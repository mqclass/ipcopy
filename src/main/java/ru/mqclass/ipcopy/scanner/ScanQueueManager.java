package ru.mqclass.ipcopy.scanner;

import net.minecraft.class_1109;
import net.minecraft.class_124;
import net.minecraft.class_2558;
import net.minecraft.class_2561;
import net.minecraft.class_2568;
import net.minecraft.class_2583;
import net.minecraft.class_2960;
import net.minecraft.class_310;
import net.minecraft.class_3414;
import net.minecraft.class_5250;
import ru.mqclass.ipcopy.IpCopyProcessor;
import ru.mqclass.ipcopy.config.IpCopyConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread-safe scan dispatcher and queue manager with LRU TTL cache and rate limiting.
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class ScanQueueManager {

    private static final ScanQueueManager INSTANCE = new ScanQueueManager();

    // Rate limiter default (1350ms protects against server 1200ms cooldown kick)
    public static final long DEFAULT_RATE_LIMIT_MS = 1350L;
    private static final long CACHE_TTL_MS = 30L * 60L * 1000L; // 30 minutes TTL

    // In-memory cache record
    public record CachedScanResult(String ip, List<String> twinks, long timestamp) {
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private final Map<String, CachedScanResult> ipCache = new ConcurrentHashMap<>();
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ipcopy-scanner-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile ScanSession currentSession = null;
    private volatile ScheduledFuture<?> currentTask = null;
    private volatile String awaitingResponseForIp = null;
    private final Map<String, List<String>> pendingSubnetSiblings = new ConcurrentHashMap<>();

    private ScanQueueManager() {}

    public static ScanQueueManager getInstance() {
        return INSTANCE;
    }

    public ScanSession getCurrentSession() {
        return currentSession;
    }

    public boolean isScanning() {
        ScanSession session = currentSession;
        return session != null && session.getState() == ScanSession.State.RUNNING;
    }

    public synchronized void startScan(String targetNick, Collection<String> rawIps, String commandTemplate) {
        if (targetNick == null || rawIps == null || rawIps.isEmpty()) {
            return;
        }

        // Cancel previous task if active
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }

        // Clean & filter valid IPv4 addresses preserving unique order
        Set<String> uniqueIps = new LinkedHashSet<>();
        for (String ip : rawIps) {
            if (ip != null) {
                String clean = ip.trim();
                if (IpCopyProcessor.isValidIp(clean)) {
                    uniqueIps.add(clean);
                }
            }
        }

        if (uniqueIps.isEmpty()) {
            return;
        }

        queue.clear();
        ScanSession session = new ScanSession(targetNick, uniqueIps.size(), commandTemplate);
        this.currentSession = session;
        session.setState(ScanSession.State.RUNNING);

        // Filter against 30-min TTL cache
        List<String> needNetworkScan = new ArrayList<>();
        for (String ip : uniqueIps) {
            CachedScanResult cached = ipCache.get(ip);
            if (cached != null && !cached.isExpired()) {
                session.recordResult(ip, cached.twinks(), true);
            } else {
                needNetworkScan.add(ip);
            }
        }

        if (needNetworkScan.isEmpty()) {
            // All IPs were cached!
            finishScan(session);
            return;
        }

        // Subnet /24 clustering optimization: query each distinct provider pool only once
        Map<String, List<String>> subnetGroups = new LinkedHashMap<>();
        for (String ip : needNetworkScan) {
            String cidr = ru.mqclass.ipcopy.forensics.IpForensicEngine.getCidr24(ip);
            subnetGroups.computeIfAbsent(cidr, k -> new ArrayList<>()).add(ip);
        }

        pendingSubnetSiblings.clear();
        for (Map.Entry<String, List<String>> entry : subnetGroups.entrySet()) {
            List<String> list = entry.getValue();
            if (!list.isEmpty()) {
                String rep = list.get(0);
                pendingSubnetSiblings.put(rep, new ArrayList<>(list));
                queue.offer(rep);
            }
        }

        scheduleNextStep(50);
    }

    public synchronized void pauseScan() {
        ScanSession session = currentSession;
        if (session != null && session.getState() == ScanSession.State.RUNNING) {
            session.setState(ScanSession.State.PAUSED);
            if (currentTask != null) {
                currentTask.cancel(false);
            }
        }
    }

    public synchronized void resumeScan() {
        ScanSession session = currentSession;
        if (session != null && session.getState() == ScanSession.State.PAUSED) {
            session.setState(ScanSession.State.RUNNING);
            scheduleNextStep(100);
        }
    }

    public synchronized void cancelScan() {
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        queue.clear();
        awaitingResponseForIp = null;
        ScanSession session = currentSession;
        if (session != null) {
            session.setState(ScanSession.State.IDLE);
        }
    }

    private void scheduleNextStep(long delayMs) {
        currentTask = scheduler.schedule(this::processNextQueueItem, delayMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void processNextQueueItem() {
        ScanSession session = currentSession;
        if (session == null || session.getState() != ScanSession.State.RUNNING) {
            return;
        }

        String nextIp = queue.poll();
        if (nextIp == null) {
            finishScan(session);
            return;
        }

        session.setCurrentIp(nextIp);
        this.awaitingResponseForIp = nextIp;

        // Dispatch command safely via CommandDispatcher
        String cmd = session.getCommandTemplate().replace("%ip%", nextIp);
        ru.mqclass.ipcopy.network.CommandDispatcher.dispatch(cmd);

        // Wait rate limit before dispatching the next IP
        long delay = Math.max(DEFAULT_RATE_LIMIT_MS, (long) IpCopyConfig.getInstance().scanDelayMs);

        scheduleNextStep(delay);
    }

    private synchronized void finishScan(ScanSession session) {
        session.setState(ScanSession.State.COMPLETED);
        session.setCurrentIp(null);
        awaitingResponseForIp = null;

        class_310 client = class_310.method_1551();
        if (client == null) return;

        client.execute(() -> {
            // 1. Play finish sound: block.note_block.bell
            try {
                class_3414 bellSound = class_3414.method_47908(
                    class_2960.method_60655("minecraft", "block.note_block.bell")
                );
                client.method_1483().method_4873(class_1109.method_4758(bellSound, 1.0F));
            } catch (Throwable ignored) {}

            // 2. Tactile toast feedback
            if (client.method_1566() != null && IpCopyConfig.getInstance().toastFeedback) {
                client.method_1566().method_1999(new ru.mqclass.ipcopy.toast.IpToast(
                    class_2561.method_43470("§6[IPCopy] §a✔ Проверка завершена!"),
                    class_2561.method_43470("§eИгрок: §f" + session.getTargetNick() + " §7(Твинков: §c" + session.getTwinksFoundCount() + "§7)")
                ));
            }

            // 3. Post interactive report message in player chat
            if (client.field_1724 != null) {
                client.field_1724.method_7353(createChatCompletionMessage(session), false);
            }
        });
    }

    /**
     * Intercepts server responses from /dupeip or /check while scan is running.
     * Returns true if message was matched and should be suppressed from chat.
     */
    public boolean handleServerChatLine(String cleanText) {
        ScanSession session = currentSession;
        if (session == null || session.getState() != ScanSession.State.RUNNING) {
            return false;
        }

        if (cleanText == null || cleanText.isEmpty()) return false;
        String lower = cleanText.toLowerCase(Locale.ROOT);

        boolean isDupeRelated = lower.contains("dupe") || lower.contains("твинк")
            || lower.contains("аккаунт") || lower.contains("совпаден")
            || lower.contains("игроки с таким ip")
            || (lower.contains("ip") && (lower.contains("найдено") || lower.contains("базе")));

        if (!isDupeRelated) {
            return false;
        }

        String targetIp = awaitingResponseForIp;
        if (targetIp != null) {
            List<String> twinks = extractTwinksFromLine(cleanText, session.getTargetNick());
            List<String> siblings = pendingSubnetSiblings.getOrDefault(targetIp, List.of(targetIp));
            for (String sibIp : siblings) {
                if (!session.getResults().containsKey(sibIp)) {
                    session.recordResult(sibIp, twinks, false);
                    ipCache.put(sibIp, new CachedScanResult(sibIp, twinks, System.currentTimeMillis()));
                }
            }
        }

        return true; // Suppress from user's chat HUD
    }

    private List<String> extractTwinksFromLine(String text, String targetNick) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean isClean = lower.contains("не найден") || lower.contains("не обнаружен")
            || lower.contains("нет твинков") || lower.contains("0 твинк")
            || lower.contains("совпадений не");

        if (isClean) {
            return Collections.emptyList();
        }

        List<String> twinks = new ArrayList<>();
        Pattern p = Pattern.compile("\\b([A-Za-z0-9_]{3,16})\\b");
        Matcher m = p.matcher(text);

        Set<String> blacklisted = Set.of(
            "dupeip", "dupe", "spacetimes", "auth", "player", "login", "session",
            "info", "true", "false", "null", "uuid", "telegram", "discord", "admin", "moder", "check"
        );

        while (m.find()) {
            String word = m.group(1);
            String wordLower = word.toLowerCase(Locale.ROOT);
            if (blacklisted.contains(wordLower)) continue;
            if (word.equalsIgnoreCase(targetNick)) continue;
            if (word.matches("^\\d+$")) continue;
            if (!twinks.contains(word)) {
                twinks.add(word);
            }
        }
        return twinks;
    }

    private class_2561 createChatCompletionMessage(ScanSession session) {
        class_5250 title = class_2561.method_43470(
            "\n§6§l[IPCopy] §a✔ Пакетная проверка завершена для §e" + session.getTargetNick() + "§a!\n" +
            "§7» Проверено IP: §f" + session.getTotalIps() +
            " §7| Твинков обнаружено: §c" + session.getTwinksFoundCount() + "\n"
        );

        String fullReport = session.buildSummaryReport();

        // 1. Copy Report button
        class_5250 copyReportBtn = class_2561.method_43470("§6[📋 СКОПИРОВАТЬ ОТЧЕТ]")
            .method_10862(class_2583.field_24360
                .method_10977(class_124.field_1065)
                .method_10958(new class_2558.class_10606(fullReport))
                .method_10949(new class_2568.class_10613(class_2561.method_43470("§eНажмите, чтобы скопировать весь отчет в буфер обмена"))));

        // 2. Open GUI button
        class_5250 openGuiBtn = class_2561.method_43470(" §a[🔍 В GUI]\n")
            .method_10862(class_2583.field_24360
                .method_10977(class_124.field_1060)
                .method_10958(new class_2558.class_10609("/ipcopy gui " + session.getTargetNick()))
                .method_10949(new class_2568.class_10613(class_2561.method_43470("§eНажмите, чтобы открыть подробное досье в интерфейсе"))));

        return title.method_10852(copyReportBtn).method_10852(openGuiBtn);
    }

    public void clearCache() {
        ipCache.clear();
    }
}
