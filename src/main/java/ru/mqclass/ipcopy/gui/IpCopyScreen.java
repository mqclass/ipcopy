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
    private String currentNick = "Odinoky";

    // Pagination state
    private int currentPage = 0;

    // Async request state tracking
    private boolean queryPending = false;
    private long queryStartTime = 0L;
    private boolean queryTimedOut = false;

    public IpCopyScreen(class_437 parent) {
        super(class_2561.method_43470("IP Copy — Панель модератора"));
        this.parent = parent;
        this.currentNick = "Odinoky";
    }

    public IpCopyScreen(class_437 parent, String initialNick) {
        super(class_2561.method_43470("IP Copy — Панель модератора"));
        this.parent = parent;
        if (initialNick != null && !initialNick.trim().isEmpty()) {
            String sanitized = initialNick.trim().replaceAll("[^A-Za-z0-9_]", "");
            if (sanitized.length() > 16) {
                sanitized = sanitized.substring(0, 16);
            }
            this.currentNick = sanitized;
        } else {
            this.currentNick = "Odinoky";
        }
    }

    @Override
    protected void method_25426() {
        super.method_25426();

        // Register reactive update listener on UI init/resize
        IpLookupManager.setUpdateListener(nick -> {
            if (nick != null && nick.equalsIgnoreCase(this.currentNick)) {
                this.queryPending = false;
                this.queryTimedOut = false;
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

        // Nickname text field (width: 140)
        this.nickField = new class_342(this.field_22793, centerX - 165, inputY, 140, 20, class_2561.method_43470("Ник игрока..."));
        this.nickField.method_1880(16);
        this.nickField.method_1852(this.currentNick);
        this.nickField.method_1863(text -> {
            this.currentNick = text.trim();
            this.currentPage = 0;
            this.queryTimedOut = false;
        });
        this.nickField.method_1890(str -> str.matches("[A-Za-z0-9_]*"));
        this.method_37063(this.nickField);
        this.method_25395(this.nickField);
        this.nickField.method_25365(true);

        IpLookupManager.PlayerLookupData lookupData = IpLookupManager.getData(this.currentNick);
        boolean isFetching = this.queryPending || (lookupData != null && (lookupData.status == IpLookupManager.LookupStatus.WAITING_INFO || lookupData.status == IpLookupManager.LookupStatus.FETCHING_HISTORY));

        // Search button with tooltip & pending state
        class_4185 searchBtn = class_4185.method_46430(
            class_2561.method_43470(isFetching ? "§7⏳ Поиск..." : "§e🔍 Запросить"),
            button -> triggerPlayerQuery()
        ).method_46434(centerX - 20, inputY, 90, 20)
         .method_46436(class_7919.method_47407(class_2561.method_43470("§eЗапросить историю сессий\n§7Поиск сессий игрока: §f" + this.currentNick + "\n§8(Клавиша Enter в поле)")))
         .method_46431();
        searchBtn.field_22763 = !isFetching;
        this.method_37063(searchBtn);

        // Quick test Odinoky button
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§aТест: Odinoky"),
            button -> {
                this.currentNick = "Odinoky";
                this.currentPage = 0;
                this.queryPending = false;
                this.queryTimedOut = false;
                if (this.nickField != null) {
                    this.nickField.method_1852("Odinoky");
                }
                this.rebuildWidgets();
            }
        ).method_46434(centerX + 75, inputY, 90, 20)
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

        List<PlayerIpEntry> entries = IpLookupManager.getEntries(this.currentNick);
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
            if (maxPages > 1) {
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

    private void triggerPlayerQuery() {
        if (this.currentNick == null || this.currentNick.isEmpty()) {
            return;
        }

        this.queryPending = true;
        this.queryStartTime = System.currentTimeMillis();
        this.queryTimedOut = false;

        boolean sent = IpLookupManager.queryPlayer(this.currentNick);
        if (!sent && (this.currentNick.equalsIgnoreCase("Odinoky") || this.currentNick.equalsIgnoreCase("DiNoKy"))) {
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

            // Arrow keys handle instant pagination when text field is not focused
            if ((this.activeTab == Tab.LOOKUP || this.activeTab == Tab.HISTORY) && (this.nickField == null || !this.nickField.method_25370())) {
                int totalEntries = (this.activeTab == Tab.LOOKUP)
                    ? IpLookupManager.getEntries(this.currentNick).size()
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
            int totalEntries = (this.activeTab == Tab.LOOKUP)
                ? IpLookupManager.getEntries(this.currentNick).size()
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

    @Override
    public void method_25394(class_332 context, int mouseX, int mouseY, float delta) {
        super.method_25394(context, mouseX, mouseY, delta);

        int centerX = this.field_22789 / 2;

        if (this.activeTab == Tab.LOOKUP) {
            IpLookupManager.PlayerLookupData data = IpLookupManager.getData(this.currentNick);
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
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§c✖ Указанный игрок §e" + this.currentNick + " §cне зарегистрирован!"),
                        centerX,
                        84,
                        0xFF5555
                    );
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§7Сервер SpaceTimes сообщил: игрок не найден в базе данных авторизаций."),
                        centerX,
                        99,
                        0xAAAAAA
                    );
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§8Проверьте регистр букв или правильность написания никнейма."),
                        centerX,
                        113,
                        0x888888
                    );
                } else if (status == IpLookupManager.LookupStatus.FETCHING_HISTORY) {
                    context.method_25294(centerX - 188, 62, centerX + 188, 146, 0x40000000);
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§a✔ Профиль §e" + this.currentNick + " §aнайден!"),
                        centerX,
                        72,
                        0x55FF55
                    );
                    if (profile != null) {
                        String line1 = "§7UUID: §8" + profile.uuid() + " §7| Прем: §f" + profile.premium();
                        String line2 = "§9VK: §b" + profile.vk() + " §8| §9TG: §b" + profile.telegram() + " §8| §9DS: §b" + profile.discord();
                        context.method_27535(this.field_22793, class_2561.method_43470(line1), centerX, 87, 0xFFFFFF);
                        context.method_27535(this.field_22793, class_2561.method_43470(line2), centerX, 101, 0xFFFFFF);
                    }
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§e⏳ Загрузка истории входов... §8(авто-переход по ► Посмотреть историю ◄)"),
                        centerX,
                        122,
                        0xFFFF55
                    );
                } else if (this.queryPending || status == IpLookupManager.LookupStatus.WAITING_INFO) {
                    long elapsed = System.currentTimeMillis() - this.queryStartTime;
                    int remainingSec = Math.max(1, (int) Math.ceil((QUERY_TIMEOUT_MS - elapsed) / 1000.0));
                    context.method_25294(centerX - 188, 70, centerX + 188, 125, 0x40000000);
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§e⏳ Запрос отправлен серверу..."),
                        centerX,
                        85,
                        0xFFFF55
                    );
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§7Ожидание ответа для §f" + this.currentNick + " §7(" + remainingSec + " сек)"),
                        centerX,
                        100,
                        0xAAAAAA
                    );
                } else if (this.queryTimedOut || status == IpLookupManager.LookupStatus.TIMED_OUT) {
                    context.method_25294(centerX - 188, 70, centerX + 188, 125, 0x40000000);
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§c⚠ Сервер не ответил за 5 секунд."),
                        centerX,
                        85,
                        0xFF5555
                    );
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§7Возможно, у вас нет прав на просмотр сессий, либо сервер не ответил на запрос."),
                        centerX,
                        100,
                        0xAAAAAA
                    );
                } else if (status == IpLookupManager.LookupStatus.NO_HISTORY) {
                    context.method_25294(centerX - 188, 70, centerX + 188, 125, 0x40000000);
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§eℹ У игрока §f" + this.currentNick + " §eнет сохраненных сессий."),
                        centerX,
                        88,
                        0xFFFF55
                    );
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§7Сервер не вернул историю входов для этого аккаунта."),
                        centerX,
                        102,
                        0xAAAAAA
                    );
                } else {
                    context.method_25294(centerX - 188, 70, centerX + 188, 125, 0x40000000);
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§7Введите никнейм и нажмите §e'Запросить' §7или клавишу §aEnter"),
                        centerX,
                        88,
                        0xAAAAAA
                    );
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§8Или нажмите §a[Тест: Odinoky] §8для мгновенной демонстрации"),
                        centerX,
                        102,
                        0x666666
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
                    headerText = "§6Входы §e" + this.currentNick + " §7(" + totalEntries + ") §8| " + social;
                } else {
                    headerText = "§6Входы игрока §e" + this.currentNick + " §7(Всего: " + totalEntries + "):";
                }

                context.method_27534(
                    this.field_22793,
                    class_2561.method_43470(headerText),
                    centerX - 185,
                    54,
                    0xFFFFFF
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
                    context.method_27534(this.field_22793, class_2561.method_43470(indexAndDate), centerX - 182, rowY + 4, 0xFFFFFF);

                    // Col 2: IP Address
                    context.method_27534(this.field_22793, class_2561.method_43470("§e" + entry.ip()), centerX - 78, rowY + 4, 0xFFFF55);

                    // Col 3: Subnet badge if shared with other sessions, otherwise session type
                    String subnet = SubnetMatcher.getSubnet24String(entry.ip());
                    boolean isDupeSubnet = highlightSubnets && subnetCounts.getOrDefault(subnet, 0) > 1;

                    if (isDupeSubnet) {
                        context.method_27534(this.field_22793, class_2561.method_43470("§d[⚡/24]"), centerX + 12, rowY + 4, 0xFFAAFF);
                    } else {
                        context.method_27534(this.field_22793, class_2561.method_43470("§8[" + entry.sessionType() + "]"), centerX + 12, rowY + 4, 0x888888);
                    }
                }

                // Page indicator between arrows
                if (maxPages > 1) {
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§7Стр. §e" + (this.currentPage + 1) + "§7/§e" + maxPages),
                        centerX - 11,
                        183,
                        0xFFFFFF
                    );
                }
            }
        } else if (this.activeTab == Tab.HISTORY) {
            List<IpHistoryManager.HistoryEntry> history = IpHistoryManager.getDetailedHistory();
            int totalEntries = history.size();

            if (history.isEmpty()) {
                context.method_27535(
                    this.field_22793,
                    class_2561.method_43470("§7История скопированных IP в этой сессии пуста."),
                    centerX,
                    95,
                    0xAAAAAA
                );
                context.method_27535(
                    this.field_22793,
                    class_2561.method_43470("§8Копируйте IP в чате или через дашборд — они сразу появятся здесь!"),
                    centerX,
                    110,
                    0x666666
                );
            } else {
                int maxPages = Math.max(1, (int) Math.ceil((double) totalEntries / ROWS_PER_PAGE));

                // Card container background
                context.method_25294(centerX - 188, 52, centerX + 188, 164, 0x40000000);

                // Table header
                context.method_27534(
                    this.field_22793,
                    class_2561.method_43470("§6История скопированных IP §7(Всего: " + totalEntries + "):"),
                    centerX - 185,
                    40,
                    0xFFFFFF
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
                    context.method_27534(this.field_22793, class_2561.method_43470("§8[" + entry.getFormattedTime() + "]"), centerX - 182, rowY + 4, 0x888888);

                    // Col 2: IP Address
                    context.method_27534(this.field_22793, class_2561.method_43470("§e" + entry.ip()), centerX - 124, rowY + 4, 0xFFFF55);

                    // Col 3: Subnet /24 micro badge
                    context.method_27534(this.field_22793, class_2561.method_43470("§b/24"), centerX - 24, rowY + 4, 0x55FFFF);
                }

                // Page indicator between arrows
                if (maxPages > 1) {
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§7Стр. §e" + (this.currentPage + 1) + "§7/§e" + maxPages),
                        centerX - 11,
                        173,
                        0xFFFFFF
                    );
                }
            }
        } else {
            int footerY = this.field_22790 - 42;
            context.method_27535(
                this.field_22793,
                class_2561.method_43470("§8Настройки сохраняются автоматически в config/ipcopy.json"),
                centerX,
                footerY,
                0x888888
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
}
