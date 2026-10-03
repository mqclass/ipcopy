package ru.mqclass.ipcopy.gui;

import net.minecraft.class_11908;
import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_332;
import net.minecraft.class_342;
import net.minecraft.class_4185;
import net.minecraft.class_437;
import net.minecraft.class_7919;
import ru.mqclass.ipcopy.IpCopyClient;
import ru.mqclass.ipcopy.IpCopyProcessor;
import ru.mqclass.ipcopy.config.IpCopyConfig;
import ru.mqclass.ipcopy.feedback.IpFeedback;
import ru.mqclass.ipcopy.history.IpHistoryManager;
import ru.mqclass.ipcopy.lookup.IpLookupManager;
import ru.mqclass.ipcopy.lookup.IpLookupManager.PlayerIpEntry;
import ru.mqclass.ipcopy.lookup.SubnetMatcher;
import ru.mqclass.ipcopy.report.PlayerReportExporter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Interactive moderation dashboard for IP Copy.
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public class IpCopyScreen extends class_437 {

    private enum Tab {
        LOOKUP,
        HISTORY,
        SETTINGS
    }

    public enum LookupViewMode {
        UNIQUE_IPS,
        ALL_SESSIONS
    }

    private static final int ROWS_PER_PAGE = 5;
    private static final long QUERY_TIMEOUT_MS = 5000L;

    private final class_437 parent;
    private Tab activeTab = Tab.LOOKUP;
    private LookupViewMode lookupViewMode = IpCopyConfig.getInstance().preferUniqueIpsView ? LookupViewMode.UNIQUE_IPS : LookupViewMode.ALL_SESSIONS;
    private class_342 nickField;
    private class_342 historyFilterField;
    private static volatile String lastQueriedNick = "";
    private String currentNick = "";
    private String displayedNick = "";
    private String historyFilter = "";

    // Pagination state
    private int currentPage = 0;

    // Async request state tracking
    private boolean queryPending = false;
    private long queryStartTime = 0L;
    private boolean queryTimedOut = false;

    // Server page navigation state & anti-spam cooldown (1.3s)
    private static final long SERVER_NAV_COOLDOWN_MS = 1300L;
    private long lastServerNavTime = 0L;
    private boolean isNavigatingServerPage = false;
    private long serverNavStartTime = 0L;

    // Server page navigation buttons for dynamic real-time cooldown updates
    private class_4185 btnServerFirst;
    private class_4185 btnServerPrev;
    private class_4185 btnServerNext;
    private class_4185 btnServerLast;

    private boolean canSendServerNavCommand() {
        return !this.isNavigatingServerPage && (System.currentTimeMillis() - this.lastServerNavTime >= SERVER_NAV_COOLDOWN_MS);
    }

    private void executeServerNavCommand(String cmd) {
        if (cmd == null || cmd.isEmpty() || !canSendServerNavCommand()) return;

        this.lastServerNavTime = System.currentTimeMillis();
        this.isNavigatingServerPage = true;
        this.serverNavStartTime = this.lastServerNavTime;

        // Immediately disable buttons for visual feedback without full screen rebuild
        if (this.btnServerFirst != null) this.btnServerFirst.field_22763 = false;
        if (this.btnServerPrev != null) this.btnServerPrev.field_22763 = false;
        if (this.btnServerNext != null) this.btnServerNext.field_22763 = false;
        if (this.btnServerLast != null) this.btnServerLast.field_22763 = false;

        IpLookupManager.executeServerCommand(cmd);
    }

    private String getServerNavFirstCmd(IpLookupManager.PlayerLookupData data) {
        if (data == null) return null;
        if (data.cmdFirst != null) return data.cmdFirst;
        return !this.displayedNick.isEmpty() ? ("auth find login by player " + this.displayedNick + " 1") : null;
    }

    private String getServerNavPrevCmd(IpLookupManager.PlayerLookupData data) {
        if (data == null) return null;
        if (data.cmdPrev != null) return data.cmdPrev;
        return !this.displayedNick.isEmpty() ? ("auth find login by player " + this.displayedNick + " " + (data.serverCurrentPage - 1)) : null;
    }

    private String getServerNavNextCmd(IpLookupManager.PlayerLookupData data) {
        if (data == null) return null;
        if (data.cmdNext != null) return data.cmdNext;
        return !this.displayedNick.isEmpty() ? ("auth find login by player " + this.displayedNick + " " + (data.serverCurrentPage + 1)) : null;
    }

    private String getServerNavLastCmd(IpLookupManager.PlayerLookupData data) {
        if (data == null) return null;
        if (data.cmdLast != null) return data.cmdLast;
        return !this.displayedNick.isEmpty() ? ("auth find login by player " + this.displayedNick + " " + data.serverTotalPages) : null;
    }

    public static String sanitizeNick(String raw) {
        if (raw == null) return "";
        String s = IpLookupManager.stripColorCodes(raw).trim().replaceAll("[^A-Za-z0-9_]", "");
        return s.length() > 16 ? s.substring(0, 16) : s;
    }

    public IpCopyScreen(class_437 parent) {
        this(parent, null);
    }

    public IpCopyScreen(class_437 parent, String initialNick) {
        super(class_2561.method_43470("IP Copy — Панель модератора"));
        this.parent = parent;
        if (initialNick != null && !initialNick.trim().isEmpty()) {
            this.currentNick = sanitizeNick(initialNick);
            lastQueriedNick = this.currentNick;
        } else if (!lastQueriedNick.isEmpty()) {
            this.currentNick = lastQueriedNick;
        } else {
            this.currentNick = "";
        }

        if (!this.currentNick.isEmpty()) {
            this.displayedNick = this.currentNick;
        } else {
            this.displayedNick = "";
        }
    }

    @Override
    protected void method_25426() {
        super.method_25426();

        // Register reactive update listener on UI init/resize
        IpLookupManager.setUpdateListener(nick -> {
            if (nick != null && (nick.equalsIgnoreCase(this.currentNick) || nick.equalsIgnoreCase(this.displayedNick))) {
                this.queryPending = false;
                this.queryTimedOut = false;
                this.isNavigatingServerPage = false;
                this.displayedNick = nick;
                lastQueriedNick = nick;
                class_310 client = class_310.method_1551();
                if (client != null) {
                    client.execute(this::rebuildWidgets);
                }
            }
        });

        setupWidgets();
    }

    private void rebuildWidgets() {
        this.btnServerFirst = null;
        this.btnServerPrev = null;
        this.btnServerNext = null;
        this.btnServerLast = null;
        this.method_37067();
        setupWidgets();
    }

    private void setupWidgets() {
        int centerX = this.field_22789 / 2;

        // Navigation top tabs (3 tabs x 105px + 4px gap = 323px)
        int tabWidth = 105;
        int tabHeight = 18;
        int tabY = 8;
        int totalTabsWidth = tabWidth * 3 + 8;
        int startTabX = centerX - (totalTabsWidth / 2);

        int historyCount = IpHistoryManager.size();
        String lookupTitle = (this.activeTab == Tab.LOOKUP ? "§6§l🔍 Поиск по нику" : "§7🔍 Поиск по нику");
        String historyTitle = (this.activeTab == Tab.HISTORY ? "§6§l📋 История (" + historyCount + ")" : "§7📋 История (" + historyCount + ")");
        String settingsTitle = (this.activeTab == Tab.SETTINGS ? "§6§l⚙ Настройки" : "§7⚙ Настройки");

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(lookupTitle),
            button -> {
                this.activeTab = Tab.LOOKUP;
                this.currentPage = 0;
                this.rebuildWidgets();
            }
        ).method_46434(startTabX, tabY, tabWidth, tabHeight).method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(historyTitle),
            button -> {
                this.activeTab = Tab.HISTORY;
                this.currentPage = 0;
                this.rebuildWidgets();
            }
        ).method_46434(startTabX + tabWidth + 4, tabY, tabWidth, tabHeight).method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(settingsTitle),
            button -> {
                this.activeTab = Tab.SETTINGS;
                this.rebuildWidgets();
            }
        ).method_46434(startTabX + (tabWidth + 4) * 2, tabY, tabWidth, tabHeight).method_46431());

        this.nickField = null;
        this.historyFilterField = null;

        if (this.activeTab == Tab.LOOKUP) {
            setupLookupTab(centerX);
        } else if (this.activeTab == Tab.HISTORY) {
            setupHistoryTab(centerX);
        } else {
            setupSettingsTab(centerX);
        }
    }

    private void setupLookupTab(int centerX) {
        int inputY = 30;

        // Nickname text field (width: 142)
        this.nickField = new class_342(this.field_22793, centerX - 188, inputY, 142, 20, class_2561.method_43470("Ник игрока..."));
        this.nickField.method_1880(16);
        this.nickField.method_1852(this.currentNick);
        this.nickField.method_1863(text -> {
            String trimmed = text.trim();
            this.currentNick = trimmed;
            if (!trimmed.isEmpty()) {
                lastQueriedNick = trimmed;
            }
            this.currentPage = 0;
            this.queryTimedOut = false;
            if (trimmed.isEmpty() && !this.displayedNick.isEmpty()) {
                this.displayedNick = "";
                this.rebuildWidgets();
                if (this.nickField != null) {
                    this.nickField.method_1852("");
                    this.nickField.method_25365(true);
                    this.method_25395(this.nickField);
                }
            }
        });
        this.nickField.method_1890(str -> str.length() <= 32);
        this.method_37063(this.nickField);
        this.method_25395(this.nickField);
        this.nickField.method_25365(true);

        // [📋] Paste button
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§e📋"),
            button -> pasteFromClipboard()
        ).method_46434(centerX - 43, inputY, 19, 20)
         .method_46436(class_7919.method_47407(class_2561.method_43470("§eВставить ник из буфера обмена\n§7Горячая клавиша: §fCtrl+V")))
         .method_46431());

        // [✖] Clear search button
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§c✖"),
            button -> {
                this.currentNick = "";
                this.displayedNick = "";
                this.currentPage = 0;
                this.queryPending = false;
                this.queryTimedOut = false;
                this.rebuildWidgets();
                if (this.nickField != null) {
                    this.nickField.method_1852("");
                    this.nickField.method_25365(true);
                    this.method_25395(this.nickField);
                }
            }
        ).method_46434(centerX - 21, inputY, 19, 20)
         .method_46436(class_7919.method_47407(class_2561.method_43470("§cОчистить поле поиска и результаты")))
         .method_46431());

        IpLookupManager.PlayerLookupData lookupData = (!this.displayedNick.isEmpty()) ? IpLookupManager.getData(this.displayedNick) : null;
        boolean isFetching = this.queryPending || (lookupData != null && (lookupData.status == IpLookupManager.LookupStatus.WAITING_INFO || lookupData.status == IpLookupManager.LookupStatus.FETCHING_HISTORY));

        // Search button with tooltip & pending state
        class_4185 searchBtn = class_4185.method_46430(
            class_2561.method_43470(isFetching ? "§7⏳ Поиск..." : "§e🔍 Запрос"),
            button -> triggerPlayerQuery()
        ).method_46434(centerX + 2, inputY, 76, 20)
         .method_46436(class_7919.method_47407(class_2561.method_43470("§eЗапросить историю сессий\n§7Поиск сессий игрока: §f" + (!this.currentNick.isEmpty() ? this.currentNick : "...") + "\n§8(Клавиша Enter в поле)")))
         .method_46431();
        searchBtn.field_22763 = !isFetching;
        this.method_37063(searchBtn);

        // Quick test Odinoky button
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§aТест"),
            button -> {
                this.currentNick = "Odinoky";
                this.displayedNick = "Odinoky";
                lastQueriedNick = "Odinoky";
                this.currentPage = 0;
                this.queryPending = false;
                this.queryTimedOut = false;
                if (this.nickField != null) {
                    this.nickField.method_1852("Odinoky");
                }
                this.rebuildWidgets();
            }
        ).method_46434(centerX + 82, inputY, 48, 20)
         .method_46436(class_7919.method_47407(class_2561.method_43470("§aЗагрузить тестовый профиль Odinoky\n§7Демонстрация профиля (UUID, VK, TG) и 6 сессий с подсетями /24")))
         .method_46431());

        // [⚡ Скан] Batch scan button right in search bar
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§d⚡ Скан"),
            button -> {
                if (lookupData != null && !lookupData.getUniqueIps().isEmpty()) {
                    ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().startScan(
                        this.displayedNick, lookupData.getUniqueIps(), IpCopyConfig.getInstance().secondActionCommand
                    );
                } else if (!this.currentNick.isEmpty()) {
                    IpCopyClient.startScanCommand(this.currentNick);
                }
            }
        ).method_46434(centerX + 134, inputY, 54, 20)
         .method_46436(class_7919.method_47407(class_2561.method_43470("§d[IPCopy AutoScan] §eЗапустить авто-сканирование всех уникальных IP!\n§7Проверяет адреса с кулдауном 1250ms, скрывает спам и выводит итоговый отчёт")))
         .method_46431());

        // Automation and View Switcher Toolbar (if player has lookup data)
        if (lookupData != null && lookupData.status == IpLookupManager.LookupStatus.FOUND) {
            int toolbarY = 54;
            List<IpLookupManager.UniqueIpGroup> uniqueGroups = IpLookupManager.getUniqueGroups(this.displayedNick);
            List<PlayerIpEntry> allSessionsList = IpLookupManager.getAllSessions(this.displayedNick);
            int uniqueCount = uniqueGroups.size();
            int totalSessionsCount = !allSessionsList.isEmpty() ? allSessionsList.size() : lookupData.entries.size();

            // Toggle 1: Unique IPs view
            String uniqueTitle = (this.lookupViewMode == LookupViewMode.UNIQUE_IPS ? "§6§l⭐ Уник. (" + uniqueCount + ")" : "§7⭐ Уник. (" + uniqueCount + ")");
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470(uniqueTitle),
                btn -> {
                    this.lookupViewMode = LookupViewMode.UNIQUE_IPS;
                    this.currentPage = 0;
                    this.rebuildWidgets();
                }
            ).method_46434(centerX - 188, toolbarY, 92, 17)
             .method_46436(class_7919.method_47407(class_2561.method_43470("§eРежим сгруппированных уникальных IP\n§7Объединяет одинаковые IP в строки с подсчётом сессий и твинками")))
             .method_46431());

            // Toggle 2: All sessions view
            String sessionsTitle = (this.lookupViewMode == LookupViewMode.ALL_SESSIONS ? "§6§l📋 Сессии (" + totalSessionsCount + ")" : "§7📋 Сессии (" + totalSessionsCount + ")");
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470(sessionsTitle),
                btn -> {
                    this.lookupViewMode = LookupViewMode.ALL_SESSIONS;
                    this.currentPage = 0;
                    this.rebuildWidgets();
                }
            ).method_46434(centerX - 93, toolbarY, 88, 17)
             .method_46436(class_7919.method_47407(class_2561.method_43470("§eРежим всех сессий\n§7Показывает каждую сессию по отдельности с точным временем")))
             .method_46431());

            // Auto-Crawler button
            if (lookupData.serverTotalPages > 1) {
                if (IpLookupManager.isAutoCrawling()) {
                    this.method_37063(class_4185.method_46430(
                        class_2561.method_43470("§e⏳ " + IpLookupManager.getAutoCrawlCurrentPage() + "/" + lookupData.serverTotalPages),
                        btn -> {}
                    ).method_46434(centerX - 2, toolbarY, 76, 17)
                     .method_46436(class_7919.method_47407(class_2561.method_43470("§eАвто-сбор страниц в процессе...\n§7Безопасная задержка 1.35с между командами")))
                     .method_46431());

                    this.method_37063(class_4185.method_46430(
                        class_2561.method_43470("§c⏹"),
                        btn -> IpLookupManager.stopAutoCrawl(false)
                    ).method_46434(centerX + 76, toolbarY, 18, 17)
                     .method_46436(class_7919.method_47407(class_2561.method_43470("§cОстановить авто-сбор страниц")))
                     .method_46431());
                } else {
                    this.method_37063(class_4185.method_46430(
                        class_2561.method_43470("§6⚡ Собрать (" + lookupData.serverTotalPages + ")"),
                        btn -> {
                            IpLookupManager.startAutoCrawl(this.displayedNick);
                            this.rebuildWidgets();
                        }
                    ).method_46434(centerX - 2, toolbarY, 96, 17)
                     .method_46436(class_7919.method_47407(class_2561.method_43470("§6Автоматически собрать все " + lookupData.serverTotalPages + " страниц сессий\n§7Скачивает все страницы с безопасной паузой 1.35с без ручных кликов")))
                     .method_46431());
                }
            }

            // Batch Dupe button
            if (IpLookupManager.isBatchDupeRunning()) {
                this.method_37063(class_4185.method_46430(
                    class_2561.method_43470("§b⏳ " + IpLookupManager.getBatchDupeProgress()),
                    btn -> {}
                ).method_46434(centerX + 97, toolbarY, 71, 17)
                 .method_46436(class_7919.method_47407(class_2561.method_43470("§bПроверка твинков DupeIP в процессе...")))
                 .method_46431());

                this.method_37063(class_4185.method_46430(
                    class_2561.method_43470("§c⏹"),
                    btn -> IpLookupManager.stopBatchDupe(false)
                ).method_46434(centerX + 170, toolbarY, 18, 17)
                 .method_46436(class_7919.method_47407(class_2561.method_43470("§cОстановить проверку DupeIP")))
                 .method_46431());
            } else {
                this.method_37063(class_4185.method_46430(
                    class_2561.method_43470("§b⚡ DupeIP (" + uniqueCount + ")"),
                    btn -> {
                        IpLookupManager.startBatchDupe(this.displayedNick);
                        this.rebuildWidgets();
                    }
                ).method_46434(centerX + 97, toolbarY, 91, 17)
                 .method_46436(class_7919.method_47407(class_2561.method_43470("§bАвтоматически проверить все " + uniqueCount + " уникальных IP через /dupeip\n§7Находит твинки и подсвечивает их прямо в таблице!")))
                 .method_46431());
            }
        }

        // [ℹ Инфо] header profile info button
        if (lookupData != null && lookupData.profile != null) {
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§eℹ Инфо"),
                button -> {
                    if (lookupData.profile.uuid() != null && !lookupData.profile.uuid().isEmpty()) {
                        copyToClipboard(lookupData.profile.uuid());
                        button.method_25355(class_2561.method_43470("§aСкопирован"));
                    }
                }
            ).method_46434(centerX + 134, 73, 54, 13)
             .method_46436(class_7919.method_47407(class_2561.method_43470(
                 "§6Профиль игрока: §e" + lookupData.profile.nick() +
                 "\n§7UUID: §f" + lookupData.profile.uuid() +
                 "\n§7Премиум: §f" + lookupData.profile.premium() +
                 "\n§9VK: §b" + lookupData.profile.vk() +
                 "\n§9Telegram: §b" + lookupData.profile.telegram() +
                 "\n§9Discord: §b" + lookupData.profile.discord() +
                 "\n§e(Клик для копирования UUID)"
             )))
             .method_46431());
        }

        List<PlayerIpEntry> entries = (!this.displayedNick.isEmpty()) ? IpLookupManager.getEntries(this.displayedNick) : java.util.Collections.emptyList();
        boolean hasQuickActions = (lookupData != null && lookupData.profile != null && IpCopyConfig.getInstance().showQuickActions);
        if (hasQuickActions && (!entries.isEmpty() || !lookupData.allSessions.isEmpty())) {
            setupQuickActions(centerX, lookupData, entries);
        }

        int startRowY = hasQuickActions ? 108 : 88;
        int rowHeight = 20;

        if (this.lookupViewMode == LookupViewMode.UNIQUE_IPS) {
            List<IpLookupManager.UniqueIpGroup> uniqueGroups = (!this.displayedNick.isEmpty()) ? IpLookupManager.getUniqueGroups(this.displayedNick) : java.util.Collections.emptyList();
            int totalUnique = uniqueGroups.size();
            int maxPages = Math.max(1, (int) Math.ceil((double) totalUnique / ROWS_PER_PAGE));
            if (this.currentPage >= maxPages) this.currentPage = maxPages - 1;

            if (!uniqueGroups.isEmpty()) {
                int startIndex = this.currentPage * ROWS_PER_PAGE;
                int endIndex = Math.min(startIndex + ROWS_PER_PAGE, totalUnique);
                int shownRows = endIndex - startIndex;

                for (int i = startIndex; i < endIndex; i++) {
                    IpLookupManager.UniqueIpGroup group = uniqueGroups.get(i);
                    int rowOffset = i - startIndex;
                    int rowY = startRowY + (rowOffset * rowHeight);

                    // 1. Twinks badge button / status
                    if (group.checked()) {
                        if (!group.twinks().isEmpty()) {
                            class_4185 twinkBtn = class_4185.method_46430(
                                class_2561.method_43470("§c⚠ " + group.twinks().size() + " тв."),
                                btn -> {
                                    String twinksStr = String.join(", ", group.twinks());
                                    copyToClipboard(twinksStr);
                                    IpFeedback.onTwinksCopied(group.ip(), group.twinks().size());
                                    btn.method_25355(class_2561.method_43470("§aСкопированы"));
                                }
                            ).method_46434(centerX - 10, rowY, 70, 18)
                             .method_46436(class_7919.method_47407(class_2561.method_43470(
                                 "§cОбнаружены твинки по IP §e" + group.ip() + "§c:\n§f" + String.join(", ", group.twinks()) + "\n§e(Нажмите для копирования твинков)"
                             )))
                             .method_46431();
                            this.method_37063(twinkBtn);
                        } else {
                            class_4185 cleanBtn = class_4185.method_46430(
                                class_2561.method_43470("§a✔ Чист"),
                                btn -> executeDupeCheck(group.ip())
                            ).method_46434(centerX - 10, rowY, 70, 18)
                             .method_46436(class_7919.method_47407(class_2561.method_43470(
                                 "§aПо IP §e" + group.ip() + " §aтвинков не найдено.\n§7(Нажмите для повторной проверки /dupeip)"
                             )))
                             .method_46431();
                            this.method_37063(cleanBtn);
                        }
                    } else {
                        class_4185 checkBtn = class_4185.method_46430(
                            class_2561.method_43470("§7? Dupe"),
                            btn -> executeDupeCheck(group.ip())
                        ).method_46434(centerX - 10, rowY, 70, 18)
                         .method_46436(class_7919.method_47407(class_2561.method_43470(
                             "§7Проверить твинки по IP: §f" + group.ip() + "\n§8Выполнить команду /dupeip"
                         )))
                         .method_46431();
                        this.method_37063(checkBtn);
                    }

                    // 2. [Скоп. IP] button
                    this.method_37063(class_4185.method_46430(
                        class_2561.method_43470("§6Скоп. IP"),
                        button -> {
                            copyToClipboard(group.ip());
                            button.method_25355(class_2561.method_43470("§aСкопирован"));
                        }
                    ).method_46434(centerX + 64, rowY, 62, 18)
                     .method_46436(class_7919.method_47407(class_2561.method_43470("§eСкопировать IP: §f" + group.ip() + "\n§7Всего сессий: §f" + group.count())))
                     .method_46431());

                    // 3. [DupeIP] action button
                    this.method_37063(class_4185.method_46430(
                        class_2561.method_43470("§bDupeIP"),
                        button -> executeDupeCheck(group.ip())
                    ).method_46434(centerX + 130, rowY, 56, 18)
                     .method_46436(class_7919.method_47407(class_2561.method_43470("§bПроверить твинки по этому IP\n§7Выполняет команду: §f/dupeip " + group.ip())))
                     .method_46431());
                }

                // Dynamic Pagination if unique groups > 5
                int tableBoxBottom = startRowY + (shownRows * rowHeight) + 2;
                int paginationY = tableBoxBottom + 4;
                if (maxPages > 1) {
                    class_4185 prevBtn = class_4185.method_46430(
                        class_2561.method_43470("§f◀"),
                        button -> {
                            if (this.currentPage > 0) {
                                this.currentPage--;
                                this.rebuildWidgets();
                            }
                        }
                    ).method_46434(centerX - 75, paginationY, 22, 18)
                     .method_46436(class_7919.method_47407(class_2561.method_43470("§7Предыдущая страница §8(Стрелка влево)")))
                     .method_46431();
                    prevBtn.field_22763 = (this.currentPage > 0);
                    this.method_37063(prevBtn);

                    class_4185 nextBtn = class_4185.method_46430(
                        class_2561.method_43470("§f▶"),
                        button -> {
                            if (this.currentPage < maxPages - 1) {
                                this.currentPage++;
                                this.rebuildWidgets();
                            }
                        }
                    ).method_46434(centerX + 53, paginationY, 22, 18)
                     .method_46436(class_7919.method_47407(class_2561.method_43470("§7Следующая страница §8(Стрелка вправо)")))
                     .method_46431();
                    nextBtn.field_22763 = (this.currentPage < maxPages - 1);
                    this.method_37063(nextBtn);
                }
            }
        } else {
            // Mode: ALL_SESSIONS
            List<PlayerIpEntry> allSessionsList = (!this.displayedNick.isEmpty()) ? IpLookupManager.getAllSessions(this.displayedNick) : java.util.Collections.emptyList();
            List<PlayerIpEntry> displayEntries = (!entries.isEmpty()) ? entries : (!allSessionsList.isEmpty() ? allSessionsList : (lookupData != null ? lookupData.entries : java.util.Collections.emptyList()));
            int totalEntries = displayEntries.size();
            int maxPages = Math.max(1, (int) Math.ceil((double) totalEntries / ROWS_PER_PAGE));
            if (this.currentPage >= maxPages) this.currentPage = maxPages - 1;

            if (!displayEntries.isEmpty()) {
                int startIndex = this.currentPage * ROWS_PER_PAGE;
                int endIndex = Math.min(startIndex + ROWS_PER_PAGE, totalEntries);
                int shownRows = endIndex - startIndex;

                for (int i = startIndex; i < endIndex; i++) {
                    PlayerIpEntry entry = displayEntries.get(i);
                    int rowOffset = i - startIndex;
                    int rowY = startRowY + (rowOffset * rowHeight);

                    // Copy IP button with rich tooltip
                    this.method_37063(class_4185.method_46430(
                        class_2561.method_43470("§6Скоп. IP"),
                        button -> {
                            copyToClipboard(entry.ip());
                            button.method_25355(class_2561.method_43470("§aСкопирован"));
                        }
                    ).method_46434(centerX + 64, rowY, 62, 18)
                     .method_46436(class_7919.method_47407(class_2561.method_43470("§eСкопировать IP: §f" + entry.ip() + "\n§7Нажмите для помещения в буфер обмена")))
                     .method_46431());

                    // DupeIP action button
                    this.method_37063(class_4185.method_46430(
                        class_2561.method_43470("§bDupeIP"),
                        button -> executeDupeCheck(entry.ip())
                    ).method_46434(centerX + 130, rowY, 56, 18)
                     .method_46436(class_7919.method_47407(class_2561.method_43470("§bПроверить твинки по этому IP\n§7Выполняет команду: §f/dupeip " + entry.ip())))
                     .method_46431());
                }

                // Server pagination or client pagination
                int tableBoxBottom = startRowY + (shownRows * rowHeight) + 2;
                int paginationY = tableBoxBottom + 4;
                boolean hasServerPages = lookupData != null && lookupData.hasServerPagination && lookupData.serverTotalPages > 1;

                if (hasServerPages) {
                    boolean canNav = canSendServerNavCommand();
                    String cmdFirst = getServerNavFirstCmd(lookupData);
                    String cmdPrev = getServerNavPrevCmd(lookupData);
                    String cmdNext = getServerNavNextCmd(lookupData);
                    String cmdLast = getServerNavLastCmd(lookupData);

                    // [⏮] First page
                    this.btnServerFirst = class_4185.method_46430(
                        class_2561.method_43470("§f⏮"),
                        button -> executeServerNavCommand(cmdFirst)
                    ).method_46434(centerX - 87, paginationY, 22, 18)
                     .method_46436(class_7919.method_47407(class_2561.method_43470(
                         "§eПервая страница (1/" + lookupData.serverTotalPages + ")\n§7Команда: §f" + (cmdFirst != null ? cmdFirst : "—")
                     )))
                     .method_46431();
                    this.btnServerFirst.field_22763 = canNav && lookupData.serverCurrentPage > 1;
                    this.method_37063(this.btnServerFirst);

                    // [◀] Previous page
                    this.btnServerPrev = class_4185.method_46430(
                        class_2561.method_43470("§f◀"),
                        button -> executeServerNavCommand(cmdPrev)
                    ).method_46434(centerX - 62, paginationY, 22, 18)
                     .method_46436(class_7919.method_47407(class_2561.method_43470(
                         "§eПредыдущая страница\n§7Команда: §f" + (cmdPrev != null ? cmdPrev : "—")
                     )))
                     .method_46431();
                    this.btnServerPrev.field_22763 = canNav && lookupData.serverCurrentPage > 1;
                    this.method_37063(this.btnServerPrev);

                    // [▶] Next page
                    this.btnServerNext = class_4185.method_46430(
                        class_2561.method_43470("§f▶"),
                        button -> executeServerNavCommand(cmdNext)
                    ).method_46434(centerX + 40, paginationY, 22, 18)
                     .method_46436(class_7919.method_47407(class_2561.method_43470(
                         "§eСледующая страница\n§7Команда: §f" + (cmdNext != null ? cmdNext : "—")
                     )))
                     .method_46431();
                    this.btnServerNext.field_22763 = canNav && lookupData.serverCurrentPage < lookupData.serverTotalPages;
                    this.method_37063(this.btnServerNext);

                    // [⏭] Last page
                    this.btnServerLast = class_4185.method_46430(
                        class_2561.method_43470("§f⏭"),
                        button -> executeServerNavCommand(cmdLast)
                    ).method_46434(centerX + 65, paginationY, 22, 18)
                     .method_46436(class_7919.method_47407(class_2561.method_43470(
                         "§eПоследняя страница (" + lookupData.serverTotalPages + "/" + lookupData.serverTotalPages + ")\n§7Команда: §f" + (cmdLast != null ? cmdLast : "—")
                     )))
                     .method_46431();
                    this.btnServerLast.field_22763 = canNav && lookupData.serverCurrentPage < lookupData.serverTotalPages;
                    this.method_37063(this.btnServerLast);
                } else if (maxPages > 1) {
                    class_4185 prevBtn = class_4185.method_46430(
                        class_2561.method_43470("§f◀"),
                        button -> {
                            if (this.currentPage > 0) {
                                this.currentPage--;
                                this.rebuildWidgets();
                            }
                        }
                    ).method_46434(centerX - 75, paginationY, 22, 18)
                     .method_46436(class_7919.method_47407(class_2561.method_43470("§7Предыдущая страница §8(Стрелка влево)")))
                     .method_46431();
                    prevBtn.field_22763 = (this.currentPage > 0);
                    this.method_37063(prevBtn);

                    class_4185 nextBtn = class_4185.method_46430(
                        class_2561.method_43470("§f▶"),
                        button -> {
                            if (this.currentPage < maxPages - 1) {
                                this.currentPage++;
                                this.rebuildWidgets();
                            }
                        }
                    ).method_46434(centerX + 53, paginationY, 22, 18)
                     .method_46436(class_7919.method_47407(class_2561.method_43470("§7Следующая страница §8(Стрелка вправо)")))
                     .method_46431();
                    nextBtn.field_22763 = (this.currentPage < maxPages - 1);
                    this.method_37063(nextBtn);
                }
            }
        }

        // Bottom action controls
        int bottomY = this.field_22790 - 26;

        if (!entries.isEmpty() || (lookupData != null && (!lookupData.allSessions.isEmpty() || !lookupData.allCollectedIps.isEmpty()))) {
            Set<String> uniqueIps = new LinkedHashSet<>();
            if (lookupData != null && !lookupData.allCollectedIps.isEmpty()) {
                uniqueIps.addAll(lookupData.allCollectedIps);
            }
            for (PlayerIpEntry e : entries) {
                uniqueIps.add(e.ip());
            }
            int uniqueCount = uniqueIps.size();

            // 1. [📋 Все IP (N)]
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§e📋 Все IP (" + uniqueCount + ")"),
                button -> {
                    String joined = String.join(" ", uniqueIps);
                    copyToClipboard(joined);
                    button.method_25355(class_2561.method_43470("§aСкопировано!"));
                }
            ).method_46434(centerX - 188, bottomY, 92, 20)
             .method_46436(class_7919.method_47407(class_2561.method_43470("§eСкопировать все уникальные IP через пробел\n§7Всего уникальных адресов: §f" + uniqueCount)))
             .method_46431());

            // 2. [📋 Досье]
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§a📋 Досье"),
                button -> {
                    String dossier = IpLookupManager.generateExpressDossier(this.displayedNick);
                    copyToClipboard(dossier);
                    IpFeedback.onDossierCopied(this.displayedNick);
                    button.method_25355(class_2561.method_43470("§aСкопировано!"));
                }
            ).method_46434(centerX - 92, bottomY, 88, 20)
             .method_46436(class_7919.method_47407(class_2561.method_43470("§aСкопировать полное экспресс-досье игрока\n§7Готово для вставки в Discord/тикет: профиль, сессии, подсети, твинки")))
             .method_46431());

            // 3. [💾 Экспорт]
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§b💾 Экспорт"),
                button -> exportCurrentPlayerReport(button, lookupData, entries)
            ).method_46434(centerX + 0, bottomY, 88, 20)
             .method_46436(class_7919.method_47407(class_2561.method_43470("§bСохранить отчёт об игроке\n§7Папка: §f.minecraft/ipcopy/reports")))
             .method_46431());

            // 4. [Закрыть]
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§fЗакрыть"),
                button -> this.method_25419()
            ).method_46434(centerX + 92, bottomY, 96, 20)
             .method_46436(class_7919.method_47407(class_2561.method_43470("§7Закрыть панель модератора §8(Escape)")))
             .method_46431());
        } else {
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§fЗакрыть"),
                button -> this.method_25419()
            ).method_46434(centerX - 55, bottomY, 110, 20)
             .method_46436(class_7919.method_47407(class_2561.method_43470("§7Закрыть панель модератора §8(Escape)")))
             .method_46431());
        }
    }

    private void setupHistoryTab(int centerX) {
        List<IpHistoryManager.HistoryEntry> history = getFilteredHistory();
        int totalEntries = history.size();

        this.historyFilterField = new class_342(this.field_22793, centerX + 24, 31, 138, 18, class_2561.method_43470("IP, /24 или время..."));
        this.historyFilterField.method_1880(32);
        this.historyFilterField.method_1852(this.historyFilter);
        this.historyFilterField.method_1863(text -> {
            this.historyFilter = text == null ? "" : text.trim();
            this.currentPage = 0;
            this.rebuildWidgets();
            if (this.historyFilterField != null) {
                this.historyFilterField.method_25365(true);
                this.method_25395(this.historyFilterField);
            }
        });
        this.method_37063(this.historyFilterField);
        class_4185 clearFilter = class_4185.method_46430(
            class_2561.method_43470("§c✖"),
            button -> clearHistoryFilter(true)
        ).method_46434(centerX + 165, 31, 18, 18)
         .method_46436(class_7919.method_47407(class_2561.method_43470("§cСбросить фильтр истории")))
         .method_46431();
        clearFilter.field_22763 = !this.historyFilter.isEmpty();
        this.method_37063(clearFilter);
        int maxPages = Math.max(1, (int) Math.ceil((double) totalEntries / ROWS_PER_PAGE));

        if (this.currentPage >= maxPages) {
            this.currentPage = maxPages - 1;
        }

        if (!history.isEmpty()) {
            int startRowY = 56;
            int rowHeight = 21;
            int startIndex = this.currentPage * ROWS_PER_PAGE;
            int endIndex = Math.min(startIndex + ROWS_PER_PAGE, totalEntries);

            for (int i = startIndex; i < endIndex; i++) {
                IpHistoryManager.HistoryEntry entry = history.get(i);
                int rowOffset = i - startIndex;
                int rowY = startRowY + (rowOffset * rowHeight);

                // Copy IP button
                this.method_37063(class_4185.method_46430(
                    class_2561.method_43470("§6Скоп. IP"),
                    button -> {
                        copyToClipboard(entry.ip());
                        button.method_25355(class_2561.method_43470("§aСкопирован"));
                    }
                ).method_46434(centerX + 2, rowY - 1, 56, 18)
                 .method_46436(class_7919.method_47407(class_2561.method_43470("§eСкопировать IP: §f" + entry.ip() + "\n§7Поместить в буфер обмена")))
                 .method_46431());

                // In Search button (switches to lookup tab)
                this.method_37063(class_4185.method_46430(
                    class_2561.method_43470("§e🔍 Поиск"),
                    button -> {
                        this.activeTab = Tab.LOOKUP;
                        this.rebuildWidgets();
                    }
                ).method_46434(centerX + 62, rowY - 1, 58, 18)
                 .method_46436(class_7919.method_47407(class_2561.method_43470("§eПерейти во вкладку поиска игроков")))
                 .method_46431());

                // DupeIP button
                this.method_37063(class_4185.method_46430(
                    class_2561.method_43470("§bDupeIP"),
                    button -> executeDupeCheck(entry.ip())
                ).method_46434(centerX + 124, rowY - 1, 58, 18)
                 .method_46436(class_7919.method_47407(class_2561.method_43470("§bПроверить твинки по этому IP\n§7Выполняет команду: §f/dupeip " + entry.ip())))
                 .method_46431());
            }

            // Pagination navigation controls
            if (maxPages > 1) {
                int shownRows = endIndex - startIndex;
                int tableBoxBottom = startRowY + (shownRows * rowHeight) + 2;
                int paginationY = tableBoxBottom + 4;

                class_4185 prevBtn = class_4185.method_46430(
                    class_2561.method_43470("§f◀"),
                    button -> {
                        if (this.currentPage > 0) {
                            this.currentPage--;
                            this.rebuildWidgets();
                        }
                    }
                ).method_46434(centerX - 75, paginationY, 22, 18)
                 .method_46436(class_7919.method_47407(class_2561.method_43470("§7Предыдущая страница §8(Стрелка влево)")))
                 .method_46431();
                prevBtn.field_22763 = (this.currentPage > 0);
                this.method_37063(prevBtn);

                class_4185 nextBtn = class_4185.method_46430(
                    class_2561.method_43470("§f▶"),
                    button -> {
                        if (this.currentPage < maxPages - 1) {
                            this.currentPage++;
                            this.rebuildWidgets();
                        }
                    }
                ).method_46434(centerX + 53, paginationY, 22, 18)
                 .method_46436(class_7919.method_47407(class_2561.method_43470("§7Следующая страница §8(Стрелка вправо)")))
                 .method_46431();
                nextBtn.field_22763 = (this.currentPage < maxPages - 1);
                this.method_37063(nextBtn);
            }
        }

        // Bottom action controls in History tab
        int bottomY = this.field_22790 - 26;
        if (!history.isEmpty()) {
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§e📋 Все (" + totalEntries + ")"),
                button -> {
                    List<String> allIps = new ArrayList<>();
                    for (IpHistoryManager.HistoryEntry entry : history) allIps.add(entry.ip());
                    String joined = String.join(" ", allIps);
                    copyToClipboard(joined);
                    button.method_25355(class_2561.method_43470("§aСкопировано!"));
                }
            ).method_46434(centerX - 165, bottomY, 100, 20)
             .method_46436(class_7919.method_47407(class_2561.method_43470("§eСкопировать всю историю IP через пробел")))
             .method_46431());

            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§c🗑 Очистить"),
                button -> {
                    IpHistoryManager.clear();
                    this.currentPage = 0;
                    this.rebuildWidgets();
                }
            ).method_46434(centerX - 55, bottomY, 100, 20)
             .method_46436(class_7919.method_47407(class_2561.method_43470("§cОчистить историю скопированных IP из RAM")))
             .method_46431());

            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§fЗакрыть"),
                button -> this.method_25419()
            ).method_46434(centerX + 55, bottomY, 110, 20)
             .method_46436(class_7919.method_47407(class_2561.method_43470("§7Закрыть панель модератора §8(Escape)")))
             .method_46431());
        } else {
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§fЗакрыть"),
                button -> this.method_25419()
            ).method_46434(centerX - 55, bottomY, 110, 20)
             .method_46436(class_7919.method_47407(class_2561.method_43470("§7Закрыть панель модератора §8(Escape)")))
             .method_46431());
        }
    }

    private void setupSettingsTab(int centerX) {
        IpCopyConfig config = IpCopyConfig.getInstance();
        int btnWidth = 145;
        int btnHeight = 20;
        int leftX = centerX - 150;
        int rightX = centerX + 5;
        int startY = 36;
        int spacing = 24;

        // Column 1
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getStatusText(config.enabled)),
            button -> {
                config.enabled = !config.enabled;
                IpCopyProcessor.setEnabled(config.enabled);
                button.method_25355(class_2561.method_43470(getStatusText(config.enabled)));
                IpCopyConfig.save();
            }
        ).method_46434(leftX, startY, btnWidth, btnHeight).method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getSoundText(config.soundFeedback)),
            button -> {
                config.soundFeedback = !config.soundFeedback;
                button.method_25355(class_2561.method_43470(getSoundText(config.soundFeedback)));
                IpCopyConfig.save();
            }
        ).method_46434(leftX, startY + spacing, btnWidth, btnHeight).method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getActionbarText(config.actionbarFeedback)),
            button -> {
                config.actionbarFeedback = !config.actionbarFeedback;
                button.method_25355(class_2561.method_43470(getActionbarText(config.actionbarFeedback)));
                IpCopyConfig.save();
            }
        ).method_46434(leftX, startY + spacing * 2, btnWidth, btnHeight).method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getToastText(config.toastFeedback)),
            button -> {
                config.toastFeedback = !config.toastFeedback;
                button.method_25355(class_2561.method_43470(getToastText(config.toastFeedback)));
                IpCopyConfig.save();
            }
        ).method_46434(leftX, startY + spacing * 3, btnWidth, btnHeight).method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getSilentModeText(config.silentChatMode)),
            button -> {
                config.silentChatMode = !config.silentChatMode;
                button.method_25355(class_2561.method_43470(getSilentModeText(config.silentChatMode)));
                IpCopyConfig.save();
            }
        ).method_46434(leftX, startY + spacing * 4, btnWidth, btnHeight)
         .method_46436(class_7919.method_47407(class_2561.method_43470(
             "§eСкрытый режим чата (Stealth Mode)\n§aВКЛ: §7ответы /auth обрабатываются фоново в дашборде без вывода в чат\n§cВЫКЛ: §7обычный вывод в игровой чат"
         )))
         .method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getAutoFetchText(config.autoFetchAllPages)),
            button -> {
                config.autoFetchAllPages = !config.autoFetchAllPages;
                button.method_25355(class_2561.method_43470(getAutoFetchText(config.autoFetchAllPages)));
                IpCopyConfig.save();
            }
        ).method_46434(leftX, startY + spacing * 5, btnWidth, btnHeight)
         .method_46436(class_7919.method_47407(class_2561.method_43470(
             "§eАвто-сбор всех страниц\n§aВКЛ: §7при открытии игрока фоново скачиваются все страницы сессий\n§cВЫКЛ: §7ручной сбор по кнопке [⚡ Собрать всё]"
         )))
         .method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§eОтправить тест"),
            button -> {
                IpCopyClient.sendTestMessage();
                button.method_25355(class_2561.method_43470("§aОтправлено!"));
            }
        ).method_46434(leftX, startY + spacing * 6, btnWidth, btnHeight).method_46431());

        // Column 2
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getSubnetText(config.highlightSubnets)),
            button -> {
                config.highlightSubnets = !config.highlightSubnets;
                button.method_25355(class_2561.method_43470(getSubnetText(config.highlightSubnets)));
                IpCopyConfig.save();
            }
        ).method_46434(rightX, startY, btnWidth, btnHeight).method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getHistoryText(config.sessionHistory)),
            button -> {
                config.sessionHistory = !config.sessionHistory;
                if (!config.sessionHistory) {
                    IpHistoryManager.clear();
                }
                button.method_25355(class_2561.method_43470(getHistoryText(config.sessionHistory)));
                IpCopyConfig.save();
            }
        ).method_46434(rightX, startY + spacing, btnWidth, btnHeight).method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getSecondActionText(config.showSecondAction)),
            button -> {
                config.showSecondAction = !config.showSecondAction;
                button.method_25355(class_2561.method_43470(getSecondActionText(config.showSecondAction)));
                IpCopyConfig.save();
            }
        ).method_46434(rightX, startY + spacing * 2, btnWidth, btnHeight).method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getPreferUniqueIpsText(config.preferUniqueIpsView)),
            button -> {
                config.preferUniqueIpsView = !config.preferUniqueIpsView;
                button.method_25355(class_2561.method_43470(getPreferUniqueIpsText(config.preferUniqueIpsView)));
                IpCopyConfig.save();
            }
        ).method_46434(rightX, startY + spacing * 3, btnWidth, btnHeight)
         .method_46436(class_7919.method_47407(class_2561.method_43470(
             "§eВид поиска по умолчанию\n§aУник. IP: §7сгруппированные адреса с количеством входов и твинками\n§eСессии: §7список каждой сессии по отдельности"
         )))
         .method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getQuickActionsText(config.showQuickActions)),
            button -> {
                config.showQuickActions = !config.showQuickActions;
                button.method_25355(class_2561.method_43470(getQuickActionsText(config.showQuickActions)));
                IpCopyConfig.save();
            }
        ).method_46434(rightX, startY + spacing * 4, btnWidth, btnHeight)
         .method_46436(class_7919.method_47407(class_2561.method_43470("§eПанель быстрых команд\n§7Шаблоны настраиваются в config/ipcopy.json")))
         .method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§6Сброс кэша"),
            button -> {
                IpLookupManager.clearCache();
                button.method_25355(class_2561.method_43470("§aСброшено!"));
            }
        ).method_46434(rightX, startY + spacing * 5, btnWidth, btnHeight)
         .method_46436(class_7919.method_47407(class_2561.method_43470(
             "§6Сбросить кэш поиска игроков\n§7Очищает кэшированные профили и страницы сессий"
         )))
         .method_46431());

        // Bottom action buttons
        int bottomY = this.field_22790 - 26;
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§cСброс истории"),
            button -> {
                IpHistoryManager.clear();
                button.method_25355(class_2561.method_43470("§7Очищено"));
            }
        ).method_46434(centerX - 125, bottomY, 115, 20).method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§aГотово"),
            button -> this.method_25419()
        ).method_46434(centerX + 10, bottomY, 115, 20).method_46431());
    }

    private void pasteFromClipboard() {
        class_310 client = class_310.method_1551();
        if (client != null && client.field_1774 != null) {
            String raw = client.field_1774.method_1460();
            if (raw != null && !raw.isEmpty()) {
                String clean = sanitizeNick(raw);
                if (!clean.isEmpty()) {
                    this.currentNick = clean;
                    if (this.nickField != null) {
                        this.nickField.method_1852(clean);
                        this.nickField.method_1884(clean.length());
                        this.nickField.method_25365(true);
                        this.method_25395(this.nickField);
                    }
                    this.rebuildWidgets();
                }
            }
        }
    }

    private void triggerPlayerQuery() {
        if (this.currentNick == null || this.currentNick.isEmpty()) {
            return;
        }

        this.displayedNick = this.currentNick;
        lastQueriedNick = this.currentNick;
        this.currentPage = 0;
        this.queryPending = true;
        this.queryStartTime = System.currentTimeMillis();
        this.queryTimedOut = false;

        boolean sent = IpLookupManager.queryPlayer(this.currentNick);
        if (!sent) {
            this.queryPending = false;
        }

        this.rebuildWidgets();
    }

    private void copyToClipboard(String text) {
        class_310 client = class_310.method_1551();
        if (client != null && client.field_1774 != null) {
            client.field_1774.method_1455(text);
            IpFeedback.onIpCopied(text);
        }
    }

    private void executeDupeCheck(String ip) {
        class_310 client = class_310.method_1551();
        if (client != null && client.method_1562() != null) {
            String rawCmd = IpCopyConfig.getInstance().secondActionCommand;
            if (rawCmd == null) rawCmd = "/dupeip %ip%";
            String cmd = rawCmd.replace("%ip%", ip);
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            cmd = cmd.trim();
            if (!cmd.isEmpty()) {
                client.method_1562().method_45730(cmd);
                if (client.field_1724 != null) {
                    client.field_1724.method_7353(class_2561.method_43470("§b[IPCopy] §7Команда отправлена: §f/" + cmd), true);
                }
            }
        }
    }

    private List<IpHistoryManager.HistoryEntry> getFilteredHistory() {
        List<IpHistoryManager.HistoryEntry> all = IpHistoryManager.getDetailedHistory();
        String query = this.historyFilter == null ? "" : this.historyFilter.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return all;
        List<IpHistoryManager.HistoryEntry> filtered = new ArrayList<>();
        for (IpHistoryManager.HistoryEntry entry : all) {
            if (entry.ip().toLowerCase(Locale.ROOT).contains(query)
                || entry.getSubnet24().toLowerCase(Locale.ROOT).contains(query)
                || entry.getFormattedTime().toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    private void clearHistoryFilter(boolean keepFocus) {
        this.historyFilter = "";
        this.currentPage = 0;
        this.rebuildWidgets();
        if (keepFocus && this.historyFilterField != null) {
            this.historyFilterField.method_25365(true);
            this.method_25395(this.historyFilterField);
        }
    }

    private void setupQuickActions(int centerX, IpLookupManager.PlayerLookupData data, List<PlayerIpEntry> entries) {
        IpCopyConfig config = IpCopyConfig.getInstance();
        if (!config.showQuickActions || data == null || data.profile == null || config.quickActions == null || config.quickActions.isEmpty()) return;
        String ip = entries.isEmpty() ? (data.allSessions.isEmpty() ? "" : data.allSessions.get(0).ip()) : entries.get(0).ip();
        int shown = Math.min(4, config.quickActions.size());
        int quickY = 88;
        int btnWidth = 88;
        int btnGap = 4;
        for (int i = 0; i < shown; i++) {
            IpCopyConfig.QuickAction action = config.quickActions.get(i);
            int x = centerX - 182 + i * (btnWidth + btnGap);
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470(action.color + action.name),
                button -> executeQuickAction(action, data.profile, ip)
            ).method_46434(x, quickY, btnWidth, 16)
             .method_46436(class_7919.method_47407(class_2561.method_43470("§eБыстрое действие\n§7Шаблон: §f/" + action.command)))
             .method_46431());
        }
    }

    private void executeQuickAction(IpCopyConfig.QuickAction action, IpLookupManager.PlayerProfile profile, String ip) {
        if (action == null || profile == null || action.command == null) return;
        String cmd = action.command
            .replace("{nick}", sanitizeNick(profile.nick()))
            .replace("{ip}", ip == null ? "" : ip)
            .replace("{uuid}", profile.uuid() == null ? "" : profile.uuid())
            .replace('\r', ' ').replace('\n', ' ').trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1).trim();
        if (cmd.isEmpty() || cmd.length() > 256 || cmd.contains("{") || cmd.contains("}")) return;
        IpLookupManager.executeServerCommand(cmd);
    }

    private void exportCurrentPlayerReport(class_4185 button, IpLookupManager.PlayerLookupData data, List<PlayerIpEntry> entries) {
        if (data == null || this.displayedNick.isEmpty()) return;
        button.field_22763 = false;
        button.method_25355(class_2561.method_43470("§7Сохранение..."));
        PlayerReportExporter.export(this.displayedNick, data.profile, entries, data.allCollectedIps).whenComplete((path, error) -> {
            class_310 client = class_310.method_1551();
            if (client == null) return;
            client.execute(() -> {
                if (error == null && path != null) {
                    button.method_25355(class_2561.method_43470("§a✔ Сохранено"));
                    IpFeedback.onReportExported(path.getFileName().toString(), true);
                } else {
                    button.method_25355(class_2561.method_43470("§cОшибка"));
                    IpFeedback.onReportExported("Не удалось сохранить отчёт", false);
                }
                button.field_22763 = true;
            });
        });
    }

    @Override
    public boolean method_25404(class_11908 keyEvent) {
        if (keyEvent != null) {
            int keyCode = keyEvent.comp_4795();

            if (keyCode == 256 && this.activeTab == Tab.HISTORY && this.historyFilterField != null && this.historyFilterField.method_25370()) {
                if (!this.historyFilter.isEmpty()) {
                    clearHistoryFilter(true);
                } else {
                    this.historyFilterField.method_25365(false);
                    this.method_25395(null);
                }
                return true;
            }

            // Enter triggers query when typing nickname in lookup tab
            if (keyCode == 257 || keyCode == 335) {
                if (this.activeTab == Tab.LOOKUP && this.nickField != null && this.nickField.method_25370()) {
                    triggerPlayerQuery();
                    return true;
                }
            }

            // Bulletproof Ctrl+V and Shift+Insert paste interceptor
            boolean isCtrlV = (keyCode == 86 && (keyEvent.comp_4797() & 2) != 0);
            boolean isShiftInsert = (keyCode == 279 && (keyEvent.comp_4797() & 1) != 0);
            if ((isCtrlV || isShiftInsert) && this.activeTab == Tab.LOOKUP && this.nickField != null) {
                pasteFromClipboard();
                return true;
            }

            // Arrow keys handle instant pagination when text field is not focused
            boolean textFieldFocused = (this.nickField != null && this.nickField.method_25370())
                || (this.historyFilterField != null && this.historyFilterField.method_25370());
            if ((this.activeTab == Tab.LOOKUP || this.activeTab == Tab.HISTORY) && !textFieldFocused) {
                if (this.activeTab == Tab.LOOKUP) {
                    IpLookupManager.PlayerLookupData lookupData = (!this.displayedNick.isEmpty()) ? IpLookupManager.getData(this.displayedNick) : null;
                    if (lookupData != null && lookupData.hasServerPagination && lookupData.serverTotalPages > 1) {
                        if (keyCode == 263 && lookupData.serverCurrentPage > 1) {
                            executeServerNavCommand(getServerNavPrevCmd(lookupData));
                            return true;
                        } else if (keyCode == 262 && lookupData.serverCurrentPage < lookupData.serverTotalPages) {
                            executeServerNavCommand(getServerNavNextCmd(lookupData));
                            return true;
                        } else if (keyCode == 268 && lookupData.serverCurrentPage > 1) {
                            executeServerNavCommand(getServerNavFirstCmd(lookupData));
                            return true;
                        } else if (keyCode == 269 && lookupData.serverCurrentPage < lookupData.serverTotalPages) {
                            executeServerNavCommand(getServerNavLastCmd(lookupData));
                            return true;
                        }
                    }
                }

                int totalEntries = (this.activeTab == Tab.LOOKUP)
                    ? ((!this.displayedNick.isEmpty()) ? IpLookupManager.getEntries(this.displayedNick).size() : 0)
                    : getFilteredHistory().size();
                int maxPages = Math.max(1, (int) Math.ceil((double) totalEntries / ROWS_PER_PAGE));

                if (keyCode == 263 && this.currentPage > 0) {
                    this.currentPage--;
                    this.rebuildWidgets();
                    return true;
                } else if (keyCode == 262 && this.currentPage < maxPages - 1) {
                    this.currentPage++;
                    this.rebuildWidgets();
                    return true;
                }
            }
        }
        return super.method_25404(keyEvent);
    }

    @Override
    public boolean method_25401(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Mouse wheel scroll flips pages in list tabs
        if (this.activeTab == Tab.LOOKUP || this.activeTab == Tab.HISTORY) {
            if (this.activeTab == Tab.LOOKUP) {
                IpLookupManager.PlayerLookupData lookupData = (!this.displayedNick.isEmpty()) ? IpLookupManager.getData(this.displayedNick) : null;
                if (lookupData != null && lookupData.hasServerPagination && lookupData.serverTotalPages > 1) {
                    if (verticalAmount < 0 && lookupData.serverCurrentPage < lookupData.serverTotalPages) {
                        executeServerNavCommand(getServerNavNextCmd(lookupData));
                        return true;
                    } else if (verticalAmount > 0 && lookupData.serverCurrentPage > 1) {
                        executeServerNavCommand(getServerNavPrevCmd(lookupData));
                        return true;
                    }
                }
            }

            int totalEntries = (this.activeTab == Tab.LOOKUP)
                ? ((!this.displayedNick.isEmpty()) ? IpLookupManager.getEntries(this.displayedNick).size() : 0)
                : getFilteredHistory().size();
            int maxPages = Math.max(1, (int) Math.ceil((double) totalEntries / ROWS_PER_PAGE));

            if (maxPages > 1) {
                if (verticalAmount < 0 && this.currentPage < maxPages - 1) {
                    this.currentPage++;
                    this.rebuildWidgets();
                    return true;
                } else if (verticalAmount > 0 && this.currentPage > 0) {
                    this.currentPage--;
                    this.rebuildWidgets();
                    return true;
                }
            }
        }
        return super.method_25401(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void drawText(class_332 context, class_2561 text, int x, int y, int color) {
        int argb = (color & 0xFF000000) != 0 ? color : (0xFF000000 | color);
        context.method_27535(this.field_22793, text, x, y, argb);
    }

    private void drawCenteredText(class_332 context, class_2561 text, int centerX, int y, int color) {
        int argb = (color & 0xFF000000) != 0 ? color : (0xFF000000 | color);
        context.method_27534(this.field_22793, text, centerX, y, argb);
    }

    @Override
    public void method_25394(class_332 context, int mouseX, int mouseY, float delta) {
        // 1. Native background dimming & deep overlay to block world see-through
        this.method_25420(context, mouseX, mouseY, delta);
        context.method_25294(0, 0, this.field_22789, this.field_22790, 0x88000000);

        int centerX = this.field_22789 / 2;

        // 2. Central modal container card (sleek dark glassmorphism)
        int modalTop = 6;
        int modalBottom = this.field_22790 - 4;
        context.method_25294(centerX - 194, modalTop, centerX + 194, modalBottom, 0xEE121216);
        // Subtle crisp border
        context.method_25294(centerX - 194, modalTop, centerX + 194, modalTop + 1, 0xFF353545);
        context.method_25294(centerX - 194, modalBottom - 1, centerX + 194, modalBottom, 0xFF353545);
        context.method_25294(centerX - 194, modalTop, centerX - 193, modalBottom, 0xFF353545);
        context.method_25294(centerX + 193, modalTop, centerX + 194, modalBottom, 0xFF353545);

        // 3. Render all widgets on top of background
        super.method_25394(context, mouseX, mouseY, delta);

        if (this.activeTab == Tab.LOOKUP) {
            IpLookupManager.PlayerLookupData data = (!this.displayedNick.isEmpty()) ? IpLookupManager.getData(this.displayedNick) : null;
            IpLookupManager.LookupStatus status = data != null ? data.status : IpLookupManager.LookupStatus.IDLE;
            List<PlayerIpEntry> entries = data != null ? data.entries : java.util.Collections.emptyList();
            IpLookupManager.PlayerProfile profile = data != null ? data.profile : null;

            // Timeout watchdog checking
            if (this.queryPending || status == IpLookupManager.LookupStatus.WAITING_INFO || status == IpLookupManager.LookupStatus.FETCHING_HISTORY) {
                long elapsed = System.currentTimeMillis() - this.queryStartTime;
                if (!entries.isEmpty() || status == IpLookupManager.LookupStatus.FOUND) {
                    this.queryPending = false;
                    class_310 c = class_310.method_1551();
                    if (c != null) c.execute(this::rebuildWidgets);
                } else if (status == IpLookupManager.LookupStatus.NOT_REGISTERED) {
                    this.queryPending = false;
                    class_310 c = class_310.method_1551();
                    if (c != null) c.execute(this::rebuildWidgets);
                } else if (elapsed >= QUERY_TIMEOUT_MS) {
                    this.queryPending = false;
                    this.queryTimedOut = true;
                    if (data != null) {
                        data.status = IpLookupManager.LookupStatus.TIMED_OUT;
                    }
                    class_310 c = class_310.method_1551();
                    if (c != null) c.execute(this::rebuildWidgets);
                }
            }

            List<IpLookupManager.UniqueIpGroup> uniqueGroups = (!this.displayedNick.isEmpty()) ? IpLookupManager.getUniqueGroups(this.displayedNick) : java.util.Collections.emptyList();
            List<PlayerIpEntry> allSessionsList = (!this.displayedNick.isEmpty()) ? IpLookupManager.getAllSessions(this.displayedNick) : java.util.Collections.emptyList();
            boolean hasAnyData = !entries.isEmpty() || !allSessionsList.isEmpty() || !uniqueGroups.isEmpty();

            if (!hasAnyData) {
                if (status == IpLookupManager.LookupStatus.NOT_REGISTERED) {
                    context.method_25294(centerX - 188, 70, centerX + 188, 132, 0x40000000);
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§c✖ Указанный игрок §e" + this.displayedNick + " §cне зарегистрирован!"),
                        centerX,
                        84,
                        0xFFFF5555
                    );
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§7Сервер SpaceTimes сообщил: игрок не найден в базе данных авторизаций."),
                        centerX,
                        99,
                        0xFFAAAAAA
                    );
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§8Проверьте регистр букв или правильность написания никнейма."),
                        centerX,
                        113,
                        0xFF888888
                    );
                } else if (status == IpLookupManager.LookupStatus.FETCHING_HISTORY) {
                    context.method_25294(centerX - 188, 62, centerX + 188, 146, 0x40000000);
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§a✔ Профиль §e" + this.displayedNick + " §aнайден!"),
                        centerX,
                        72,
                        0xFF55FF55
                    );
                    if (profile != null) {
                        String line1 = "§7UUID: §8" + profile.uuid() + " §7| Прем: §f" + profile.premium();
                        String line2 = "§9VK: §b" + profile.vk() + " §8| §9TG: §b" + profile.telegram() + " §8| §9DS: §b" + profile.discord();
                        drawCenteredText(context, class_2561.method_43470(line1), centerX, 87, 0xFFFFFFFF);
                        drawCenteredText(context, class_2561.method_43470(line2), centerX, 101, 0xFFFFFFFF);
                    }
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§e⏳ Загрузка истории входов... §8(авто-переход по ► Посмотреть историю ◄)"),
                        centerX,
                        122,
                        0xFFFFFF55
                    );
                } else if (this.queryPending || status == IpLookupManager.LookupStatus.WAITING_INFO) {
                    long elapsed = System.currentTimeMillis() - this.queryStartTime;
                    int remainingSec = Math.max(1, (int) Math.ceil((QUERY_TIMEOUT_MS - elapsed) / 1000.0));
                    context.method_25294(centerX - 188, 70, centerX + 188, 125, 0x40000000);
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§e⏳ Запрос отправлен серверу..."),
                        centerX,
                        85,
                        0xFFFFFF55
                    );
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§7Ожидание ответа для §f" + this.displayedNick + " §7(" + remainingSec + " сек)"),
                        centerX,
                        100,
                        0xFFAAAAAA
                    );
                } else if (this.queryTimedOut || status == IpLookupManager.LookupStatus.TIMED_OUT) {
                    context.method_25294(centerX - 188, 70, centerX + 188, 125, 0x40000000);
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§c⚠ Сервер не ответил за 5 секунд."),
                        centerX,
                        85,
                        0xFFFF5555
                    );
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§7Возможно, у вас нет прав на просмотр сессий, либо сервер не ответил на запрос."),
                        centerX,
                        100,
                        0xFFAAAAAA
                    );
                } else if (status == IpLookupManager.LookupStatus.NO_HISTORY) {
                    context.method_25294(centerX - 188, 70, centerX + 188, 125, 0x40000000);
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§eℹ У игрока §f" + this.displayedNick + " §eнет сохраненных сессий."),
                        centerX,
                        88,
                        0xFFFFFF55
                    );
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§7Сервер не вернул историю входов для этого аккаунта."),
                        centerX,
                        102,
                        0xFFAAAAAA
                    );
                } else {
                    context.method_25294(centerX - 188, 70, centerX + 188, 125, 0x40000000);
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§7Введите никнейм и нажмите §e'Запрос' §7или клавишу §aEnter"),
                        centerX,
                        88,
                        0xFFAAAAAA
                    );
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§8Или нажмите §a[Тест] §8для быстрой демонстрации интерфейса"),
                        centerX,
                        102,
                        0xFF888888
                    );
                }
            } else if (this.lookupViewMode == LookupViewMode.UNIQUE_IPS) {
                int totalSessionsCount = !allSessionsList.isEmpty() ? allSessionsList.size() : entries.size();
                int totalUnique = uniqueGroups.size();
                int maxPages = Math.max(1, (int) Math.ceil((double) totalUnique / ROWS_PER_PAGE));
                boolean hasQuickActions = (profile != null && IpCopyConfig.getInstance().showQuickActions);
                int startRowY = hasQuickActions ? 108 : 88;
                int rowHeight = 20;

                // Table header with social info if available (positioned neatly at Y=75)
                String headerText;
                if (profile != null && (!profile.telegram().equals("-") || !profile.vk().equals("-"))) {
                    String social = !profile.telegram().equals("-") ? ("§9TG: §b" + profile.telegram()) : ("§9VK: §b" + profile.vk());
                    headerText = "§6Уник. IP §e" + this.displayedNick + " §7(" + totalUnique + " IP / " + totalSessionsCount + " вх.) §8| " + social;
                } else {
                    headerText = "§6Уникальные IP §e" + this.displayedNick + " §7(Уникальных: " + totalUnique + ", Всего входов: " + totalSessionsCount + "):";
                }
                drawText(context, class_2561.method_43470(headerText), centerX - 185, 75, 0xFFFFFFFF);

                int startIndex = this.currentPage * ROWS_PER_PAGE;
                int endIndex = Math.min(startIndex + ROWS_PER_PAGE, totalUnique);
                int shownRows = endIndex - startIndex;

                // Dynamic table background box wrapping ONLY the shown rows
                int tableBoxTop = startRowY - 2;
                int tableBoxBottom = startRowY + (shownRows * rowHeight) + 2;
                context.method_25294(centerX - 188, tableBoxTop, centerX + 188, tableBoxBottom, 0x48000000);
                context.method_25294(centerX - 188, tableBoxTop, centerX + 188, tableBoxTop + 1, 0x20FFFFFF);
                context.method_25294(centerX - 188, tableBoxBottom - 1, centerX + 188, tableBoxBottom, 0x20FFFFFF);

                for (int i = startIndex; i < endIndex; i++) {
                    IpLookupManager.UniqueIpGroup group = uniqueGroups.get(i);
                    int rowOffset = i - startIndex;
                    int rowY = startRowY + (rowOffset * rowHeight);

                    // Col 1: Index and IP
                    String idxAndIp = "§f" + (i + 1) + ". §e" + group.ip();
                    drawText(context, class_2561.method_43470(idxAndIp), centerX - 182, rowY + 5, 0xFFFFFF55);

                    // Col 2: Count and percentage
                    int pct = totalSessionsCount > 0 ? (int) Math.round(group.percentage()) : 0;
                    String countInfo = "§b" + group.count() + " вх. §8(" + pct + "%)";
                    drawText(context, class_2561.method_43470(countInfo), centerX - 82, rowY + 5, 0xFF55FFFF);
                }

                // Dynamic pagination indicator right below the table
                int paginationY = tableBoxBottom + 4;
                if (maxPages > 1) {
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§7Стр. §e" + (this.currentPage + 1) + "§7/§e" + maxPages),
                        centerX,
                        paginationY + 5,
                        0xFFFFFFFF
                    );
                } else if (totalUnique > 0) {
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§8Показаны все " + totalUnique + " уник. IP игрока"),
                        centerX,
                        paginationY + 5,
                        0xFF888888
                    );
                }
            } else {
                // Mode: ALL_SESSIONS
                List<PlayerIpEntry> displayEntries = !entries.isEmpty() ? entries : allSessionsList;
                int totalEntries = displayEntries.size();
                int maxPages = Math.max(1, (int) Math.ceil((double) totalEntries / ROWS_PER_PAGE));
                boolean hasQuickActions = (profile != null && IpCopyConfig.getInstance().showQuickActions);
                int startRowY = hasQuickActions ? 108 : 88;
                int rowHeight = 20;

                // Subnet occurrence counts for smart badge detection
                List<String> entryIps = new ArrayList<>(displayEntries.size());
                for (PlayerIpEntry e : displayEntries) {
                    entryIps.add(e.ip());
                }
                Map<String, Integer> subnetCounts = SubnetMatcher.countSubnetOccurrences(entryIps);
                boolean highlightSubnets = IpCopyConfig.getInstance().highlightSubnets;

                // Table header with social info if available (positioned neatly at Y=75)
                String headerText;
                if (profile != null && (!profile.telegram().equals("-") || !profile.vk().equals("-"))) {
                    String social = !profile.telegram().equals("-") ? ("§9TG: §b" + profile.telegram()) : ("§9VK: §b" + profile.vk());
                    headerText = "§6Входы §e" + this.displayedNick + " §7(" + totalEntries + ") §8| " + social;
                } else {
                    headerText = "§6Входы игрока §e" + this.displayedNick + " §7(Всего: " + totalEntries + "):";
                }
                drawText(context, class_2561.method_43470(headerText), centerX - 185, 75, 0xFFFFFFFF);

                int startIndex = this.currentPage * ROWS_PER_PAGE;
                int endIndex = Math.min(startIndex + ROWS_PER_PAGE, totalEntries);
                int shownRows = endIndex - startIndex;

                // Dynamic table background box wrapping ONLY the shown rows
                int tableBoxTop = startRowY - 2;
                int tableBoxBottom = startRowY + (shownRows * rowHeight) + 2;
                context.method_25294(centerX - 188, tableBoxTop, centerX + 188, tableBoxBottom, 0x48000000);
                context.method_25294(centerX - 188, tableBoxTop, centerX + 188, tableBoxTop + 1, 0x20FFFFFF);
                context.method_25294(centerX - 188, tableBoxBottom - 1, centerX + 188, tableBoxBottom, 0x20FFFFFF);

                for (int i = startIndex; i < endIndex; i++) {
                    PlayerIpEntry entry = displayEntries.get(i);
                    int rowOffset = i - startIndex;
                    int rowY = startRowY + (rowOffset * rowHeight);

                    // Col 1: Index and Date
                    String indexAndDate = "§f" + (i + 1) + ". §a" + entry.date();
                    drawText(context, class_2561.method_43470(indexAndDate), centerX - 182, rowY + 5, 0xFFFFFFFF);

                    // Col 2: IP Address
                    drawText(context, class_2561.method_43470("§e" + entry.ip()), centerX - 78, rowY + 5, 0xFFFFFF55);

                    // Col 3: Subnet badge if shared with other sessions, otherwise session type
                    String subnet = SubnetMatcher.getSubnet24String(entry.ip());
                    boolean isDupeSubnet = highlightSubnets && subnetCounts.getOrDefault(subnet, 0) > 1;

                    if (isDupeSubnet) {
                        drawText(context, class_2561.method_43470("§d[⚡/24]"), centerX + 12, rowY + 5, 0xFFFFAAFF);
                    } else {
                        drawText(context, class_2561.method_43470("§8[" + entry.sessionType() + "]"), centerX + 12, rowY + 5, 0xFF888888);
                    }
                }

                // Dynamic page indicator between arrows
                int paginationY = tableBoxBottom + 4;
                boolean hasServerPages = data != null && data.hasServerPagination && data.serverTotalPages > 1;

                if (hasServerPages) {
                    long elapsed = System.currentTimeMillis() - this.lastServerNavTime;
                    if (this.isNavigatingServerPage && (System.currentTimeMillis() - this.serverNavStartTime > 4000L)) {
                        this.isNavigatingServerPage = false;
                    }

                    boolean canNav = canSendServerNavCommand();
                    if (this.btnServerFirst != null) this.btnServerFirst.field_22763 = canNav && data.serverCurrentPage > 1;
                    if (this.btnServerPrev != null) this.btnServerPrev.field_22763 = canNav && data.serverCurrentPage > 1;
                    if (this.btnServerNext != null) this.btnServerNext.field_22763 = canNav && data.serverCurrentPage < data.serverTotalPages;
                    if (this.btnServerLast != null) this.btnServerLast.field_22763 = canNav && data.serverCurrentPage < data.serverTotalPages;

                    String pageStatus;
                    if (this.isNavigatingServerPage) {
                        pageStatus = "§e⏳ Загрузка...";
                    } else if (elapsed < SERVER_NAV_COOLDOWN_MS) {
                        double remainSec = (SERVER_NAV_COOLDOWN_MS - elapsed) / 1000.0;
                        pageStatus = String.format(java.util.Locale.ROOT, "§7Стр. §e%d§7/§e%d §8(%.1fs)", data.serverCurrentPage, data.serverTotalPages, remainSec);
                    } else {
                        pageStatus = "§7Стр. §e" + data.serverCurrentPage + "§7/§e" + data.serverTotalPages;
                    }

                    drawCenteredText(
                        context,
                        class_2561.method_43470(pageStatus),
                        centerX,
                        paginationY + 5,
                        0xFFFFFFFF
                    );
                } else if (maxPages > 1) {
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§7Стр. §e" + (this.currentPage + 1) + "§7/§e" + maxPages),
                        centerX,
                        paginationY + 5,
                        0xFFFFFFFF
                    );
                }
            }
        } else if (this.activeTab == Tab.HISTORY) {
            List<IpHistoryManager.HistoryEntry> history = getFilteredHistory();
            int totalEntries = history.size();
            int fullHistorySize = IpHistoryManager.size();

            drawText(context, class_2561.method_43470("§7Найдено: §e" + totalEntries + "§7/§e" + fullHistorySize), centerX - 185, 38, 0xFFAAAAAA);

            if (history.isEmpty()) {
                drawCenteredText(
                    context,
                    class_2561.method_43470(fullHistorySize > 0 ? "§eПо текущему фильтру совпадений нет." : "§7История скопированных IP в этой сессии пуста."),
                    centerX,
                    95,
                    fullHistorySize > 0 ? 0xFFFFFF55 : 0xFFAAAAAA
                );
                drawCenteredText(
                    context,
                    class_2561.method_43470(fullHistorySize > 0 ? "§8Очистите фильтр кнопкой ✖ или клавишей Escape." : "§8Копируйте IP в чате или через дашборд — они сразу появятся здесь!"),
                    centerX,
                    110,
                    0xFF888888
                );
            } else {
                int maxPages = Math.max(1, (int) Math.ceil((double) totalEntries / ROWS_PER_PAGE));

                int startRowY = 56;
                int rowHeight = 21;
                int startIndex = this.currentPage * ROWS_PER_PAGE;
                int endIndex = Math.min(startIndex + ROWS_PER_PAGE, totalEntries);
                int shownRows = endIndex - startIndex;

                // Dynamic card container background
                int tableBoxTop = startRowY - 2;
                int tableBoxBottom = startRowY + (shownRows * rowHeight) + 2;
                context.method_25294(centerX - 188, tableBoxTop, centerX + 188, tableBoxBottom, 0x48000000);
                context.method_25294(centerX - 188, tableBoxTop, centerX + 188, tableBoxTop + 1, 0x20FFFFFF);
                context.method_25294(centerX - 188, tableBoxBottom - 1, centerX + 188, tableBoxBottom, 0x20FFFFFF);

                for (int i = startIndex; i < endIndex; i++) {
                    IpHistoryManager.HistoryEntry entry = history.get(i);
                    int rowOffset = i - startIndex;
                    int rowY = startRowY + (rowOffset * rowHeight);

                    // Col 1: Formatted time
                    drawText(context, class_2561.method_43470("§8[" + entry.getFormattedTime() + "]"), centerX - 182, rowY + 5, 0xFF888888);

                    // Col 2: IP Address
                    drawText(context, class_2561.method_43470("§e" + entry.ip()), centerX - 124, rowY + 5, 0xFFFFFF55);

                    // Col 3: Subnet /24 micro badge
                    drawText(context, class_2561.method_43470("§b/24"), centerX - 24, rowY + 5, 0xFF55FFFF);
                }

                // Page indicator between arrows
                int paginationY = tableBoxBottom + 4;
                if (maxPages > 1) {
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§7Стр. §e" + (this.currentPage + 1) + "§7/§e" + maxPages),
                        centerX,
                        paginationY + 5,
                        0xFFFFFFFF
                    );
                }
            }
        } else {
            int footerY = this.field_22790 - 42;
            drawCenteredText(
                context,
                class_2561.method_43470("§8Настройки сохраняются автоматически в config/ipcopy.json"),
                centerX,
                footerY,
                0xFF888888
            );
        }
    }

    @Override
    public void method_25419() {
        if (this.field_22787 != null) {
            this.field_22787.method_1507(this.parent);
        }
    }

    @Override
    public void method_25432() {
        super.method_25432();
        // Guaranteed cleanup on screen dismissal to avoid memory leaks
        this.btnServerFirst = null;
        this.btnServerPrev = null;
        this.btnServerNext = null;
        this.btnServerLast = null;
        IpLookupManager.setUpdateListener(null);
        IpCopyConfig.save();
    }

    private static String getStatusText(boolean state) {
        return "§7Мод: " + (state ? "§aВКЛ" : "§cВЫКЛ");
    }

    private static String getSoundText(boolean state) {
        return "§7Звук клика: " + (state ? "§aВКЛ" : "§cВЫКЛ");
    }

    private static String getActionbarText(boolean state) {
        return "§7Actionbar: " + (state ? "§aВКЛ" : "§cВЫКЛ");
    }

    private static String getToastText(boolean state) {
        return "§7Toast: " + (state ? "§aВКЛ" : "§cВЫКЛ");
    }

    private static String getSubnetText(boolean state) {
        return "§7Подсети /24: " + (state ? "§aВКЛ" : "§cВЫКЛ");
    }

    private static String getHistoryText(boolean state) {
        return "§7История RAM: " + (state ? "§aВКЛ" : "§cВЫКЛ");
    }

    private static String getSecondActionText(boolean state) {
        return "§7/dupeip: " + (state ? "§aВКЛ" : "§cВЫКЛ");
    }

    private static String getSilentModeText(boolean state) {
        return "§7Чат: " + (state ? "§cСкрытый" : "§aОбычный");
    }

    private static String getQuickActionsText(boolean state) {
        return "§7Быстрые действия: " + (state ? "§aВКЛ" : "§cВЫКЛ");
    }

    private static String getAutoFetchText(boolean state) {
        return "§7Авто-сбор: " + (state ? "§aВКЛ" : "§cВЫКЛ");
    }

    private static String getPreferUniqueIpsText(boolean state) {
        return "§7Вид по умолч.: " + (state ? "§6Уник. IP" : "§bСессии");
    }

    private static String formatCompactDateRange(String d1, String d2) {
        if (d1 == null || d1.isEmpty()) return "";
        if (d2 == null || d2.isEmpty() || d1.equals(d2)) return extractShortDate(d1);
        String s1 = extractShortDate(d1);
        String s2 = extractShortDate(d2);
        if (s1.equals(s2)) return s1;
        return s1 + "—" + s2;
    }

    private static String extractShortDate(String fullDate) {
        if (fullDate == null) return "";
        int dash1 = fullDate.indexOf('-');
        if (dash1 >= 0) {
            int dash2 = fullDate.indexOf('-', dash1 + 1);
            if (dash2 > dash1) {
                String day = fullDate.substring(0, dash1).trim();
                String month = fullDate.substring(dash1 + 1, dash2).trim();
                return day + "." + month;
            }
        }
        return fullDate.length() > 5 ? fullDate.substring(0, 5) : fullDate;
    }
}
