package ru.mqclass.ipcopy.scraper;

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
import ru.mqclass.ipcopy.forensics.IpForensicEngine;
import ru.mqclass.ipcopy.hud.ForensicHudOverlay;
import ru.mqclass.ipcopy.network.CommandDispatcher;
import ru.mqclass.ipcopy.risk.AsnRiskLookupService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Session Scraper Finite State Machine (FSM) & Auto-Pager.
 * Intercepts incoming Minecraft chat messages, detects login history multi-page tables,
 * schedules rapid background page requests (75ms debounce), suppresses raw chat spam,
 * and passes captured session pools to the Forensic Engine and AsnRiskLookupService.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class SessionCaptureFSM {

    public enum State {
        IDLE,
        SESSION_START,
        SCRAPING_PAGES,
        PROCESSING,
        COMPLETED
    }

    private static final SessionCaptureFSM INSTANCE = new SessionCaptureFSM();

    // Regex for Header: "Входы игрока [head]nick (28/28)" or "Входы игрока (1/5)"
    private static final Pattern HEADER_PATTERN = Pattern.compile(
        "(?i)(?:Входы игрока|Logins of player)[^\\(]*\\((\\d+)[/из](\\d+)\\)"
    );

    // Regex for Page Footer: "Страница (1/5)" or "Page 1 of 5"
    private static final Pattern FOOTER_PAGE_PATTERN = Pattern.compile(
        "(?i)(?:Страница|Page)\\s*\\(?(\\d+)[/из](\\d+)\\)?"
    );

    // Regex for player name in header
    private static final Pattern NICK_PATTERN = Pattern.compile(
        "(?i)(?:Входы игрока|Logins of player)\\s+(?:\\[[^\\]]+\\])?([A-Za-z0-9_]{3,16})"
    );

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "IPCopy-SessionFSM");
        t.setDaemon(true);
        return t;
    });

    private volatile State state = State.IDLE;
    private volatile String currentTargetNick = "";
    private volatile int totalExpectedPages = 1;
    private volatile int totalIngestedSessions = 0;
    private final Set<Integer> ingestedPages = ConcurrentHashMap.newKeySet();
    private final Set<String> capturedRawIps = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<String, IpForensicEngine.SubnetCluster> analyzedClusters = new ConcurrentHashMap<>();
    private volatile int overallRiskScore = 0;

    private ScheduledFuture<?> idleTimeoutFuture = null;
    private volatile long lastMessageTimestamp = 0L;

    private SessionCaptureFSM() {}

    public static SessionCaptureFSM getInstance() {
        return INSTANCE;
    }

    public State getState() {
        return state;
    }

    public String getCurrentTargetNick() {
        return currentTargetNick;
    }

    public int getTotalIngestedSessions() {
        return totalIngestedSessions;
    }

    public Map<String, IpForensicEngine.SubnetCluster> getAnalyzedClusters() {
        return analyzedClusters;
    }

    public int getOverallRiskScore() {
        return overallRiskScore;
    }

    public Set<String> getCapturedRawIps() {
        return new LinkedHashSet<>(capturedRawIps);
    }

    /**
     * Resets FSM state and cancels active scraping sessions.
     */
    public synchronized void cancel() {
        if (idleTimeoutFuture != null) {
            idleTimeoutFuture.cancel(true);
            idleTimeoutFuture = null;
        }
        state = State.IDLE;
        currentTargetNick = "";
        totalExpectedPages = 1;
        totalIngestedSessions = 0;
        ingestedPages.clear();
        capturedRawIps.clear();
        analyzedClusters.clear();
        overallRiskScore = 0;
    }

    /**
     * Inspects inbound chat line. Returns true if the line belongs to active scraping
     * and should be suppressed from the player's chat HUD.
     */
    public boolean handleInboundMessage(String rawText, class_2561 message) {
        if (rawText == null || rawText.isBlank()) return false;
        String clean = IpForensicEngine.cleanText(rawText);

        // 1. Detect Start Header
        Matcher headerMatcher = HEADER_PATTERN.matcher(clean);
        if (headerMatcher.find()) {
            startScrapingSession(clean, headerMatcher);
            return true;
        }

        // 2. If actively scraping, collect session entries and check page footers
        if (state == State.SESSION_START || state == State.SCRAPING_PAGES) {
            lastMessageTimestamp = System.currentTimeMillis();
            restartIdleTimeout(350L);

            // Extract IPv4 addresses
            List<String> ips = IpForensicEngine.extractUniqueIps(clean);
            if (!ips.isEmpty()) {
                capturedRawIps.addAll(ips);
                totalIngestedSessions += ips.size();
                return true;
            }

            // Check footer pagination
            Matcher footerMatcher = FOOTER_PAGE_PATTERN.matcher(clean);
            if (footerMatcher.find()) {
                try {
                    int curPage = Integer.parseInt(footerMatcher.group(1));
                    int totalPgs = Integer.parseInt(footerMatcher.group(2));
                    handlePageFooterDetected(curPage, totalPgs);
                } catch (NumberFormatException ignored) {}
                return true;
            }

            // Check SpaceTimes navigation decoration line
            if (clean.contains("Начало") && clean.contains("Назад") && clean.contains("Вперёд")) {
                return true;
            }

            // Check separator lines
            if (clean.startsWith("┏━━━━━") || clean.startsWith("┣") || clean.startsWith("┗━━━━━")) {
                return true;
            }
        }

        return false;
    }

    private synchronized void startScrapingSession(String headerLine, Matcher matcher) {
        cancel();
        state = State.SESSION_START;
        lastMessageTimestamp = System.currentTimeMillis();

        // Extract target nick
        Matcher nickMatcher = NICK_PATTERN.matcher(headerLine);
        if (nickMatcher.find()) {
            this.currentTargetNick = nickMatcher.group(1);
        } else {
            this.currentTargetNick = "Target";
        }

        try {
            int currentPage = Integer.parseInt(matcher.group(1));
            int total = Integer.parseInt(matcher.group(2));
            this.totalExpectedPages = Math.max(1, total);
            ingestedPages.add(currentPage);

            // If more than 1 page exists, schedule auto-paging for remaining pages
            if (total > 1) {
                scheduleAutoPaging(currentTargetNick, total);
            }
        } catch (NumberFormatException ignored) {
            this.totalExpectedPages = 1;
        }

        state = State.SCRAPING_PAGES;
        restartIdleTimeout(500L);
    }

    private void scheduleAutoPaging(String nick, int totalPages) {
        for (int p = 2; p <= totalPages; p++) {
            final int pageToFetch = p;
            long delay = (long) (p - 1) * 75L; // 75ms debounce delay per page

            scheduler.schedule(() -> {
                if (state == State.SCRAPING_PAGES && currentTargetNick.equalsIgnoreCase(nick)) {
                    // Dispatch silent server query
                    CommandDispatcher.dispatch("auth find login by player " + nick + " " + pageToFetch);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void handlePageFooterDetected(int currentPage, int totalPages) {
        ingestedPages.add(currentPage);
        this.totalExpectedPages = Math.max(this.totalExpectedPages, totalPages);

        if (ingestedPages.size() >= totalExpectedPages) {
            // All pages captured!
            triggerForensicAnalysis();
        }
    }

    private synchronized void restartIdleTimeout(long delayMs) {
        if (idleTimeoutFuture != null) {
            idleTimeoutFuture.cancel(false);
        }
        idleTimeoutFuture = scheduler.schedule(this::triggerForensicAnalysis, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Executes async forensic clustering and ASN/VPN risk scoring.
     */
    private synchronized void triggerForensicAnalysis() {
        if (state == State.PROCESSING || state == State.COMPLETED || capturedRawIps.isEmpty()) {
            return;
        }

        state = State.PROCESSING;
        if (idleTimeoutFuture != null) {
            idleTimeoutFuture.cancel(false);
            idleTimeoutFuture = null;
        }

        final String nick = this.currentTargetNick;
        final Set<String> ips = new LinkedHashSet<>(capturedRawIps);
        final int totalSessions = this.totalIngestedSessions;

        // Perform async ASN and risk scoring lookups via Virtual Threads
        AsnRiskLookupService.getInstance().lookupAllSubnetsAsync(ips).thenAccept(riskMap -> {
            Map<String, IpForensicEngine.SubnetCluster> baseClusters = IpForensicEngine.clusterSubnets(ips);
            Map<String, IpForensicEngine.SubnetCluster> enrichedClusters = new LinkedHashMap<>();

            int maxRisk = 0;
            int totalRiskSum = 0;

            for (Map.Entry<String, IpForensicEngine.SubnetCluster> entry : baseClusters.entrySet()) {
                String cidr = entry.getKey();
                IpForensicEngine.SubnetCluster cluster = entry.getValue();
                AsnRiskLookupService.AsnRiskRecord record = riskMap.get(cidr);

                if (record != null) {
                    IpForensicEngine.SubnetCluster enriched = cluster.withRiskData(
                        record.asn(), record.ispOrganization(), record.countryCode(),
                        record.city(), record.isProxy(), record.isHosting(), record.riskScore()
                    );
                    enrichedClusters.put(cidr, enriched);
                    maxRisk = Math.max(maxRisk, record.riskScore());
                    totalRiskSum += record.riskScore();
                } else {
                    enrichedClusters.put(cidr, cluster);
                }
            }

            int avgRisk = !enrichedClusters.isEmpty() ? (totalRiskSum / enrichedClusters.size()) : 0;
            this.overallRiskScore = Math.max(maxRisk, avgRisk);
            this.analyzedClusters.clear();
            this.analyzedClusters.putAll(enrichedClusters);
            this.state = State.COMPLETED;

            // Notify player and play finish sound
            postCompletionSummary(nick, totalSessions, enrichedClusters.values(), overallRiskScore, ips);
        });
    }

    private void postCompletionSummary(
        String nick, int totalSessions,
        java.util.Collection<IpForensicEngine.SubnetCluster> clusters,
        int risk, Set<String> allIps
    ) {
        class_310 client = class_310.method_1551();
        if (client == null) return;

        client.execute(() -> {
            // 1. Play finish bell sound
            try {
                class_3414 bell = class_3414.method_47908(class_2960.method_60655("minecraft", "block.note_block.bell"));
                client.method_1483().method_4873(class_1109.method_4758(bell, 1.0F));
            } catch (Throwable ignored) {}

            // 2. Prepare Clipboard payloads
            String rawIpsList = String.join(", ", allIps);
            String fullMarkdown = IpForensicEngine.buildDiscordReport(nick, totalSessions, clusters, risk);

            // 3. Build Interactive Chat Message
            class_5250 header = class_2561.method_43470("\n§6§l[IPCopy] §a✔ Анализ сессий игрока §e" + nick + " §aзавершен!\n");
            class_5250 stats = class_2561.method_43470("§7Сессий: §f" + totalSessions + " §7| Подсетей /24: §f" + clusters.size() + " §7| Риск: " + getRiskFormatted(risk) + "\n");

            // Button 1: Copy All Raw IPs
            class_5250 copyAllBtn = class_2561.method_43470("§e[📋 СКОПИРОВАТЬ ВСЕ IP]")
                .method_10862(class_2583.field_24360
                    .method_10977(class_124.field_1068)
                    .method_10958(new class_2558.class_10606(rawIpsList))
                    .method_10949(new class_2568.class_10613(class_2561.method_43470("§7Скопировать все " + allIps.size() + " уникальных IP через запятую"))));

            // Button 2: Copy Discord Markdown Report
            class_5250 copyDiscordBtn = class_2561.method_43470(" §b[📄 СКОПИРОВАТЬ ОТЧЕТ (DISCORD)]\n")
                .method_10862(class_2583.field_24360
                    .method_10977(class_124.field_1060)
                    .method_10958(new class_2558.class_10606(fullMarkdown))
                    .method_10949(new class_2568.class_10613(class_2561.method_43470("§7Скопировать структурированный Markdown-отчет для Discord"))));

            class_5250 combined = header.method_10852(stats).method_10852(copyAllBtn).method_10852(copyDiscordBtn);

            if (client.field_1724 != null) {
                client.field_1724.method_7353(combined, false);
            }
        });
    }

    private static String getRiskFormatted(int risk) {
        if (risk >= 70) {
            return "§c§l" + risk + "% [DATACENTER / VPN / PROXY]";
        } else if (risk >= 26) {
            return "§e§l" + risk + "% [RESIDENTIAL / DYNAMIC]";
        } else {
            return "§a§l" + risk + "% [CLEAN]";
        }
    }
}
