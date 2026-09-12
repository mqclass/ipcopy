package ru.mqclass.ipcopy;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.class_124;
import net.minecraft.class_2558;
import net.minecraft.class_2561;
import net.minecraft.class_2568;
import net.minecraft.class_2583;
import net.minecraft.class_310;
import net.minecraft.class_5250;
import ru.mqclass.ipcopy.config.IpCopyConfig;
import ru.mqclass.ipcopy.gui.IpCopyScreen;
import ru.mqclass.ipcopy.history.IpHistoryManager;
import ru.mqclass.ipcopy.lookup.IpLookupManager;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Main client initializer for the IP Copy mod.
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class IpCopyClient implements ClientModInitializer {

    public static final String MOD_ID = "ipcopy";
    public static final String MOD_NAME = "IP Copy";
    public static final String VERSION = "1.2.0";

    private static final String TEST_PLAYER = "DiNoKy";
    private static final Pattern NICKNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");

    @Override
    public void onInitializeClient() {
        // Load configuration from .minecraft/config/ipcopy.json
        IpCopyConfig.load();

        // Wipe session history when leaving a world or disconnecting from server
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            IpHistoryManager.clear();
            IpLookupManager.clearCache();
        });

        // Register game message modifier (handles anticheat alerts, server logs, command outputs)
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (overlay || message == null) {
                return message;
            }
            return IpCopyProcessor.processMessage(message);
        });

        // Dot commands are handled locally and never sent as chat messages to the server.
        ClientSendMessageEvents.ALLOW_CHAT.register(IpCopyClient::handleDotCommand);

        // Register client commands under /ipcopy and /apf
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("ipcopy")
                .executes(context -> {
                    context.getSource().sendFeedback(helpMessage());
                    return 1;
                })
                .then(ClientCommandManager.literal("gui")
                    .executes(context -> {
                        openConfigGui(null);
                        return 1;
                    })
                    .then(ClientCommandManager.argument("nick", StringArgumentType.word())
                        .executes(context -> {
                            String nick = StringArgumentType.getString(context, "nick");
                            openConfigGui(nick);
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("test")
                    .executes(context -> {
                        sendTestMessage();
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("toggle")
                    .executes(context -> {
                        context.getSource().sendFeedback(toggleMessage());
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("history")
                    .executes(context -> {
                        context.getSource().sendFeedback(historyMessage());
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("clear")
                    .executes(context -> {
                        IpHistoryManager.clear();
                        context.getSource().sendFeedback(class_2561.method_43470("§6[IPCopy] §aИстория текущей сессии очищена."));
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("reload")
                    .executes(context -> {
                        IpCopyConfig.load();
                        context.getSource().sendFeedback(class_2561.method_43470("§6[IPCopy] §aКонфигурация перезагружена с диска."));
                        return 1;
                    })
                )
            );

            dispatcher.register(ClientCommandManager.literal("apf")
                .then(ClientCommandManager.argument("nick", StringArgumentType.word())
                    .executes(context -> {
                        String nick = StringArgumentType.getString(context, "nick");
                        executeAuthPlayer(nick);
                        return 1;
                    })
                )
            );
        });

        System.out.println("[IPCopy] IP Copy v" + VERSION + " by mqclass successfully initialized for Fabric 1.21.11!");
    }

    public static boolean handleDotCommand(String rawMessage) {
        if (rawMessage == null) {
            return true;
        }

        String input = rawMessage.trim();
        if (!input.startsWith(".")) {
            return true;
        }
        String[] parts = input.split("\\s+");
        String command = parts[0].toLowerCase(Locale.ROOT);

        if (command.equals(".ipcopy")) {
            if (parts.length == 1) {
                showLocalMessage(helpMessage());
            } else if (parts.length >= 2 && parts[1].equalsIgnoreCase("gui")) {
                String nick = parts.length >= 3 ? parts[2] : null;
                openConfigGui(nick);
            } else if (parts.length == 2 && parts[1].equalsIgnoreCase("test")) {
                sendTestMessage();
            } else if (parts.length == 2 && parts[1].equalsIgnoreCase("toggle")) {
                showLocalMessage(toggleMessage());
            } else if (parts.length == 2 && parts[1].equalsIgnoreCase("history")) {
                showLocalMessage(historyMessage());
            } else if (parts.length == 2 && parts[1].equalsIgnoreCase("clear")) {
                IpHistoryManager.clear();
                showLocalMessage(class_2561.method_43470("§6[IPCopy] §aИстория текущей сессии очищена."));
            } else if (parts.length == 2 && parts[1].equalsIgnoreCase("reload")) {
                IpCopyConfig.load();
                showLocalMessage(class_2561.method_43470("§6[IPCopy] §aКонфигурация перезагружена с диска."));
            } else {
                showLocalMessage(class_2561.method_43470("§cИспользование: .ipcopy [gui <ник>|test|toggle|history|clear|reload]"));
            }
            return false;
        }

        if (command.equals(".apf")) {
            if (parts.length != 2 || !NICKNAME_PATTERN.matcher(parts[1]).matches()) {
                showLocalMessage(class_2561.method_43470("§cИспользование: .apf <ник>  §7(ник 1–16 символов: A-Z, 0-9, _ )"));
                return false;
            }

            executeAuthPlayer(parts[1]);
            return false;
        }

        return true;
    }

    private static void executeAuthPlayer(String nick) {
        class_310 client = class_310.method_1551();
        if (client == null || client.field_1724 == null || client.method_1562() == null) {
            showLocalMessage(class_2561.method_43470("§cНужно подключиться к серверу."));
        } else {
            IpLookupManager.queryPlayer(nick);
            client.method_1562().method_45730("auth player " + nick + " info");
        }
    }

    public static void openConfigGui() {
        openConfigGui(null);
    }

    public static void openConfigGui(String initialNick) {
        class_310 client = class_310.method_1551();
        if (client != null) {
            client.execute(() -> client.method_1507(new IpCopyScreen(client.field_1755, initialNick)));
        }
    }

    private static class_2561 helpMessage() {
        boolean enabled = IpCopyProcessor.isEnabled();

        class_5250 title = class_2561.method_43470(
            "§6§l[IPCopy] " + (enabled ? "§aМод включен" : "§cМод выключен") + " §7(by mqclass v" + VERSION + ")\n"
        );

        // Clickable actions
        class_5250 guiBtn = class_2561.method_43470(" §6[🔍 Панель поиска IP] ")
            .method_10862(class_2583.field_24360
                .method_10977(class_124.field_1065)
                .method_10958(new class_2558.class_10609("/ipcopy gui"))
                .method_10949(new class_2568.class_10613(class_2561.method_43470("§eНажмите, чтобы открыть меню поиска и настроек"))));

        class_5250 testBtn = class_2561.method_43470(" §a[▶ Запустить тест]\n")
            .method_10862(class_2583.field_24360
                .method_10977(class_124.field_1060)
                .method_10958(new class_2558.class_10609("/ipcopy test"))
                .method_10949(new class_2568.class_10613(class_2561.method_43470("§eНажмите, чтобы отправить тестовое сообщение"))));

        class_5250 commands = class_2561.method_43470(
            "§e» §7.ipcopy gui [ник] §f— поиск IP по нику и настройки в GUI\n" +
            "§e» §7.ipcopy test §f— тест с тремя IP игрока §e" + TEST_PLAYER + "§7\n" +
            "§e» §7.ipcopy toggle §f— быстрое вкл/выкл мода\n" +
            "§e» §7.ipcopy history §f— последние скопированные IP сессии\n" +
            "§e» §7.apf <ник> §f— отправить §7/auth player <ник> info"
        );

        return title.method_10852(guiBtn).method_10852(testBtn).method_10852(commands);
    }

    private static class_2561 toggleMessage() {
        boolean newState = !IpCopyProcessor.isEnabled();
        IpCopyProcessor.setEnabled(newState);
        return class_2561.method_43470(
            "§6[IPCopy] §7Статус мода: " + (newState ? "§aВКЛЮЧЕН" : "§cВЫКЛЮЧЕН")
        );
    }

    private static class_2561 historyMessage() {
        List<String> history = IpHistoryManager.getHistory();
        if (history.isEmpty()) {
            return class_2561.method_43470("§6[IPCopy] §7История IP в текущей сессии пуста (или выключена).");
        }

        class_5250 header = class_2561.method_43470("§6§m-----§r §6Последние IP сессии §7(RAM) §6§m-----§r\n");
        for (int i = 0; i < history.size(); i++) {
            String ip = history.get(i);
            class_5250 line = class_2561.method_43470("§7" + (i + 1) + ". §e" + ip + " ");
            line.method_10852(IpCopyProcessor.createIpButton(ip, false));
            if (i < history.size() - 1) {
                line.method_27693("\n");
            }
            header.method_10852(line);
        }
        return header;
    }

    public static void sendTestMessage() {
        class_5250 testMessage = class_2561.method_43470(
            "§6§m-----§r §6Входы игрока §e" + TEST_PLAYER + " §7(3/3) §6§m-----§r\n" +
            "§f├ §a02-09-2026 23:02 §7- §b185.230.240.209 §7- §9session\n" +
            "§f├ §a05-09-2026 21:45 §7- §b94.25.180.12 §7- §9session\n" +
            "§f└ §a11-09-2026 23:08 §7- §b178.62.204.18 §7- §9session"
        );
        showLocalMessage(IpCopyProcessor.processPreviewMessage(testMessage));
    }

    private static void showLocalMessage(class_2561 message) {
        class_310 client = class_310.method_1551();
        if (message != null && client != null && client.field_1724 != null) {
            client.field_1724.method_7353(message, false);
        }
    }

    public static void showSpaceModerationHelpAddon() {
        showLocalMessage(class_2561.method_43470(
            "\n§6IP Copy §7by mqclass (v" + VERSION + ")\n" +
            " §f▪ §6.ipcopy gui [ник] §8– §fпоиск IP по нику и окно настроек\n" +
            " §f▪ §6.ipcopy test §8– §fтест трёх IP и кнопок копирования\n" +
            " §f▪ §6.ipcopy toggle §8– §fвключить или выключить IP Copy\n" +
            " §f▪ §6.ipcopy history §8– §fистория скопированных IP\n" +
            " §f▪ §6.apf §7<ник> §8– §fоткрыть /auth player <ник> info"
        ));
    }
}
