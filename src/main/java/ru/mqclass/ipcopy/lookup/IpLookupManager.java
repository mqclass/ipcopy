package ru.mqclass.ipcopy.lookup;

import net.minecraft.class_2558;
import net.minecraft.class_2561;
import net.minecraft.class_2583;
import net.minecraft.class_310;
import ru.mqclass.ipcopy.IpCopyProcessor;
import ru.mqclass.ipcopy.config.IpCopyConfig;
import ru.mqclass.ipcopy.feedback.IpFeedback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages player IP lookups, parses player profiles and login history responses.
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class IpLookupManager {

    public record PlayerIpEntry(String date, String ip, String sessionType) {}

    public record PlayerProfile(
        String nick,
        String uuid,
        String premium,
        String vk,
        String telegram,
        String discord
    ) {}

    public record UniqueIpGroup(
        String ip,
        String subnet24,
        int count,
        double percentage,
        String firstDate,
        String lastDate,
        List<String> twinks,
        boolean checked
    ) {}

    public enum LookupStatus {
        IDLE,
        WAITING_INFO,
        FETCHING_HISTORY,
        FOUND,
        NOT_REGISTERED,
        NO_HISTORY,
        TIMED_OUT
    }

    public static final class PlayerLookupData {
        public final String nick;
        public volatile PlayerProfile profile;
        public final List<PlayerIpEntry> entries = new CopyOnWriteArrayList<>();
        public final List<PlayerIpEntry> allSessions = new CopyOnWriteArrayList<>();
        public final Map<String, List<String>> ipTwinksMap = new ConcurrentHashMap<>();
        public final Set<String> ipCheckedForTwinks = Collections.synchronizedSet(new LinkedHashSet<>());
        public final Set<String> allCollectedIps = Collections.synchronizedSet(new LinkedHashSet<>());
        public volatile LookupStatus status = LookupStatus.IDLE;
        public volatile String errorMessage = null;
        public volatile String followUpCommand = null;
        public volatile long lastUpdated = System.currentTimeMillis();

        // Server pagination fields for SpaceTimes
        public volatile String cmdFirst = null;
        public volatile String cmdPrev = null;
        public volatile String cmdNext = null;
        public volatile String cmdLast = null;
        public volatile int serverCurrentPage = 1;
        public volatile int serverTotalPages = 1;
        public volatile boolean hasServerPagination = false;

        public PlayerLookupData(String nick) {
            this.nick = nick;
        }

        public void resetNavigation() {
            this.cmdFirst = null;
            this.cmdPrev = null;
            this.cmdNext = null;
            this.cmdLast = null;
            this.hasServerPagination = false;
        }
    }

    private static final int MAX_CACHE_PLAYERS = 100;
    private static final Map<String, PlayerLookupData> CACHE =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PlayerLookupData> eldest) {
                return size() > MAX_CACHE_PLAYERS;
            }
        });

    private static final Pattern NICK_INFO_PATTERN = Pattern.compile(
        "Ник:\\s*[^A-Za-z0-9_]*(?:\\[[^\\]]*\\]\\s*)?([A-Za-z0-9_]{1,16})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "UUID:\\s*([0-9a-fA-F-]+)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PREMIUM_PATTERN = Pattern.compile(
        "Премиум:\\s*([^\r\n]+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern VK_PATTERN = Pattern.compile(
        "VK:\\s*([^\r\n]+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern TELEGRAM_PATTERN = Pattern.compile(
        "Telegram:\\s*([^\r\n]+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern DISCORD_PATTERN = Pattern.compile(
        "Discord:\\s*([^\r\n]+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern NOT_REGISTERED_PATTERN = Pattern.compile(
        "(?:Указанный\\s+)?игрок,?\\s*[^A-Za-z0-9_]*(?:\\[[^\\]]*\\]\\s*)?([A-Za-z0-9_]{1,16}),?\\s+не\\s+(?:зарегистрирован|зарегестрирован|найден)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern LOGINS_HEADER_PATTERN = Pattern.compile(
        "(?:Входы|Авторизации|История входов|Logins of)\\s+(?:игрока\\s+)?[^A-Za-z0-9_]*(?:\\[[^\\]]*\\]\\s*)?([A-Za-z0-9_]{1,16})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern PAGE_HEADER_PATTERN = Pattern.compile(
        "\\((\\d+)\\s*/\\s*(\\d+)\\)"
    );

    private static final Pattern DATE_PATTERN = Pattern.compile(
        "(\\d{2}[-./]\\d{2}[-./]\\d{4}\\s+\\d{2}:\\d{2})"
    );

    private static volatile String activeQueryNick = null;
    private static volatile long activeQueryTime = 0L;
    private static volatile String activeHistoryNick = null;
    private static volatile long activeHistoryStartTime = 0L;
    private static volatile Consumer<String> updateListener = null;
    private static volatile long lastAutoFetchTime = 0L;
    private static volatile String lastAutoFetchCmd = "";

    // Scheduler for rate-safe automated queries (1350ms delays)
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ipcopy-automation-scheduler");
        t.setDaemon(true);
        return t;
    });

    // Auto-Crawler state
    private static volatile boolean autoCrawling = false;
    private static volatile String autoCrawlNick = null;
    private static volatile int autoCrawlCurrentPage = 1;
    private static volatile int autoCrawlTotalPages = 1;
    private static final Set<Integer> autoCrawlVisitedPages = Collections.synchronizedSet(new HashSet<>());
    private static volatile ScheduledFuture<?> autoCrawlWatchdog = null;

    // Batch Dupe state
    private static volatile boolean batchDupeRunning = false;
    private static volatile String batchDupeNick = null;
    private static volatile String batchDupeCurrentIp = null;
    private static volatile int batchDupeTotal = 0;
    private static volatile int batchDupeCurrentIndex = 0;
    private static final Queue<String> batchDupeQueue = new ConcurrentLinkedQueue<>();

    static {
        // Pre-fill Odinoky profile from actual server format
        PlayerLookupData odinoky = new PlayerLookupData("Odinoky");
        odinoky.profile = new PlayerProfile(
            "Odinoky",
            "bb291d35-9b8f-382b-9c2f-dee1a151c559",
            "нет",
            "Odinoky Raze",
            "@mq_class",
            "-"
        );
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 12:59", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 12:34", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 00:30", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 00:20", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 00:01", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("11-09-2026 23:50", "185.230.240.209", "session"));
        for (PlayerIpEntry e : odinoky.entries) {
            odinoky.allCollectedIps.add(e.ip());
        }
        odinoky.allSessions.addAll(odinoky.entries);
        odinoky.ipCheckedForTwinks.add("185.230.240.209");
        odinoky.ipTwinksMap.put("185.230.240.209", List.of("DiNoKy", "mqclass"));
        odinoky.serverCurrentPage = 28;
        odinoky.serverTotalPages = 29;
        odinoky.hasServerPagination = true;
        odinoky.cmdFirst = "/auth find login by player Odinoky 1";
        odinoky.cmdPrev = "/auth find login by player Odinoky 27";
        odinoky.cmdNext = "/auth find login by player Odinoky 29";
        odinoky.cmdLast = "/auth find login by player Odinoky 29";
        odinoky.status = LookupStatus.FOUND;
        CACHE.put("odinoky", odinoky);

        // Pre-fill demo DiNoKy
        PlayerLookupData dinoky = new PlayerLookupData("DiNoKy");
        dinoky.entries.add(new PlayerIpEntry("11-09-2026 23:08", "178.62.204.18", "session"));
        dinoky.entries.add(new PlayerIpEntry("05-09-2026 21:45", "94.25.180.12", "session"));
        dinoky.entries.add(new PlayerIpEntry("02-09-2026 23:02", "185.230.240.209", "session"));
        for (PlayerIpEntry e : dinoky.entries) {
            dinoky.allCollectedIps.add(e.ip());
        }
        dinoky.allSessions.addAll(dinoky.entries);
        dinoky.ipCheckedForTwinks.add("185.230.240.209");
        dinoky.ipTwinksMap.put("185.230.240.209", List.of("Odinoky", "mqclass"));
        dinoky.ipCheckedForTwinks.add("178.62.204.18");
        dinoky.ipTwinksMap.put("178.62.204.18", Collections.emptyList());
        dinoky.status = LookupStatus.FOUND;
        CACHE.put("dinoky", dinoky);
    }

    private IpLookupManager() {}

    public static String stripColorCodes(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)§[0-9a-fk-or]", "");
    }

    public static void setUpdateListener(Consumer<String> listener) {
        updateListener = listener;
    }

    public static String getActiveQueryNick() {
        return activeQueryNick;
    }

    public static boolean isQueryPending(String nick) {
        if (activeQueryNick == null || nick == null) return false;
        if (!activeQueryNick.equalsIgnoreCase(nick)) return false;
        return (System.currentTimeMillis() - activeQueryTime) < 5000L;
    }

    public static boolean queryPlayer(String nick) {
        if (nick == null || nick.trim().isEmpty()) {
            return false;
        }

        String target = nick.trim();
        activeQueryNick = target;
        activeQueryTime = System.currentTimeMillis();

        PlayerLookupData data = getOrCreateData(target);
        data.status = LookupStatus.WAITING_INFO;
        data.errorMessage = null;

        class_310 client = class_310.method_1551();
        if (client != null && client.method_1562() != null) {
            client.method_1562().method_45730("auth player " + target + " info");
            return true;
        }
        return false;
    }

    public static PlayerLookupData getData(String nick) {
        if (nick == null) return null;
        return CACHE.get(nick.trim().toLowerCase(Locale.ROOT));
    }

    public static PlayerProfile getProfile(String nick) {
        PlayerLookupData data = getData(nick);
        return data != null ? data.profile : null;
    }

    public static List<PlayerIpEntry> getEntries(String nick) {
        PlayerLookupData data = getData(nick);
        return data != null ? new ArrayList<>(data.entries) : Collections.emptyList();
    }

    public static List<PlayerIpEntry> getAllSessions(String nick) {
        PlayerLookupData data = getData(nick);
        if (data == null) return Collections.emptyList();
        return !data.allSessions.isEmpty() ? new ArrayList<>(data.allSessions) : new ArrayList<>(data.entries);
    }

    public static List<UniqueIpGroup> getUniqueGroups(String nick) {
        PlayerLookupData data = getData(nick);
        if (data == null) return Collections.emptyList();

        List<PlayerIpEntry> source = !data.allSessions.isEmpty() ? data.allSessions : data.entries;
        if (source.isEmpty()) return Collections.emptyList();

        int totalSessions = source.size();
        Map<String, List<PlayerIpEntry>> byIp = new LinkedHashMap<>();
        for (PlayerIpEntry entry : source) {
            byIp.computeIfAbsent(entry.ip(), k -> new ArrayList<>()).add(entry);
        }

        List<UniqueIpGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<PlayerIpEntry>> e : byIp.entrySet()) {
            String ip = e.getKey();
            List<PlayerIpEntry> list = e.getValue();
            int count = list.size();
            double pct = (count * 100.0) / totalSessions;
            String subnet = ru.mqclass.ipcopy.lookup.SubnetMatcher.getSubnet24String(ip);

            String firstDate = list.get(list.size() - 1).date(); // oldest
            String lastDate = list.get(0).date(); // newest

            List<String> twinks = data.ipTwinksMap.getOrDefault(ip, Collections.emptyList());
            boolean checked = data.ipCheckedForTwinks.contains(ip);

            groups.add(new UniqueIpGroup(ip, subnet, count, pct, firstDate, lastDate, twinks, checked));
        }

        groups.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return groups;
    }

    // ================= Auto-Crawler Engine =================

    public static boolean isAutoCrawling() {
        return autoCrawling;
    }

    public static String getAutoCrawlNick() {
        return autoCrawlNick;
    }

    public static int getAutoCrawlCurrentPage() {
        return autoCrawlCurrentPage;
    }

    public static int getAutoCrawlTotalPages() {
        return autoCrawlTotalPages;
    }

    public static synchronized void startAutoCrawl(String nick) {
        if (nick == null || nick.trim().isEmpty()) return;
        PlayerLookupData data = getData(nick);
        if (data == null || data.serverTotalPages <= 1) return;

        autoCrawling = true;
        autoCrawlNick = nick.trim();
        autoCrawlCurrentPage = data.serverCurrentPage;
        autoCrawlTotalPages = data.serverTotalPages;
        autoCrawlVisitedPages.clear();
        autoCrawlVisitedPages.add(data.serverCurrentPage);

        notifyListener(nick);
        scheduleNextCrawlStep(100);
    }

    public static synchronized void stopAutoCrawl(boolean completed) {
        if (!autoCrawling) return;
        autoCrawling = false;
        String nick = autoCrawlNick;
        autoCrawlNick = null;

        if (completed && nick != null) {
            PlayerLookupData data = getData(nick);
            int total = data != null ? (!data.allSessions.isEmpty() ? data.allSessions.size() : data.entries.size()) : 0;
            int unique = data != null ? data.allCollectedIps.size() : 0;
            IpFeedback.onAutoCrawlFinished(nick, total, unique);
        }
        notifyListener(nick);
    }

    private static void scheduleNextCrawlStep(long delayMs) {
        if (!autoCrawling || autoCrawlNick == null) return;

        SCHEDULER.schedule(() -> {
            if (!autoCrawling || autoCrawlNick == null) return;
            PlayerLookupData data = getData(autoCrawlNick);
            if (data == null) {
                stopAutoCrawl(false);
                return;
            }

            int nextTarget = -1;
            for (int p = 1; p <= autoCrawlTotalPages; p++) {
                if (!autoCrawlVisitedPages.contains(p)) {
                    nextTarget = p;
                    break;
                }
            }

            if (nextTarget == -1) {
                stopAutoCrawl(true);
                return;
            }

            autoCrawlCurrentPage = nextTarget;
            notifyListener(autoCrawlNick);

            executeServerCommand("auth find login by player " + autoCrawlNick + " " + nextTarget);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    // ================= Batch Dupe Engine =================

    public static boolean isBatchDupeRunning() {
        return batchDupeRunning;
    }

    public static String getBatchDupeNick() {
        return batchDupeNick;
    }

    public static String getBatchDupeProgress() {
        return batchDupeCurrentIndex + "/" + batchDupeTotal;
    }

    public static synchronized void startBatchDupe(String nick) {
        if (nick == null || nick.trim().isEmpty()) return;
        PlayerLookupData data = getData(nick);
        if (data == null) return;

        List<UniqueIpGroup> groups = getUniqueGroups(nick);
        if (groups.isEmpty()) return;

        batchDupeQueue.clear();
        for (UniqueIpGroup g : groups) {
            batchDupeQueue.add(g.ip());
        }

        batchDupeRunning = true;
        batchDupeNick = nick.trim();
        batchDupeTotal = batchDupeQueue.size();
        batchDupeCurrentIndex = 0;

        notifyListener(nick);
        runNextBatchDupeStep(50);
    }

    public static synchronized void stopBatchDupe(boolean completed) {
        if (!batchDupeRunning) return;
        batchDupeRunning = false;
        String nick = batchDupeNick;
        batchDupeNick = null;
        batchDupeCurrentIp = null;
        batchDupeQueue.clear();

        if (completed && nick != null) {
            PlayerLookupData data = getData(nick);
            int twinksCount = 0;
            if (data != null) {
                for (List<String> list : data.ipTwinksMap.values()) {
                    twinksCount += list.size();
                }
            }
            IpFeedback.onBatchDupeFinished(batchDupeTotal, twinksCount);
        }
        notifyListener(nick);
    }

    private static void runNextBatchDupeStep(long delayMs) {
        if (!batchDupeRunning) return;

        SCHEDULER.schedule(() -> {
            if (!batchDupeRunning) return;
            String nextIp = batchDupeQueue.poll();
            if (nextIp == null) {
                stopBatchDupe(true);
                return;
            }

            batchDupeCurrentIp = nextIp;
            batchDupeCurrentIndex++;
            notifyListener(batchDupeNick);

            String rawCmd = IpCopyConfig.getInstance().secondActionCommand;
            if (rawCmd == null) rawCmd = "/dupeip %ip%";
            String cmd = rawCmd.replace("%ip%", nextIp);
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            executeServerCommand(cmd);

            runNextBatchDupeStep(1400);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public static void checkDupeIpMessage(String cleanText) {
        if (cleanText == null || cleanText.isEmpty()) return;
        String lower = cleanText.toLowerCase(Locale.ROOT);

        boolean isDupeRelated = lower.contains("dupe") || lower.contains("твинк")
            || lower.contains("аккаунт") || lower.contains("совпаден")
            || (lower.contains("игрок") && (lower.contains("найдено") || lower.contains("ip") || lower.contains("базе")));

        if (!isDupeRelated) return;

        List<String> ips = IpCopyProcessor.extractIps(cleanText);
        String targetIp = !ips.isEmpty() ? ips.get(0) : batchDupeCurrentIp;
        if (targetIp == null) return;

        String nick = (batchDupeNick != null) ? batchDupeNick : activeHistoryNick;
        if (nick == null) nick = activeQueryNick;
        if (nick == null) return;

        PlayerLookupData data = getData(nick);
        if (data == null) return;

        data.ipCheckedForTwinks.add(targetIp);

        boolean isClean = lower.contains("не найден") || lower.contains("не обнаружен")
            || lower.contains("нет твинков") || lower.contains("0 твинк")
            || lower.contains("только один") || lower.contains("только этот")
            || lower.contains("совпадений не");

        List<String> twinks = new ArrayList<>();
        if (!isClean) {
            Pattern p = Pattern.compile("\\b([A-Za-z0-9_]{3,16})\\b");
            Matcher m = p.matcher(cleanText);
            Set<String> blacklistedWords = Set.of(
                "dupeip", "dupe", "spacetimes", "auth", "player", "login", "session",
                "info", "true", "false", "null", "uuid", "telegram", "discord", "admin", "moder", "helper"
            );

            while (m.find()) {
                String word = m.group(1);
                String wordLower = word.toLowerCase(Locale.ROOT);
                if (blacklistedWords.contains(wordLower)) continue;
                if (word.equalsIgnoreCase(nick)) continue;
                if (word.matches("^\\d+$")) continue;
                if (!twinks.contains(word)) {
                    twinks.add(word);
                }
            }
        }

        data.ipTwinksMap.put(targetIp, twinks);
        data.lastUpdated = System.currentTimeMillis();
        notifyListener(nick);
    }

    public static String generateExpressDossier(String nick) {
        PlayerLookupData data = getData(nick);
        if (data == null) return "Информация об игроке " + nick + " не найдена.";

        StringBuilder sb = new StringBuilder();
        sb.append("═════════════ ДОСЬЕ ИГРОКА ═════════════\n");
        sb.append("Никнейм: ").append(data.nick).append("\n");

        if (data.profile != null) {
            sb.append("UUID: ").append(data.profile.uuid()).append("\n");
            sb.append("Премиум: ").append(data.profile.premium()).append("\n");
            sb.append("Социальные сети:\n");
            sb.append("  • VK: ").append(data.profile.vk()).append("\n");
            sb.append("  • Telegram: ").append(data.profile.telegram()).append("\n");
            sb.append("  • Discord: ").append(data.profile.discord()).append("\n");
        }

        List<PlayerIpEntry> source = !data.allSessions.isEmpty() ? data.allSessions : data.entries;
        List<UniqueIpGroup> groups = getUniqueGroups(nick);

        sb.append("────────────────────────────────────────\n");
        sb.append("Всего сессий: ").append(source.size());
        if (!source.isEmpty()) {
            String oldest = source.get(source.size() - 1).date();
            String newest = source.get(0).date();
            sb.append(" (с ").append(oldest).append(" по ").append(newest).append(")");
        }
        sb.append("\n");
        sb.append("Уникальных IP-адресов: ").append(groups.size()).append("\n");
        sb.append("────────────────────────────────────────\n");
        sb.append("ДЕТАЛИЗАЦИЯ ПО IP:\n");

        if (groups.isEmpty()) {
            sb.append("  (Записи входов отсутствуют)\n");
        } else {
            for (int i = 0; i < groups.size(); i++) {
                UniqueIpGroup g = groups.get(i);
                sb.append(String.format(Locale.ROOT, "%d. %s [%s] — %d вх. (%.1f%%)\n",
                    (i + 1), g.ip(), g.subnet24(), g.count(), g.percentage()));
                sb.append("   • Период: ").append(g.firstDate()).append(" — ").append(g.lastDate()).append("\n");
                if (g.checked()) {
                    if (g.twinks().isEmpty()) {
                        sb.append("   • Твинки: не обнаружены (чист)\n");
                    } else {
                        sb.append("   • Твинки (").append(g.twinks().size()).append("): ")
                          .append(String.join(", ", g.twinks())).append("\n");
                    }
                } else {
                    sb.append("   • Твинки: не проверен\n");
                }
            }
        }

        sb.append("════════════════════════════════════════\n");
        sb.append("Сформировано через IP Copy by mqclass\n");

        return sb.toString();
    }

    public static LookupStatus getStatus(String nick) {
        PlayerLookupData data = getData(nick);
        return data != null ? data.status : LookupStatus.IDLE;
    }

    private static synchronized PlayerLookupData getOrCreateData(String nick) {
        String key = nick.trim().toLowerCase(Locale.ROOT);
        PlayerLookupData data = CACHE.get(key);
        if (data == null) {
            data = new PlayerLookupData(nick.trim());
            CACHE.put(key, data);
        }
        return data;
    }

    public static boolean isAuthMessage(String rawText) {
        if (rawText == null || rawText.isEmpty()) return false;
        String clean = stripColorCodes(rawText).trim();

        // 1. Profile block: /auth player <nick> info
        if (clean.contains("Информация об игроке") || (clean.contains("Ник:") && clean.contains("UUID:"))) {
            return true;
        }

        // 2. Player not registered
        if (NOT_REGISTERED_PATTERN.matcher(clean).find()) {
            return true;
        }

        // 3. Login history header
        if (clean.contains("Входы игрока") || clean.contains("История входов") || clean.contains("Авторизации игрока") || clean.contains("Logins of")) {
            return true;
        }

        // 4. History entries (start with box-drawing or contain date and session/login)
        if ((clean.startsWith("┣") || clean.startsWith("├") || clean.startsWith("|")) && DATE_PATTERN.matcher(clean).find()) {
            return true;
        }

        // 5. Navigation footer
        if ((clean.startsWith("┗") || clean.startsWith("└") || clean.contains("━━━━━")) &&
            (clean.contains("Начало") || clean.contains("Назад") || clean.contains("Вперёд") || clean.contains("Вперед") || clean.contains("Конец"))) {
            return true;
        }

        // 6. Empty history messages or prompt
        if (clean.contains("(0/0)") || clean.contains("нет сохраненных сессий") || clean.contains("нет записей входов") || clean.contains("Посмотреть историю входов")) {
            return true;
        }

        // 7. DupeIP responses
        if ((clean.contains("DupeIP") || clean.contains("твинк") || clean.contains("совпаден") || clean.contains("Игроки с таким IP")) &&
            (IpCopyProcessor.containsIp(clean) || batchDupeRunning)) {
            return true;
        }

        return false;
    }

    public static void inspectMessage(String rawText) {
        inspectMessage(null, rawText);
    }

    public static void inspectMessage(class_2561 message, String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return;
        }

        String cleanText = stripColorCodes(rawText);

        // Check for DupeIP responses in chat
        checkDupeIpMessage(cleanText);

        // Case 1: "Указанный игрок, ник, не зарегистрирован"
        Matcher notRegMatcher = NOT_REGISTERED_PATTERN.matcher(cleanText);
        if (notRegMatcher.find()) {
            String nick = notRegMatcher.group(1);
            if (nick == null && activeQueryNick != null) {
                nick = activeQueryNick;
            }
            if (nick != null) {
                activeHistoryNick = null;
                PlayerLookupData data = getOrCreateData(nick);
                data.entries.clear();
                data.profile = null;
                data.status = LookupStatus.NOT_REGISTERED;
                data.errorMessage = "Указанный игрок не зарегистрирован на сервере";
                data.lastUpdated = System.currentTimeMillis();

                if (activeQueryNick != null && activeQueryNick.equalsIgnoreCase(nick)) {
                    activeQueryNick = null;
                }

                notifyListener(nick);
            }
            return;
        }

        // Case 2: "Информация об игроке" (has ClickEvent for history)
        if (cleanText.contains("Информация об игроке")) {
            Matcher nickMatcher = NICK_INFO_PATTERN.matcher(cleanText);
            String nick = nickMatcher.find() ? nickMatcher.group(1) : activeQueryNick;
            if (nick != null) {
                Matcher uuidM = UUID_PATTERN.matcher(cleanText);
                Matcher premM = PREMIUM_PATTERN.matcher(cleanText);
                Matcher vkM = VK_PATTERN.matcher(cleanText);
                Matcher tgM = TELEGRAM_PATTERN.matcher(cleanText);
                Matcher dsM = DISCORD_PATTERN.matcher(cleanText);

                String uuid = uuidM.find() ? uuidM.group(1) : "";
                String premium = premM.find() ? premM.group(1).trim() : "нет";
                String vk = vkM.find() ? vkM.group(1).trim() : "-";
                String tg = tgM.find() ? tgM.group(1).trim() : "-";
                String ds = dsM.find() ? dsM.group(1).trim() : "-";

                PlayerLookupData data = getOrCreateData(nick);
                data.profile = new PlayerProfile(nick, uuid, premium, vk, tg, ds);
                data.status = LookupStatus.FETCHING_HISTORY;
                data.lastUpdated = System.currentTimeMillis();

                // Extract ClickEvent command from "Посмотреть историю входов"
                String clickCmd = findClickCommand(message, "историю входов");
                if (clickCmd == null) {
                    clickCmd = findAnyAuthClickCommand(message);
                }
                if (clickCmd == null) {
                    clickCmd = "auth find login by player " + nick;
                }
                data.followUpCommand = clickCmd;

                // If user initiated a query or checked a profile, automatically fetch the login history with a safe 1300ms delay
                boolean shouldAutoFetch = (activeQueryNick == null || isQueryPending(nick) || activeQueryNick.equalsIgnoreCase(nick));
                long now = System.currentTimeMillis();

                if (shouldAutoFetch && clickCmd != null && (now - lastAutoFetchTime > 3000L || !clickCmd.equalsIgnoreCase(lastAutoFetchCmd))) {
                    lastAutoFetchTime = now;
                    lastAutoFetchCmd = clickCmd;

                    final String finalCmd = clickCmd.startsWith("/") ? clickCmd.substring(1) : clickCmd;

                    // Delay follow-up command by 1300ms so the server spam filter never outputs "Подождите 1 сек"
                    java.util.concurrent.CompletableFuture.delayedExecutor(1300, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .execute(() -> {
                            class_310 client = class_310.method_1551();
                            if (client != null) {
                                client.execute(() -> {
                                    if (client.method_1562() != null) {
                                        client.method_1562().method_45730(finalCmd);
                                    }
                                });
                            }
                        });
                }

                notifyListener(nick);
            }
            return;
        }

        // Case 3: Logins header: "----- Входы игрока [head]nick (28/29) -----"
        Matcher loginsHeaderMatcher = LOGINS_HEADER_PATTERN.matcher(cleanText);
        if (loginsHeaderMatcher.find()) {
            String nick = loginsHeaderMatcher.group(1);
            if (nick != null) {
                activeHistoryNick = nick;
                activeHistoryStartTime = System.currentTimeMillis();
                PlayerLookupData data = getOrCreateData(nick);
                data.entries.clear();
                data.status = LookupStatus.FOUND;
                data.lastUpdated = System.currentTimeMillis();

                Matcher pageMatcher = PAGE_HEADER_PATTERN.matcher(cleanText);
                if (pageMatcher.find()) {
                    try {
                        data.serverCurrentPage = Integer.parseInt(pageMatcher.group(1));
                        data.serverTotalPages = Integer.parseInt(pageMatcher.group(2));
                        data.hasServerPagination = data.serverTotalPages > 1;
                    } catch (Exception ignored) {}
                }

                // Auto-crawler tracking
                if (autoCrawling && autoCrawlNick != null && autoCrawlNick.equalsIgnoreCase(nick)) {
                    autoCrawlVisitedPages.add(data.serverCurrentPage);
                    autoCrawlTotalPages = data.serverTotalPages;
                } else if (IpCopyConfig.getInstance().autoFetchAllPages && data.serverTotalPages > 1 && !autoCrawling) {
                    startAutoCrawl(nick);
                }

                notifyListener(nick);
            }
        }

        // Case 4: Streaming session entries: "├ 11-09-2026 23:50 · 185.230.240.209 · session"
        String targetNick = activeHistoryNick;
        if (targetNick == null && activeQueryNick != null && (System.currentTimeMillis() - activeQueryTime) < 5000L) {
            targetNick = activeQueryNick;
        }

        if (targetNick != null && (System.currentTimeMillis() - activeHistoryStartTime < 5000L || System.currentTimeMillis() - activeQueryTime < 5000L)) {
            String lower = cleanText.toLowerCase(Locale.ROOT);
            boolean isAnticheat = lower.contains("sac") || lower.contains("античит") || lower.contains("anticheat");
            if (!isAnticheat) {
                String[] lines = cleanText.split("\n");
                boolean addedAny = false;
                PlayerLookupData data = getOrCreateData(targetNick);

                for (String line : lines) {
                    List<String> ips = IpCopyProcessor.extractIps(line);
                    if (ips.isEmpty()) {
                        continue;
                    }

                    String ip = ips.get(0);
                    String date = "Неизвестно";
                    Matcher dateMatcher = DATE_PATTERN.matcher(line);
                    if (dateMatcher.find()) {
                        date = dateMatcher.group(1);
                    }

                    String type = line.toLowerCase(Locale.ROOT).contains("session") ? "session" : "login";
                    PlayerIpEntry newEntry = new PlayerIpEntry(date, ip, type);
                    data.allCollectedIps.add(ip);
                    if (!data.allSessions.contains(newEntry)) {
                        data.allSessions.add(newEntry);
                    }
                    if (!data.entries.contains(newEntry)) {
                        data.entries.add(newEntry);
                        addedAny = true;
                    }
                }

                if (addedAny) {
                    data.status = LookupStatus.FOUND;
                    data.lastUpdated = System.currentTimeMillis();
                    notifyListener(targetNick);
                } else if (cleanText.contains("(0/0)") || cleanText.contains("(0/") || lower.contains("нет записей") || lower.contains("нет сессий")) {
                    data.status = LookupStatus.NO_HISTORY;
                    data.lastUpdated = System.currentTimeMillis();
                    notifyListener(targetNick);
                }
            }
        }

        // Case 5: Navigation footer: "┗━━━━━ [Начало] [Назад] [Вперёд] [Конец] ━━━━━"
        if (message != null && activeHistoryNick != null) {
            if (cleanText.contains("Начало") || cleanText.contains("Назад") || cleanText.contains("Вперёд") || cleanText.contains("Вперед") || cleanText.contains("Конец") || cleanText.contains("━━━━━")) {
                PlayerLookupData data = getData(activeHistoryNick);
                if (data != null) {
                    extractNavigationCommands(message, data);
                    data.hasServerPagination = true;

                    if (autoCrawling && autoCrawlNick != null && autoCrawlNick.equalsIgnoreCase(activeHistoryNick)) {
                        autoCrawlVisitedPages.add(data.serverCurrentPage);
                        scheduleNextCrawlStep(1350);
                    }

                    notifyListener(activeHistoryNick);
                }
            }
        }
    }

    public static void extractNavigationCommands(class_2561 text, PlayerLookupData data) {
        if (text == null || data == null) return;

        // Pass 1: style visit across leaves
        text.method_27658((style, fragment) -> {
            if (style != null && style.method_10970() != null) {
                String clean = stripColorCodes(fragment).trim().toLowerCase(Locale.ROOT);
                String cmd = extractCommandFromClickEvent(style.method_10970());
                if (cmd != null && !cmd.isEmpty()) {
                    assignNavCommand(clean, cmd, data);
                }
            }
            return Optional.empty();
        }, class_2583.field_24360);

        // Pass 2: recursive tree traversal for siblings
        inspectNavInTree(text, data);
    }

    private static void inspectNavInTree(class_2561 text, PlayerLookupData data) {
        if (text == null) return;
        class_2583 style = text.method_10866();
        if (style != null && style.method_10970() != null) {
            String clean = stripColorCodes(text.getString()).trim().toLowerCase(Locale.ROOT);
            String cmd = extractCommandFromClickEvent(style.method_10970());
            if (cmd != null && !cmd.isEmpty()) {
                assignNavCommand(clean, cmd, data);
            }
        }
        for (class_2561 sibling : text.method_10855()) {
            inspectNavInTree(sibling, data);
        }
    }

    private static void assignNavCommand(String clean, String cmd, PlayerLookupData data) {
        if (clean.contains("начало") || clean.equals("<<") || clean.equals("|<")) {
            data.cmdFirst = cmd;
        }
        if (clean.contains("назад") || clean.equals("<")) {
            data.cmdPrev = cmd;
        }
        if (clean.contains("вперёд") || clean.contains("вперед") || clean.equals(">")) {
            data.cmdNext = cmd;
        }
        if (clean.contains("конец") || clean.equals(">>") || clean.equals(">|")) {
            data.cmdLast = cmd;
        }
    }

    public static void executeServerCommand(String command) {
        if (command == null || command.isEmpty()) return;
        class_310 client = class_310.method_1551();
        if (client != null) {
            client.execute(() -> {
                if (client.method_1562() != null) {
                    String clean = command.startsWith("/") ? command.substring(1) : command;
                    client.method_1562().method_45730(clean.trim());
                }
            });
        }
    }

    public static String findClickCommand(class_2561 text, String targetSnippet) {
        if (text == null || targetSnippet == null) return null;

        class_2583 style = text.method_10866();
        if (style != null && style.method_10970() != null) {
            String content = text.getString();
            if (content != null && content.toLowerCase(Locale.ROOT).contains(targetSnippet.toLowerCase(Locale.ROOT))) {
                String cmd = extractCommandFromClickEvent(style.method_10970());
                if (cmd != null) return cmd;
            }
        }

        for (class_2561 sibling : text.method_10855()) {
            String cmd = findClickCommand(sibling, targetSnippet);
            if (cmd != null) return cmd;
        }

        return null;
    }

    public static String findAnyAuthClickCommand(class_2561 text) {
        if (text == null) return null;

        class_2583 style = text.method_10866();
        if (style != null && style.method_10970() != null) {
            String cmd = extractCommandFromClickEvent(style.method_10970());
            if (cmd != null && (cmd.toLowerCase(Locale.ROOT).contains("auth") || cmd.toLowerCase(Locale.ROOT).contains("player") || cmd.toLowerCase(Locale.ROOT).contains("login"))) {
                return cmd;
            }
        }

        for (class_2561 sibling : text.method_10855()) {
            String cmd = findAnyAuthClickCommand(sibling);
            if (cmd != null) return cmd;
        }

        return null;
    }

    public static String extractCommandFromClickEvent(class_2558 clickEvent) {
        if (clickEvent == null) return null;

        if (clickEvent instanceof class_2558.class_10609 runCmd) {
            return runCmd.comp_3506();
        }

        if (clickEvent instanceof class_2558.class_10610 suggestCmd) {
            return suggestCmd.comp_3507();
        }

        try {
            for (java.lang.reflect.Method m : clickEvent.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                    m.setAccessible(true);
                    String val = (String) m.invoke(clickEvent);
                    if (val != null && (val.startsWith("/") || val.toLowerCase(Locale.ROOT).contains("auth"))) {
                        return val;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static void notifyListener(String nick) {
        Consumer<String> listener = updateListener;
        if (listener != null) {
            class_310 client = class_310.method_1551();
            if (client != null) {
                client.execute(() -> {
                    Consumer<String> l = updateListener;
                    if (l != null) {
                        l.accept(nick);
                    }
                });
            } else {
                listener.accept(nick);
            }
        }
    }

    public static void clearCache() {
        stopAutoCrawl(false);
        stopBatchDupe(false);
        CACHE.clear();
        updateListener = null;
        activeQueryNick = null;
        activeQueryTime = 0L;

        // Restore default demos
        PlayerLookupData odinoky = new PlayerLookupData("Odinoky");
        odinoky.profile = new PlayerProfile(
            "Odinoky",
            "bb291d35-9b8f-382b-9c2f-dee1a151c559",
            "нет",
            "Odinoky Raze",
            "@mq_class",
            "-"
        );
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 12:59", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 12:34", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 00:30", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 00:20", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 00:01", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("11-09-2026 23:50", "185.230.240.209", "session"));
        for (PlayerIpEntry e : odinoky.entries) {
            odinoky.allCollectedIps.add(e.ip());
        }
        odinoky.allSessions.addAll(odinoky.entries);
        odinoky.ipCheckedForTwinks.add("185.230.240.209");
        odinoky.ipTwinksMap.put("185.230.240.209", List.of("DiNoKy", "mqclass"));
        odinoky.serverCurrentPage = 28;
        odinoky.serverTotalPages = 29;
        odinoky.hasServerPagination = true;
        odinoky.cmdFirst = "/auth find login by player Odinoky 1";
        odinoky.cmdPrev = "/auth find login by player Odinoky 27";
        odinoky.cmdNext = "/auth find login by player Odinoky 29";
        odinoky.cmdLast = "/auth find login by player Odinoky 29";
        odinoky.status = LookupStatus.FOUND;
        CACHE.put("odinoky", odinoky);

        PlayerLookupData dinoky = new PlayerLookupData("DiNoKy");
        dinoky.entries.add(new PlayerIpEntry("11-09-2026 23:08", "178.62.204.18", "session"));
        dinoky.entries.add(new PlayerIpEntry("05-09-2026 21:45", "94.25.180.12", "session"));
        dinoky.entries.add(new PlayerIpEntry("02-09-2026 23:02", "185.230.240.209", "session"));
        for (PlayerIpEntry e : dinoky.entries) {
            dinoky.allCollectedIps.add(e.ip());
        }
        dinoky.allSessions.addAll(dinoky.entries);
        dinoky.ipCheckedForTwinks.add("185.230.240.209");
        dinoky.ipTwinksMap.put("185.230.240.209", List.of("Odinoky", "mqclass"));
        dinoky.ipCheckedForTwinks.add("178.62.204.18");
        dinoky.ipTwinksMap.put("178.62.204.18", Collections.emptyList());
        dinoky.status = LookupStatus.FOUND;
        CACHE.put("dinoky", dinoky);
    }
}
