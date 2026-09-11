package ru.mqclass.ipcopy.gui;

import net.minecraft.class_2561;
import net.minecraft.class_332;
import net.minecraft.class_4185;
import net.minecraft.class_437;
import ru.mqclass.ipcopy.IpCopyClient;
import ru.mqclass.ipcopy.IpCopyProcessor;
import ru.mqclass.ipcopy.config.IpCopyConfig;
import ru.mqclass.ipcopy.history.IpHistoryManager;

/**
 * Native settings screen for IP Copy mod.
 * Extends net.minecraft.client.gui.screen.Screen (class_437).
 * Pure vanilla styling, zero heavy dependencies.
 */
public class IpCopyScreen extends class_437 {

    private final class_437 parent;

    public IpCopyScreen(class_437 parent) {
        super(class_2561.method_43470("IP Copy - Настройки"));
        this.parent = parent;
    }

    @Override
    protected void method_25426() {
        super.method_25426();

        IpCopyConfig config = IpCopyConfig.getInstance();
        int centerX = this.field_22789 / 2;
        int btnWidth = 220;
        int btnHeight = 20;
        int startX = centerX - (btnWidth / 2);
        int startY = 52;
        int spacing = 24;

        // 1. Mod status toggle button
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getStatusText(config.enabled)),
            button -> {
                config.enabled = !config.enabled;
                IpCopyProcessor.setEnabled(config.enabled);
                button.method_25355(class_2561.method_43470(getStatusText(config.enabled)));
                IpCopyConfig.save();
            }
        ).method_46434(startX, startY, btnWidth, btnHeight).method_46431());

        // 2. Sound feedback toggle button
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getSoundText(config.soundFeedback)),
            button -> {
                config.soundFeedback = !config.soundFeedback;
                button.method_25355(class_2561.method_43470(getSoundText(config.soundFeedback)));
                IpCopyConfig.save();
            }
        ).method_46434(startX, startY + spacing, btnWidth, btnHeight).method_46431());

        // 3. Actionbar feedback toggle button
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getActionbarText(config.actionbarFeedback)),
            button -> {
                config.actionbarFeedback = !config.actionbarFeedback;
                button.method_25355(class_2561.method_43470(getActionbarText(config.actionbarFeedback)));
                IpCopyConfig.save();
            }
        ).method_46434(startX, startY + spacing * 2, btnWidth, btnHeight).method_46431());

        // 4. Session history toggle button
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

        // 5. Second action button toggle (/dupeip)
        this.method_37063(class_4185.method_46430(
            class_2561.method_43470(getSecondActionText(config.showSecondAction)),
            button -> {
                config.showSecondAction = !config.showSecondAction;
                button.method_25355(class_2561.method_43470(getSecondActionText(config.showSecondAction)));
                IpCopyConfig.save();
            }
        ).method_46434(startX, startY + spacing * 4, btnWidth, btnHeight).method_46431());

        // 6. Test in chat button & Clear history (half width side by side)
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

    @Override
    public void method_25394(class_332 context, int mouseX, int mouseY, float delta) {
        super.method_25394(context, mouseX, mouseY, delta);

        int centerX = this.field_22789 / 2;

        // Title and subtitle
        context.method_27535(
            this.field_22793,
            class_2561.method_43470("§6§lIP Copy §7— Настройки §8(v1.1.0 by mqclass)"),
            centerX,
            16,
            0xFFFFFF
        );

        context.method_27535(
            this.field_22793,
            class_2561.method_43470("§7Быстрое копирование IP в стиле SpaceModeration"),
            centerX,
            30,
            0xAAAAAA
        );

        // Footer info notes
        int footerY = this.field_22790 - 24;
        context.method_27535(
            this.field_22793,
            class_2561.method_43470("§8История IP хранится только в RAM и удаляется при выходе с сервера"),
            centerX,
            footerY,
            0x888888
        );
    }

    @Override
    public void method_25419() {
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
