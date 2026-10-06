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
import ru.mqclass.ipcopy.config.IpCopyConfig;
import ru.mqclass.ipcopy.forensics.IpForensicEngine;
import ru.mqclass.ipcopy.network.CommandDispatcher;
import ru.mqclass.ipcopy.risk.AsnRiskLookupService;
import ru.mqclass.ipcopy.scanner.ScanQueueManager;

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
 * Deterministic Sequential Session Scraper Finite State Machine (FSM) & Auto-Pager.
 * Intercepts incoming Minecraft chat messages, detects login history multi-page tables,
 * sequences background page requests with guaranteed anti-spam rate limiting,
 * suppresses raw chat spam, and passes captured session pools to the Forensic Engine.
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

    // Regex for Header: "Входы игрока [head]nick (28/28)" or "Входы игрока Y3BEKasdss (24/24)"
    private static final Pattern HEADER_PATTERN = Pattern.compile(
        "(?i)(?:Входы|Авторизации|История входов|Logins of)\\s+(?:игрока\\s+)?[^\\(\\r\\n]*\\((\\d+)\\s*[/из]\\s*(\\d+)\\)"
    );

    // Regex for Page Footer: "Страница (1/5)" or "Page 1 of 5"
    private static final Pattern FOOTER_PAGE_PATTERN = Pattern.compile(
        "(?i)(?:Страница|Page)\\s*\\(?(\\d+)\\s*[/из]\\s*(\\d+)\\)?"
    );

    // Regex for player name in header
    private static final Pattern NICK_PATTERN = Pattern.compile(
        "(?i)(?:Входы|Авторизации|История входов|Logins of)\\s+(?:игрока\\s+)?[^A-Za-z0-9_\\r\\n]*(?:\\[[^\\]]*\\]\\s*)?([A-Za-z0-9_]{1,16})"
    );

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "IPCopy-SessionFSM");
        t.setDaemon(true);
        return t;
    });

    private volatile State state = State.IDLE;
    private volatile String currentTargetNick = "";
    private volatile int totalExpectedPages = 1;
    private volatile int currentScrapedPage = 1;
    private volatile int totalIngestedSessions = 0;
    private volatile boolean scanExplicitlyRequested = false;
    private volatile boolean isRequestInFlight = false;
    private volatile int lastFinishedPage = -1;

    private final Set<Integer> ingestedPages = ConcurrentHashMap.newKeySet();
    private final Set<Integer> attemptedPages = ConcurrentHashMap.newKeySet();
    private final Set<String> capturedRawIps = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<String, IpForensicEngine.SubnetCluster> analyzedClusters = new ConcurrentHashMap<>();
    private volatile int overallRiskScore = 0;

    private ScheduledFuture<?> watchdogFuture = null;

    private SessionCaptureFSM() {}

    public static SessionCaptureFSM getInstance() {
        return INSTANCE;
    }

    public State getState() {
        return state;
    }

    public boolean isScraping() {
        return state == State.SESSION_START || state == State.SCRAPING_PAGES || state == State.PROCESSING;
    }

    public String getCurrentTargetNick() {
        return currentTargetNick;
    }

    public int getTotalExpectedPages() {
        return totalExpectedPages;
    }

    public int getCurrentScrapedPage() {
        return currentScrapedPage;
    }

    public int getIngestedPagesCount() {
        return ingestedPages.size();
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
     * Flags next login history output for the given nick to be automatically scraped and suppressed from chat.
     */
    public synchronized void prepareScanForNick(String nick) {
        if (nick == null || nick.isBlank()) return;
        this.scanExplicitlyRequested = true;
        this.currentTargetNick = nick.trim();
    }

    /**
     * Manually starts or restarts scraping session for a target player (e.g. from GUI button after initial page is loaded).
     */
    public synchronized void startScraping(String nick, int startPage, int totalPages) {
        if (nick == null || nick.isBlank()) return;
        String cleanNick = nick.trim();
        if (isScraping() && cleanNick.equalsIgnoreCase(this.currentTargetNick)) {
            return;
        }
        cancelInternal(false);

        this.currentTargetNick = cleanNick;
        this.totalExpectedPages = Math.max(1, totalPages);
        this.currentScrapedPage = Math.max(1, startPage);

        ru.mqclass.ipcopy.lookup.IpLookupManager.PlayerLookupData data =
            ru.mqclass.ipcopy.lookup.IpLookupManager.getData(cleanNick);
        if (data != null) {
            this.capturedRawIps.addAll(data.allCollectedIps);
            for (ru.mqclass.ipcopy.lookup.IpLookupManager.PlayerIpEntry entry : data.entries) {
                this.capturedRawIps.add(entry.ip());
            }
            this.ingestedPages.addAll(data.pageSessions.keySet());
            this.attemptedPages.addAll(data.pageSessions.keySet());
            this.totalIngestedSessions = Math.max(data.allSessions.size(), data.entries.size());
        }

        this.ingestedPages.add(this.currentScrapedPage);
        this.attemptedPages.add(this.currentScrapedPage);
        this.state = State.SCRAPING_PAGES;

        if (this.ingestedPages.size() >= this.totalExpectedPages) {
            triggerForensicAnalysis();
        } else {
            scheduleNextPageStep(100L);
        }
    }

    /**
     * Resets FSM state and cancels active scraping sessions.
     */
    public synchronized void cancel() {
        cancelInternal(true);
    }

    public synchronized void cancelHard() {
        cancelInternal(false);
    }

    private synchronized void cancelInternal(boolean finalizeIfHasData) {
        if (watchdogFuture != null) {
            watchdogFuture.cancel(true);
            watchdogFuture = null;
        }

        if (finalizeIfHasData && isScraping() && !capturedRawIps.isEmpty()) {
            triggerForensicAnalysis();
            return;
        }

        state = State.IDLE;
        currentTargetNick = "";
        totalExpectedPages = 1;
        currentScrapedPage = 1;
        totalIngestedSessions = 0;
        scanExplicitlyRequested = false;
        isRequestInFlight = false;
        lastFinishedPage = -1;
        ingestedPages.clear();
        attemptedPages.clear();
        capturedRawIps.clear();
        analyzedClusters.clear();
        overallRiskScore = 0;
    }

    /**
     * Inspects inbound chat message (which may be a single multi-line packet containing header, entries, and footer,
     * or individual lines). Returns true if the message belongs to active background scraping and should be suppressed.
     */
    public boolean handleInboundMessage(String rawText, class_2561 message) {
        if (rawText == null || rawText.isBlank()) return false;
        String clean = IpForensicEngine.cleanText(rawText);
        boolean matchedHeaderInThisPacket = false;
        boolean startedNewScrapeOnThisPacket = false;

        // 1. Detect Login History Header (DO NOT return early so multi-line packets are fully parsed!)
        Matcher headerMatcher = HEADER_PATTERN.matcher(clean);
        if (headerMatcher.find()) {
            Matcher nickMatcher = NICK_PATTERN.matcher(clean);
            String nick = nickMatcher.find() ? nickMatcher.group(1) : this.currentTargetNick;

            try {
                int cur = Integer.parseInt(headerMatcher.group(1));
                int total = Integer.parseInt(headerMatcher.group(2));

                if (isScraping() && this.currentTargetNick.equalsIgnoreCase(nick)) {
                    // Active continuation of ongoing scrape
                    this.currentScrapedPage = cur;
                    this.totalExpectedPages = Math.max(this.totalExpectedPages, total);
                    this.attemptedPages.add(cur);
                    this.isRequestInFlight = false;
                    restartWatchdog(6000L);
                    matchedHeaderInThisPacket = true;
                } else if (state == State.IDLE || !this.currentTargetNick.equalsIgnoreCase(nick)) {
                    boolean explicitForThisNick = scanExplicitlyRequested
                        && (this.currentTargetNick.isEmpty() || this.currentTargetNick.equalsIgnoreCase(nick));
                    boolean shouldAutoScrape = explicitForThisNick
                        || IpCopyConfig.getInstance().autoFetchAllPages
                        || ru.mqclass.ipcopy.lookup.IpLookupManager.isExpressAuditRunning();

                    ru.mqclass.ipcopy.lookup.IpLookupManager.PlayerLookupData data =
                        ru.mqclass.ipcopy.lookup.IpLookupManager.getData(nick);
                    int alreadyCollectedPages = data != null ? data.getCollectedPagesCount() : 0;

                    if (shouldAutoScrape && (total > 1 && alreadyCollectedPages < total || explicitForThisNick)) {
                        this.scanExplicitlyRequested = false;
                        startScrapingSession(nick, cur, total);
                        matchedHeaderInThisPacket = true;
                        startedNewScrapeOnThisPacket = true;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. If actively scraping, collect session entries and check page completion within the same packet
        if (isScraping()) {
            restartWatchdog(6000L);
            boolean matchedContent = matchedHeaderInThisPacket;

            // Extract IPv4 addresses line-by-line to accurately count sessions and unique IPs
            String[] lines = clean.split("\n");
            int sessionLinesInPacket = 0;
            List<String> packetIps = new ArrayList<>();
            for (String line : lines) {
                // Ignore any appended button suffix when extracting IPs from a line
                int btnIdx = line.indexOf("[Скоп. IP");
                String lineCore = btnIdx >= 0 ? line.substring(0, btnIdx) : line;
                List<String> lineIps = IpForensicEngine.extractUniqueIps(lineCore);
                if (!lineIps.isEmpty()) {
                    sessionLinesInPacket++;
                    packetIps.addAll(lineIps);
                }
            }

            if (!packetIps.isEmpty()) {
                capturedRawIps.addAll(packetIps);
                if (! this.ingestedPages.contains(this.currentScrapedPage)) {
                    totalIngestedSessions += sessionLinesInPacket;
                }
                matchedContent = true;
            }

            // Check footer pagination ("Страница X/Y" or SpaceTimes navigation bar)
            boolean footerDetected = false;
            int finishedPageNum = this.currentScrapedPage;
            int finishedTotalPages = this.totalExpectedPages;

            Matcher footerMatcher = FOOTER_PAGE_PATTERN.matcher(clean);
            if (footerMatcher.find()) {
                try {
                    finishedPageNum = Integer.parseInt(footerMatcher.group(1));
                    finishedTotalPages = Integer.parseInt(footerMatcher.group(2));
                    footerDetected = true;
                } catch (NumberFormatException ignored) {}
            } else if ((clean.contains("Начало") && clean.contains("Назад"))
                    || (clean.contains("Вперёд") || clean.contains("Вперед"))
                    || clean.contains("┗━━━━━")) {
                footerDetected = true;
            }

            if (footerDetected) {
                onPageFinished(finishedPageNum, finishedTotalPages);
                matchedContent = true;
            } else if (clean.startsWith("┏━━━━━") || clean.startsWith("┣") || clean.startsWith("┗━━━━━")) {
                matchedContent = true;
            }

            if (matchedContent) {
                // Show a brief 1-line notification when background multi-page scraping begins
                if (startedNewScrapeOnThisPacket && this.totalExpectedPages > 1) {
                    notifyScrapeStarted(this.currentTargetNick, this.totalExpectedPages);
                }
                return true; // Suppress raw page output from chat
            }
        }

        return false;
    }

    private void notifyScrapeStarted(String nick, int totalPages) {
        class_310 client = class_310.method_1551();
        if (client != null) {
            client.execute(() -> {
                if (client.field_1724 != null) {
                    client.field_1724.method_7353(
                        class_2561.method_43470("§6[IPCopy] §e⏳ Авто-сбор всех страниц входов §f" + nick + " §7(всего страниц: §e" + totalPages + "§7)..."),
                        true
                    );
                }
            });
        }
    }

    private synchronized void startScrapingSession(String nick, int currentPage, int totalPages) {
        cancelInternal(false);
        this.currentTargetNick = nick != null && !nick.isBlank() ? nick.trim() : "Target";
        this.totalExpectedPages = Math.max(1, totalPages);
        this.currentScrapedPage = Math.max(1, currentPage);

        // Pre-populate any other pages already stored in IpLookupManager (excluding currentPage which is being parsed right now)
        ru.mqclass.ipcopy.lookup.IpLookupManager.PlayerLookupData data =
            ru.mqclass.ipcopy.lookup.IpLookupManager.getData(this.currentTargetNick);
        if (data != null) {
            this.capturedRawIps.addAll(data.allCollectedIps);
            for (Integer p : data.pageSessions.keySet()) {
                if (p != this.currentScrapedPage) {
                    this.ingestedPages.add(p);
                    this.attemptedPages.add(p);
                    List<ru.mqclass.ipcopy.lookup.IpLookupManager.PlayerIpEntry> list = data.pageSessions.get(p);
                    if (list != null) {
                        this.totalIngestedSessions += list.size();
                    }
                }
            }
        }

        // Note: Do NOT add currentScrapedPage to ingestedPages here; onPageFinished() will add it once the page footer is processed!
        this.attemptedPages.add(this.currentScrapedPage);
        this.state = State.SCRAPING_PAGES;
        this.isRequestInFlight = false;
        this.lastFinishedPage = -1;

        restartWatchdog(6000L);
    }

    private synchronized void onPageFinished(int curPage, int totalPages) {
        if (!isScraping() || curPage <= 0) return;

        // Guard against duplicate finishing for the same page
        if (this.ingestedPages.contains(curPage)) {
            return;
        }

        this.ingestedPages.add(curPage);
        this.attemptedPages.add(curPage);
        if (totalPages > 0) {
            this.totalExpectedPages = Math.max(this.totalExpectedPages, totalPages);
        }

        // Sync collected IPs into lookup data so GUI gets populated immediately
        ru.mqclass.ipcopy.lookup.IpLookupManager.PlayerLookupData data =
            ru.mqclass.ipcopy.lookup.IpLookupManager.getData(this.currentTargetNick);
        if (data != null) {
            data.allCollectedIps.addAll(this.capturedRawIps);
            this.capturedRawIps.addAll(data.allCollectedIps);
            if (!data.allSessions.isEmpty()) {
                this.totalIngestedSessions = Math.max(this.totalIngestedSessions, data.allSessions.size());
            }
        }
        ru.mqclass.ipcopy.lookup.IpLookupManager.onAutoCrawlPageUpdate(this.currentTargetNick);

        this.isRequestInFlight = false;

        if (this.ingestedPages.size() >= this.totalExpectedPages) {
            // All pages captured!
            triggerForensicAnalysis();
        } else {
            // Schedule fetching the next unvisited page sequentially
            scheduleNextPageStep(150L);
        }
    }

    private synchronized void scheduleNextPageStep(long delayMs) {
        if (!isScraping()) return;
        if (isRequestInFlight) return; // Strict single in-flight lock

        // Iterate from totalExpectedPages down to 1 (SpaceTimes opens the last page N/N first)
        int nextTarget = -1;
        for (int p = totalExpectedPages; p >= 1; p--) {
            if (!ingestedPages.contains(p) && !attemptedPages.contains(p)) {
                nextTarget = p;
                break;
            }
        }

        if (nextTarget == -1) {
            // If all pages were ingested or attempted, finish!
            triggerForensicAnalysis();
            return;
        }

        final int pageToFetch = nextTarget;
        attemptedPages.add(pageToFetch);
        currentScrapedPage = pageToFetch;
        isRequestInFlight = true;

        scheduler.schedule(() -> {
            synchronized (this) {
                if (!isScraping()) {
                    isRequestInFlight = false;
                    return;
                }
            }

            // Dispatch command strictly through rate-limited CommandDispatcher
            CommandDispatcher.dispatch("auth find login by player " + currentTargetNick + " " + pageToFetch);

            // Arm watchdog timer in case server drops packet or throttles
            restartWatchdog(6000L);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void restartWatchdog(long timeoutMs) {
        if (watchdogFuture != null) {
            watchdogFuture.cancel(false);
        }
        watchdogFuture = scheduler.schedule(() -> {
            synchronized (this) {
                if (isScraping()) {
                    isRequestInFlight = false;
                    if (ingestedPages.size() >= totalExpectedPages || attemptedPages.size() >= totalExpectedPages) {
                        triggerForensicAnalysis();
                    } else {
                        scheduleNextPageStep(100L);
                    }
                }
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Executes async forensic clustering and ASN/VPN risk scoring.
     */
    private synchronized void triggerForensicAnalysis() {
        if (state == State.PROCESSING || state == State.COMPLETED) {
            return;
        }

        final String nick = this.currentTargetNick;
        ru.mqclass.ipcopy.lookup.IpLookupManager.PlayerLookupData data =
            ru.mqclass.ipcopy.lookup.IpLookupManager.getData(nick);
        if (data != null) {
            this.capturedRawIps.addAll(data.allCollectedIps);
            for (String ip : data.getUniqueIps()) {
                this.capturedRawIps.add(ip);
            }
            if (!data.allSessions.isEmpty()) {
                this.totalIngestedSessions = Math.max(this.totalIngestedSessions, data.allSessions.size());
            }
        }

        if (capturedRawIps.isEmpty()) {
            state = State.IDLE;
            ru.mqclass.ipcopy.lookup.IpLookupManager.onAutoCrawlFinished(nick);
            return;
        }

        state = State.PROCESSING;
        if (watchdogFuture != null) {
            watchdogFuture.cancel(false);
            watchdogFuture = null;
        }

        // Preserve order from IpLookupManager.getUniqueIps(nick) if available
        final Set<String> ips = new LinkedHashSet<>();
        if (data != null) {
            ips.addAll(data.getUniqueIps());
        }
        ips.addAll(capturedRawIps);
        final int totalSessions = Math.max(this.totalIngestedSessions, ips.size());

        // Perform async ASN and risk scoring lookups via Virtual Threads
        AsnRiskLookupService.getInstance().lookupAllSubnetsAsync(ips).whenComplete((riskMap, throwable) -> {
            try {
                if (throwable != null || riskMap == null) {
                    riskMap = Collections.emptyMap();
                }

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

                // Notify IpLookupManager that multi-page scraping is complete
                ru.mqclass.ipcopy.lookup.IpLookupManager.onAutoCrawlFinished(nick);

                // Notify player and play finish sound
                postCompletionSummary(nick, totalSessions, enrichedClusters.values(), overallRiskScore, ips);
            } catch (Throwable t) {
                System.err.println("[IPCopy] Error finalizing forensics: " + t.getMessage());
                this.state = State.IDLE;
                ru.mqclass.ipcopy.lookup.IpLookupManager.onAutoCrawlFinished(nick);
            }
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

            // 2. Prepare payloads (comma-separated vertical column: 1.1.1.1,\n2.2.2.2)
            String rawIpsList = ru.mqclass.ipcopy.IpCopyProcessor.formatIpsColumn(allIps);
            String fullMarkdown = IpForensicEngine.buildDiscordReport(nick, totalSessions, clusters, risk);

            // Auto-copy unique IPs in column format to clipboard immediately upon completion
            if (!rawIpsList.isEmpty() && !ru.mqclass.ipcopy.lookup.IpLookupManager.isExpressAuditRunning()) {
                ru.mqclass.ipcopy.lookup.IpLookupManager.copyToClipboardInternal(rawIpsList);
            }

            // 3. Build Interactive Chat Message
            class_5250 header = class_2561.method_43470(
                "\n§6§l[IPCopy] §a✔ Сбор всех страниц игрока §e" + nick + " §aзавершён! §7(Уник. IP скопированы в буфер)\n"
            );
            class_5250 stats = class_2561.method_43470(
                "§7Сессий: §f" + totalSessions +
                " §7| Уникальных IP: §e" + allIps.size() +
                " §7| Подсетей /24: §b" + clusters.size() +
                " §7| Риск: " + getRiskFormatted(risk) + "\n"
            );

            // Button 1: Copy All Unique IPs in column format with commas
            class_5250 copyAllBtn = class_2561.method_43470("§e[📋 СКОПИРОВАТЬ УНИК. IP (" + allIps.size() + ")]")
                .method_10862(class_2583.field_24360
                    .method_10977(class_124.field_1054)
                    .method_10958(new class_2558.class_10606(rawIpsList))
                    .method_10949(new class_2568.class_10613(class_2561.method_43470(
                        "§eСкопировать все " + allIps.size() + " уникальных IP в столбик через запятую:\n§a" + rawIpsList
                    ))));

            // Button 2: Batch Dupe Check
            class_5250 dupeCheckBtn = class_2561.method_43470(" §6[⚡ ПРОВЕРИТЬ ТВИНКИ]")
                .method_10862(class_2583.field_24360
                    .method_10977(class_124.field_1065)
                    .method_10958(new class_2558.class_10609("/ipcopy scan " + nick))
                    .method_10949(new class_2568.class_10613(class_2561.method_43470("§7Запустить пакетную проверку твинков только по уникальным подсетям"))));

            // Button 3: Open GUI
            class_5250 openGuiBtn = class_2561.method_43470(" §a[🔍 В GUI]")
                .method_10862(class_2583.field_24360
                    .method_10977(class_124.field_1060)
                    .method_10958(new class_2558.class_10609("/ipcopy gui " + nick))
                    .method_10949(new class_2568.class_10613(class_2561.method_43470("§7Открыть полное досье игрока в интерфейсе"))));

            // Button 4: Copy Discord Report
            class_5250 copyDiscordBtn = class_2561.method_43470(" §b[📄 DISCORD]\n")
                .method_10862(class_2583.field_24360
                    .method_10977(class_124.field_1061)
                    .method_10958(new class_2558.class_10606(fullMarkdown))
                    .method_10949(new class_2568.class_10613(class_2561.method_43470("§7Скопировать полный структурированный отчет для Discord"))));

            class_5250 combined = header.method_10852(stats)
                .method_10852(copyAllBtn)
                .method_10852(dupeCheckBtn)
                .method_10852(openGuiBtn)
                .method_10852(copyDiscordBtn);

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
