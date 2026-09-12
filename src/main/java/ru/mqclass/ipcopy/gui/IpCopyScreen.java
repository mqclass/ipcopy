package ru.mqclass.ipcopy.gui;

import net.minecraft.class_11908;
import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_332;
import net.minecraft.class_342;
import net.minecraft.class_4185;
import net.minecraft.class_437;
import ru.mqclass.ipcopy.IpCopyClient;
import ru.mqclass.ipcopy.IpCopyProcessor;
import ru.mqclass.ipcopy.config.IpCopyConfig;
import ru.mqclass.ipcopy.feedback.IpFeedback;
import ru.mqclass.ipcopy.history.IpHistoryManager;
import ru.mqclass.ipcopy.lookup.IpLookupManager;
import ru.mqclass.ipcopy.lookup.IpLookupManager.PlayerIpEntry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Interactive moderation dashboard for IP Copy by mqclass.
 * Features player IP lookup, live server response tracking, and settings.
 */
public class IpCopyScreen extends class_437 {

    private enum Tab {
        LOOKUP,
        SETTINGS
    }

    private final class_437 parent;
    private Tab activeTab = Tab.LOOKUP;
    private class_342 nickField;
    private String currentNick = "DiNoKy";

    public IpCopyScreen(class_437 parent) {
        super(class_2561.method_43470("IP Copy — Панель модератора"));
        this.parent = parent;
    }

    public IpCopyScreen(class_437 parent, String initialNick) {
        super(class_2561.method_43470("IP Copy — Панель модератора"));
        this.parent = parent;
        if (initialNick != null && !initialNick.trim().isEmpty()) {
            this.currentNick = initialNick.trim();
        }
    }

    @Override
    protected void method_25426() {
        super.method_25426();

        // Subscribe to live player lookup updates
        IpLookupManager.setUpdateListener(nick -> {
            if (nick != null && nick.equalsIgnoreCase(this.currentNick)) {
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

        // Top navigation tabs
        int tabWidth = 140;
        int tabHeight = 20;
        int tabY = 10;

        String lookupTitle = (this.activeTab == Tab.LOOKUP ? "§6§l🔍 Поиск по нику" : "§7🔍 Поиск по нику");
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(lookupTitle),
            button -> {
                this.activeTab = Tab.LOOKUP;
                this.rebuildWidgets();
            }
        ).method_46434(centerX - tabWidth - 5, tabY, tabWidth, tabHeight).method_46431());

        String settingsTitle = (this.activeTab == Tab.SETTINGS ? "§6§l⚙ Настройки" : "§7⚙ Настройки");
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(settingsTitle),
            button -> {
                this.activeTab = Tab.SETTINGS;
                this.rebuildWidgets();
            }
        ).method_46434(centerX + 5, tabY, tabWidth, tabHeight).method_46431());

