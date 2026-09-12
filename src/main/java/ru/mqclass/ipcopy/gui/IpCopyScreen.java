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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    private static final int ROWS_PER_PAGE = 5;
    private static final long QUERY_TIMEOUT_MS = 5000L;

    private final class_437 parent;
    private Tab activeTab = Tab.LOOKUP;
    private class_342 nickField;
    private static volatile String lastQueriedNick = "";
    private String currentNick = "";
    private String displayedNick = "";

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

    private boolean canSendServerNavCommand() {
        return !this.isNavigatingServerPage && (System.currentTimeMillis() - this.lastServerNavTime >= SERVER_NAV_COOLDOWN_MS);
    }

    private void executeServerNavCommand(String cmd) {
        if (cmd == null || cmd.isEmpty() || !canSendServerNavCommand()) return;

        this.lastServerNavTime = System.currentTimeMillis();
        this.isNavigatingServerPage = true;
        this.serverNavStartTime = this.lastServerNavTime;

        IpLookupManager.executeServerCommand(cmd);
        this.rebuildWidgets();
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

        if (!this.currentNick.isEmpty() && IpLookupManager.getData(this.currentNick) != null) {
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

        // Nickname text field (width: 125)
        this.nickField = new class_342(this.field_22793, centerX - 165, inputY, 125, 20, class_2561.method_43470("Ник игрока..."));
        this.nickField.method_1880(16);
        this.nickField.method_1852(this.currentNick);
        this.nickField.method_1863(text -> {
            String trimmed = text.trim();
            this.currentNick = trimmed;
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
        ).method_46434(centerX - 37, inputY, 18, 20)
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
        ).method_46434(centerX - 16, inputY, 18, 20)
         .method_46436(class_7919.method_47407(class_2561.method_43470("§cОчистить поле поиска и результаты")))
         .method_46431());

        IpLookupManager.PlayerLookupData lookupData = (!this.displayedNick.isEmpty()) ? IpLookupManager.getData(this.displayedNick) : null;
        boolean isFetching = this.queryPending || (lookupData != null && (lookupData.status == IpLookupManager.LookupStatus.WAITING_INFO || lookupData.status == IpLookupManager.LookupStatus.FETCHING_HISTORY));

        // Search button with tooltip & pending state
        class_4185 searchBtn = class_4185.method_46430(
            class_2561.method_43470(isFetching ? "§7⏳ Поиск..." : "§e🔍 Запрос"),
            button -> triggerPlayerQuery()
        ).method_46434(centerX + 6, inputY, 78, 20)
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
        ).method_46434(centerX + 88, inputY, 77, 20)
         .method_46436(class_7919.method_47407(class_2561.method_43470("§aЗагрузить тестовый профиль Odinoky\n§7Демонстрация профиля (UUID, VK, TG) и 6 сессий с подсетями /24")))
         .method_46431());

        // Profile Info button with rich tooltip and 1-click UUID copy
        if (lookupData != null && lookupData.profile != null) {
            IpLookupManager.PlayerProfile profile = lookupData.profile;
            String tooltipText = "§6§lИнформация об игроке\n" +
                "§7Ник: §f" + profile.nick() + "\n" +
                "§7UUID: §8" + profile.uuid() + "\n" +
                "§7Премиум: §f" + profile.premium() + "\n" +
                "§7VK: §b" + profile.vk() + "\n" +
                "§7Telegram: §b" + profile.telegram() + "\n" +
                "§7Discord: §8" + profile.discord() + "\n" +
                "§e(Нажмите, чтобы скопировать UUID)";

            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§bℹ Инфо"),
                btn -> {
                    if (profile.uuid() != null && !profile.uuid().isEmpty()) {
                        copyToClipboard(profile.uuid());
                        btn.method_25355(class_2561.method_43470("§aСкопирован"));
                    }
                }
            ).method_46434(centerX + 128, 49, 54, 16)
             .method_46436(class_7919.method_47407(class_2561.method_43470(tooltipText)))
             .method_46431());
        }

        List<PlayerIpEntry> entries = (!this.displayedNick.isEmpty()) ? IpLookupManager.getEntries(this.displayedNick) : java.util.Collections.emptyList();
        int totalEntries = entries.size();
        int maxPages = Math.max(1, (int) Math.ceil((double) totalEntries / ROWS_PER_PAGE));

        if (this.currentPage >= maxPages) {
            this.currentPage = maxPages - 1;
        }

        // Render current page entry rows
        if (!entries.isEmpty()) {
            int startRowY = 70;
            int rowHeight = 21;
            int startIndex = this.currentPage * ROWS_PER_PAGE;
            int endIndex = Math.min(startIndex + ROWS_PER_PAGE, totalEntries);

            for (int i = startIndex; i < endIndex; i++) {
                PlayerIpEntry entry = entries.get(i);
                int rowOffset = i - startIndex;
                int rowY = startRowY + (rowOffset * rowHeight);

                // Copy IP button with rich tooltip
                this.method_37063(class_4185.method_46430(
                    class_2561.method_43470("§6Скоп. IP"),
                    button -> {
                        copyToClipboard(entry.ip());
                        button.method_25355(class_2561.method_43470("§aСкопирован"));
                    }
                ).method_46434(centerX + 62, rowY - 1, 62, 18)
                 .method_46436(class_7919.method_47407(class_2561.method_43470("§eСкопировать IP: §f" + entry.ip() + "\n§7Нажмите для помещения в буфер обмена")))
                 .method_46431());

                // DupeIP action button
                this.method_37063(class_4185.method_46430(
                    class_2561.method_43470("§bDupeIP"),
                    button -> executeDupeCheck(entry.ip())
                ).method_46434(centerX + 128, rowY - 1, 54, 18)
                 .method_46436(class_7919.method_47407(class_2561.method_43470("§bПроверить твинки по этому IP\n§7Выполняет команду: §f/dupeip " + entry.ip())))
                 .method_46431());
            }

            // Pagination navigation controls
            boolean hasServerPages = lookupData != null && lookupData.hasServerPagination && lookupData.serverTotalPages > 1;

            if (hasServerPages) {
                int paginationY = 178;
                boolean canNav = canSendServerNavCommand();

                // [⏮] First page
                class_4185 firstBtn = class_4185.method_46430(
                    class_2561.method_43470("§f⏮"),
                    button -> executeServerNavCommand(lookupData.cmdFirst)
                ).method_46434(centerX - 87, paginationY, 22, 18)
                 .method_46436(class_7919.method_47407(class_2561.method_43470(
                     "§eПервая страница (1/" + lookupData.serverTotalPages + ")\n§7Команда: §f" + (lookupData.cmdFirst != null ? lookupData.cmdFirst : "—")
                 )))
                 .method_46431();
                firstBtn.field_22763 = canNav && lookupData.cmdFirst != null && lookupData.serverCurrentPage > 1;
                this.method_37063(firstBtn);

                // [◀] Previous page
                class_4185 prevBtn = class_4185.method_46430(
                    class_2561.method_43470("§f◀"),
                    button -> executeServerNavCommand(lookupData.cmdPrev)
                ).method_46434(centerX - 62, paginationY, 22, 18)
                 .method_46436(class_7919.method_47407(class_2561.method_43470(
                     "§eПредыдущая страница\n§7Команда: §f" + (lookupData.cmdPrev != null ? lookupData.cmdPrev : "—")
                 )))
                 .method_46431();
                prevBtn.field_22763 = canNav && lookupData.cmdPrev != null && lookupData.serverCurrentPage > 1;
                this.method_37063(prevBtn);

                // [▶] Next page
                class_4185 nextBtn = class_4185.method_46430(
                    class_2561.method_43470("§f▶"),
                    button -> executeServerNavCommand(lookupData.cmdNext)
                ).method_46434(centerX + 40, paginationY, 22, 18)
                 .method_46436(class_7919.method_47407(class_2561.method_43470(
                     "§eСледующая страница\n§7Команда: §f" + (lookupData.cmdNext != null ? lookupData.cmdNext : "—")
                 )))
                 .method_46431();
                nextBtn.field_22763 = canNav && lookupData.cmdNext != null && lookupData.serverCurrentPage < lookupData.serverTotalPages;
                this.method_37063(nextBtn);

                // [⏭] Last page
                class_4185 lastBtn = class_4185.method_46430(
                    class_2561.method_43470("§f⏭"),
                    button -> executeServerNavCommand(lookupData.cmdLast)
                ).method_46434(centerX + 65, paginationY, 22, 18)
                 .method_46436(class_7919.method_47407(class_2561.method_43470(
                     "§eПоследняя страница (" + lookupData.serverTotalPages + "/" + lookupData.serverTotalPages + ")\n§7Команда: §f" + (lookupData.cmdLast != null ? lookupData.cmdLast : "—")
                 )))
                 .method_46431();
                lastBtn.field_22763 = canNav && lookupData.cmdLast != null && lookupData.serverCurrentPage < lookupData.serverTotalPages;
                this.method_37063(lastBtn);
            } else if (maxPages > 1) {
                int paginationY = 178;

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

        // Bottom action controls
        int bottomY = this.field_22790 - 26;

        if (!entries.isEmpty()) {
            Set<String> uniqueIps = new LinkedHashSet<>();
            if (lookupData != null && !lookupData.allCollectedIps.isEmpty()) {
                uniqueIps.addAll(lookupData.allCollectedIps);
            }
            for (PlayerIpEntry e : entries) {
                uniqueIps.add(e.ip());
            }
            int uniqueCount = uniqueIps.size();

            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§e📋 Все IP (" + uniqueCount + ")"),
                button -> {
                    String joined = String.join(" ", uniqueIps);
                    copyToClipboard(joined);
                    button.method_25355(class_2561.method_43470("§aСкопировано!"));
                }
            ).method_46434(centerX - 145, bottomY, 160, 20)
             .method_46436(class_7919.method_47407(class_2561.method_43470("§eСкопировать все уникальные IP через пробел\n§7Всего уникальных адресов: §f" + uniqueCount)))
             .method_46431());

            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§fЗакрыть"),
                button -> this.method_25419()
            ).method_46434(centerX + 35, bottomY, 110, 20)
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
        List<IpHistoryManager.HistoryEntry> history = IpHistoryManager.getDetailedHistory();
        int totalEntries = history.size();
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
                int paginationY = 168;

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
                    List<String> allIps = IpHistoryManager.getHistory();
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
            class_2561.method_43470("§eОтправить тест"),
            button -> {
                IpCopyClient.sendTestMessage();
                button.method_25355(class_2561.method_43470("§aОтправлено!"));
            }
        ).method_46434(rightX, startY + spacing * 3, btnWidth, btnHeight).method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§6Сброс кэша"),
            button -> {
                IpLookupManager.clearCache();
                button.method_25355(class_2561.method_43470("§aСброшено!"));
            }
        ).method_46434(rightX, startY + spacing * 4, btnWidth, btnHeight)
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

    @Override
    public boolean method_25404(class_11908 keyEvent) {
        if (keyEvent != null) {
            int keyCode = keyEvent.comp_4795();

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
            if ((isCtrlV || isShiftInsert) && this.activeTab == Tab.LOOKUP && this.nickField != null && this.nickField.method_25370()) {
                pasteFromClipboard();
                return true;
            }

            // Arrow keys handle instant pagination when text field is not focused
            if ((this.activeTab == Tab.LOOKUP || this.activeTab == Tab.HISTORY) && (this.nickField == null || !this.nickField.method_25370())) {
                if (this.activeTab == Tab.LOOKUP) {
                    IpLookupManager.PlayerLookupData lookupData = (!this.displayedNick.isEmpty()) ? IpLookupManager.getData(this.displayedNick) : null;
                    if (lookupData != null && lookupData.hasServerPagination && lookupData.serverTotalPages > 1) {
                        if (keyCode == 263 && lookupData.cmdPrev != null && lookupData.serverCurrentPage > 1) {
                            executeServerNavCommand(lookupData.cmdPrev);
                            return true;
                        } else if (keyCode == 262 && lookupData.cmdNext != null && lookupData.serverCurrentPage < lookupData.serverTotalPages) {
                            executeServerNavCommand(lookupData.cmdNext);
                            return true;
                        } else if (keyCode == 268 && lookupData.cmdFirst != null && lookupData.serverCurrentPage > 1) {
                            executeServerNavCommand(lookupData.cmdFirst);
                            return true;
                        } else if (keyCode == 269 && lookupData.cmdLast != null && lookupData.serverCurrentPage < lookupData.serverTotalPages) {
                            executeServerNavCommand(lookupData.cmdLast);
                            return true;
                        }
                    }
                }

                int totalEntries = (this.activeTab == Tab.LOOKUP)
                    ? ((!this.displayedNick.isEmpty()) ? IpLookupManager.getEntries(this.displayedNick).size() : 0)
                    : IpHistoryManager.size();
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
                    if (verticalAmount < 0 && lookupData.cmdNext != null && lookupData.serverCurrentPage < lookupData.serverTotalPages) {
                        executeServerNavCommand(lookupData.cmdNext);
                        return true;
                    } else if (verticalAmount > 0 && lookupData.cmdPrev != null && lookupData.serverCurrentPage > 1) {
                        executeServerNavCommand(lookupData.cmdPrev);
                        return true;
                    }
                }
            }

            int totalEntries = (this.activeTab == Tab.LOOKUP)
                ? ((!this.displayedNick.isEmpty()) ? IpLookupManager.getEntries(this.displayedNick).size() : 0)
                : IpHistoryManager.size();
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
        super.method_25394(context, mouseX, mouseY, delta);

        int centerX = this.field_22789 / 2;

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

            if (entries.isEmpty()) {
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
            } else {
                int totalEntries = entries.size();
                int maxPages = Math.max(1, (int) Math.ceil((double) totalEntries / ROWS_PER_PAGE));

                // Table background card (semi-transparent dark container)
                context.method_25294(centerX - 188, 66, centerX + 188, 174, 0x40000000);

                // Subnet occurrence counts for smart badge detection
                List<String> entryIps = new ArrayList<>(entries.size());
                for (PlayerIpEntry e : entries) {
                    entryIps.add(e.ip());
                }
                Map<String, Integer> subnetCounts = SubnetMatcher.countSubnetOccurrences(entryIps);
                boolean highlightSubnets = IpCopyConfig.getInstance().highlightSubnets;

                // Table header with social info if available
                String headerText;
                if (profile != null && (!profile.telegram().equals("-") || !profile.vk().equals("-"))) {
                    String social = !profile.telegram().equals("-") ? ("§9TG: §b" + profile.telegram()) : ("§9VK: §b" + profile.vk());
                    headerText = "§6Входы §e" + this.displayedNick + " §7(" + totalEntries + ") §8| " + social;
                } else {
                    headerText = "§6Входы игрока §e" + this.displayedNick + " §7(Всего: " + totalEntries + "):";
                }

                drawText(
                    context,
                    class_2561.method_43470(headerText),
                    centerX - 185,
                    54,
                    0xFFFFFFFF
                );

                int startRowY = 70;
                int rowHeight = 21;
                int startIndex = this.currentPage * ROWS_PER_PAGE;
                int endIndex = Math.min(startIndex + ROWS_PER_PAGE, totalEntries);

                for (int i = startIndex; i < endIndex; i++) {
                    PlayerIpEntry entry = entries.get(i);
                    int rowOffset = i - startIndex;
                    int rowY = startRowY + (rowOffset * rowHeight);

                    // Col 1: Index and Date
                    String indexAndDate = "§f" + (i + 1) + ". §a" + entry.date();
                    drawText(context, class_2561.method_43470(indexAndDate), centerX - 182, rowY + 4, 0xFFFFFFFF);

                    // Col 2: IP Address
                    drawText(context, class_2561.method_43470("§e" + entry.ip()), centerX - 78, rowY + 4, 0xFFFFFF55);

                    // Col 3: Subnet badge if shared with other sessions, otherwise session type
                    String subnet = SubnetMatcher.getSubnet24String(entry.ip());
                    boolean isDupeSubnet = highlightSubnets && subnetCounts.getOrDefault(subnet, 0) > 1;

                    if (isDupeSubnet) {
                        drawText(context, class_2561.method_43470("§d[⚡/24]"), centerX + 12, rowY + 4, 0xFFFFAAFF);
                    } else {
                        drawText(context, class_2561.method_43470("§8[" + entry.sessionType() + "]"), centerX + 12, rowY + 4, 0xFF888888);
                    }
                }

                // Page indicator between arrows
                boolean hasServerPages = data != null && data.hasServerPagination && data.serverTotalPages > 1;

                if (hasServerPages) {
                    long elapsed = System.currentTimeMillis() - this.lastServerNavTime;
                    if (this.isNavigatingServerPage && (System.currentTimeMillis() - this.serverNavStartTime > 4000L)) {
                        this.isNavigatingServerPage = false;
                        this.rebuildWidgets();
                    }

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
                        183,
                        0xFFFFFFFF
                    );
                } else if (maxPages > 1) {
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§7Стр. §e" + (this.currentPage + 1) + "§7/§e" + maxPages),
                        centerX,
                        183,
                        0xFFFFFFFF
                    );
                }
            }
        } else if (this.activeTab == Tab.HISTORY) {
            List<IpHistoryManager.HistoryEntry> history = IpHistoryManager.getDetailedHistory();
            int totalEntries = history.size();

            if (history.isEmpty()) {
                drawCenteredText(
                    context,
                    class_2561.method_43470("§7История скопированных IP в этой сессии пуста."),
                    centerX,
                    95,
                    0xFFAAAAAA
                );
                drawCenteredText(
                    context,
                    class_2561.method_43470("§8Копируйте IP в чате или через дашборд — они сразу появятся здесь!"),
                    centerX,
                    110,
                    0xFF888888
                );
            } else {
                int maxPages = Math.max(1, (int) Math.ceil((double) totalEntries / ROWS_PER_PAGE));

                // Card container background
                context.method_25294(centerX - 188, 52, centerX + 188, 164, 0x40000000);

                // Table header
                drawText(
                    context,
                    class_2561.method_43470("§6История скопированных IP §7(Всего: " + totalEntries + "):"),
                    centerX - 185,
                    40,
                    0xFFFFFFFF
                );

                int startRowY = 56;
                int rowHeight = 21;
                int startIndex = this.currentPage * ROWS_PER_PAGE;
                int endIndex = Math.min(startIndex + ROWS_PER_PAGE, totalEntries);

                for (int i = startIndex; i < endIndex; i++) {
                    IpHistoryManager.HistoryEntry entry = history.get(i);
                    int rowOffset = i - startIndex;
                    int rowY = startRowY + (rowOffset * rowHeight);

                    // Col 1: Formatted time
                    drawText(context, class_2561.method_43470("§8[" + entry.getFormattedTime() + "]"), centerX - 182, rowY + 4, 0xFF888888);

                    // Col 2: IP Address
                    drawText(context, class_2561.method_43470("§e" + entry.ip()), centerX - 124, rowY + 4, 0xFFFFFF55);

                    // Col 3: Subnet /24 micro badge
                    drawText(context, class_2561.method_43470("§b/24"), centerX - 24, rowY + 4, 0xFF55FFFF);
                }

                // Page indicator between arrows
                if (maxPages > 1) {
                    drawCenteredText(
                        context,
                        class_2561.method_43470("§7Стр. §e" + (this.currentPage + 1) + "§7/§e" + maxPages),
                        centerX,
                        173,
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
}