        if (this.activeTab == Tab.LOOKUP) {
            setupLookupTab(centerX);
        } else {
            setupSettingsTab(centerX);
        }
    }

    private void setupLookupTab(int centerX) {
        int fieldWidth = 140;
        int btnWidth = 85;
        int testBtnWidth = 95;
        int inputY = 44;

        // Nickname text field
        this.nickField = new class_342(this.field_22793, centerX - 165, inputY, fieldWidth, 20, class_2561.method_43470("Ник игрока..."));
        this.nickField.method_1880(16);
        this.nickField.method_1852(this.currentNick);
        this.nickField.method_1863(text -> this.currentNick = text.trim());
        this.nickField.method_1890(str -> str.matches("[A-Za-z0-9_]*"));
        this.method_37063(this.nickField);

        // Search button
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§e🔍 Запросить"),
            button -> triggerPlayerQuery()
        ).method_46434(centerX - 15, inputY, btnWidth, 20).method_46431());

        // Test button
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§aТест: DiNoKy"),
            button -> {
                this.currentNick = "DiNoKy";
                if (this.nickField != null) {
                    this.nickField.method_1852("DiNoKy");
                }
                this.rebuildWidgets();
            }
        ).method_46434(centerX + 75, inputY, testBtnWidth, 20).method_46431());

        // Player IP entry rows
        List<PlayerIpEntry> entries = IpLookupManager.getEntries(this.currentNick);
        int startRowY = 86;
        int rowHeight = 24;
        int maxRows = Math.min(entries.size(), 5);

        for (int i = 0; i < maxRows; i++) {
            PlayerIpEntry entry = entries.get(i);
            int rowY = startRowY + (i * rowHeight);

            // Copy button
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§6Скоп. IP"),
                button -> {
                    copyToClipboard(entry.ip());
                    button.method_25355(class_2561.method_43470("§aСкопирован"));
                }
            ).method_46434(centerX + 65, rowY - 2, 75, 18).method_46431());

            // DupeIP button
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§bDupeIP"),
                button -> executeDupeCheck(entry.ip())
            ).method_46434(centerX + 145, rowY - 2, 55, 18).method_46431());
        }

        // Bottom actions
        int bottomY = this.field_22790 - 30;

        if (!entries.isEmpty()) {
            this.method_37063(class_4185.method_46430(
                class_2561.method_43470("§e📋 Скопировать все IP (" + entries.size() + ")"),
                button -> {
                    Set<String> uniqueIps = new LinkedHashSet<>();
                    for (PlayerIpEntry e : entries) {
                        uniqueIps.add(e.ip());
                    }
                    String joined = String.join(" ", uniqueIps);
                    copyToClipboard(joined);
                    button.method_25355(class_2561.method_43470("§aВсе скопированы!"));
                }
            ).method_46434(centerX - 150, bottomY, 190, 20).method_46431());
        }

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§fЗакрыть"),
            button -> this.method_25419()
        ).method_46434(centerX + 50, bottomY, 100, 20).method_46431());
    }

    private void setupSettingsTab(int centerX) {
        IpCopyConfig config = IpCopyConfig.getInstance();
        int btnWidth = 220;
        int btnHeight = 20;
        int startX = centerX - (btnWidth / 2);
        int startY = 46;
        int spacing = 24;

        // 1. Mod status
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getStatusText(config.enabled)),
            button -> {
                config.enabled = !config.enabled;
                IpCopyProcessor.setEnabled(config.enabled);
                button.method_25355(class_2561.method_43470(getStatusText(config.enabled)));
                IpCopyConfig.save();
            }
        ).method_46434(startX, startY, btnWidth, btnHeight).method_46431());

        // 2. Sound feedback
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getSoundText(config.soundFeedback)),
            button -> {
                config.soundFeedback = !config.soundFeedback;
                button.method_25355(class_2561.method_43470(getSoundText(config.soundFeedback)));
                IpCopyConfig.save();
            }
        ).method_46434(startX, startY + spacing, btnWidth, btnHeight).method_46431());

        // 3. Actionbar feedback
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getActionbarText(config.actionbarFeedback)),
            button -> {
                config.actionbarFeedback = !config.actionbarFeedback;
                button.method_25355(class_2561.method_43470(getActionbarText(config.actionbarFeedback)));
                IpCopyConfig.save();
            }
        ).method_46434(startX, startY + spacing * 2, btnWidth, btnHeight).method_46431());

        // 4. Session history
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
        ).method_46434(startX, startY + spacing * 3, btnWidth, btnHeight).method_46431());

        // 5. Second action (/dupeip)
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getSecondActionText(config.showSecondAction)),
            button -> {
                config.showSecondAction = !config.showSecondAction;
                button.method_25355(class_2561.method_43470(getSecondActionText(config.showSecondAction)));
                IpCopyConfig.save();
            }
        ).method_46434(startX, startY + spacing * 4, btnWidth, btnHeight).method_46431());

        // 6. Test in chat button & Clear history
        int halfWidth = (btnWidth - 4) / 2;
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§eОтправить тест"),
            button -> {
                IpCopyClient.sendTestMessage();
                button.method_25355(class_2561.method_43470("§aОтправлено!"));
            }
        ).method_46434(startX, startY + spacing * 5 + 4, halfWidth, btnHeight).method_46431());

        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§cСброс истории"),
            button -> {
                IpHistoryManager.clear();
                button.method_25355(class_2561.method_43470("§7Очищено"));
            }
        ).method_46434(startX + halfWidth + 4, startY + spacing * 5 + 4, halfWidth, btnHeight).method_46431());

        // 7. Done / Save button
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470("§aГотово"),
            button -> this.method_25419()
        ).method_46434(startX, startY + spacing * 6 + 10, btnWidth, btnHeight).method_46431());
    }

    private void triggerPlayerQuery() {
        if (this.currentNick == null || this.currentNick.isEmpty()) {
            return;
        }
        boolean sent = IpLookupManager.queryPlayer(this.currentNick);
        if (!sent) {
            // If offline, check if test data or notify
            if (this.currentNick.equalsIgnoreCase("DiNoKy")) {
                this.rebuildWidgets();
            }
        } else {
            this.rebuildWidgets();
        }
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
            client.method_1562().method_45730(cmd);
            if (client.field_1724 != null) {
                client.field_1724.method_7353(class_2561.method_43470("§b[IPCopy] §7Команда отправлена: §f/" + cmd), true);
            }
        }
    }

    @Override
    public boolean method_25404(class_11908 keyEvent) {
        if (keyEvent != null && (keyEvent.comp_4795() == 257 || keyEvent.comp_4795() == 335)) {
            if (this.activeTab == Tab.LOOKUP && this.nickField != null && this.nickField.method_25370()) {
                triggerPlayerQuery();
                return true;
            }
        }
        return super.method_25404(keyEvent);
    }

    @Override
    public void method_25394(class_332 context, int mouseX, int mouseY, float delta) {
        super.method_25394(context, mouseX, mouseY, delta);

        int centerX = this.field_22789 / 2;

        if (this.activeTab == Tab.LOOKUP) {
            List<PlayerIpEntry> entries = IpLookupManager.getEntries(this.currentNick);

            if (entries.isEmpty()) {
                if (IpLookupManager.isQueryPending(this.currentNick)) {
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§e⏳ Запрос отправлен серверу. Ожидание ответа для §f" + this.currentNick + "§e..."),
                        centerX,
                        105,
                        0xFFFFFF
                    );
                } else {
                    context.method_27535(
                        this.field_22793,
                        class_2561.method_43470("§7Введите никнейм и нажмите §e'Запросить' §7или кнопку §a'Тест: DiNoKy'"),
                        centerX,
                        105,
                        0xAAAAAA
                    );
                }
            } else {
                // Table header
                context.method_27534(
                    this.field_22793,
                    class_2561.method_43470("§6Найденные входы игрока §e" + this.currentNick + " §7(" + entries.size() + "):"),
                    centerX - 195,
                    70,
                    0xFFFFFF
                );

                int startRowY = 86;
                int rowHeight = 24;
                int maxRows = Math.min(entries.size(), 5);

                for (int i = 0; i < maxRows; i++) {
                    PlayerIpEntry entry = entries.get(i);
                    int rowY = startRowY + (i * rowHeight);

                    String text = "§f" + (i + 1) + ". §a" + entry.date() + " §7- §e" + entry.ip() + " §8(" + entry.sessionType() + ")";
                    context.method_27534(
                        this.field_22793,
                        class_2561.method_43470(text),
                        centerX - 195,
                        rowY + 2,
                        0xFFFFFF
                    );
                }
            }
        } else {
            // Settings footer
            int footerY = this.field_22790 - 24;
            context.method_27535(
                this.field_22793,
                class_2561.method_43470("§8История IP хранится только в RAM и удаляется при выходе с сервера"),
                centerX,
                footerY,
                0x888888
            );
        }
    }

    @Override
    public void method_25419() {
        IpLookupManager.setUpdateListener(null);
        IpCopyConfig.save();
        if (this.field_22787 != null) {
            this.field_22787.method_1507(this.parent);
        }
    }

    private static String getStatusText(boolean state) {
        return "§7Статус мода: " + (state ? "§aВКЛЮЧЕН" : "§cВЫКЛЮЧЕН");
    }

    private static String getSoundText(boolean state) {
        return "§7Звук при клике: " + (state ? "§aВКЛ" : "§cВЫКЛ");
    }

    private static String getActionbarText(boolean state) {
        return "§7Уведомление в Actionbar: " + (state ? "§aВКЛ" : "§cВЫКЛ");
    }

    private static String getHistoryText(boolean state) {
        return "§7История сессии: " + (state ? "§aВКЛ" : "§cВЫКЛ");
    }

    private static String getSecondActionText(boolean state) {
        return "§7Кнопка /dupeip: " + (state ? "§aВКЛ" : "§cВЫКЛ");
    }
}
