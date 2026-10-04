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
import ru.mqclass.ipcopy.compat.SpaceModerationAuthFix;
import ru.mqclass.ipcopy.config.IpCopyConfig;
import ru.mqclass.ipcopy.gui.IpCopyScreen;
import ru.mqclass.ipcopy.history.IpHistoryManager;
import ru.mqclass.ipcopy.keybind.IpKeyBindings;
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
    public static final String VERSION = "1.5.0";

    private static final String TEST_PLAYER = "DiNoKy";
    private static final Pattern NICKNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");

    @Override
    public void onInitializeClient() {
        // Initialize client stability & SpaceModeration local auth resolution
        SpaceModerationAuthFix.init();

        // Load configuration from .minecraft/config/ipcopy.json
        IpCopyConfig.load();

        // Register native keybindings (default key 'I' for instant GUI access)
        IpKeyBindings.register();

        // Initialize Anti-AFK keepalive manager
        ru.mqclass.ipcopy.afk.AntiAfkManager.getInstance().init();

        // Register real-time HUD scan & forensic overlays
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(ru.mqclass.ipcopy.hud.ScanHudOverlay::render);
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(ru.mqclass.ipcopy.hud.ForensicHudOverlay::render);

        // Wipe session history and queue when leaving a world or disconnecting from server
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ru.mqclass.ipcopy.network.CommandDispatcher.getInstance().clearQueue();
            ru.mqclass.ipcopy.scraper.SessionCaptureFSM.getInstance().cancelHard();
            ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().cancelScan();
            ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().clearCache();
            IpLookupManager.clearCache();
            IpHistoryManager.clear();
        });

        // Register game message modifier (handles anticheat alerts, server logs, command outputs)
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (overlay || message == null) {
                return message;
            }
            return IpCopyProcessor.processMessage(message);
        });

        // Dot commands are handled locally and never sent as chat messages to the server.
        ClientSendMessageEvents.ALLOW_CHAT.register(raw -> {
            ru.mqclass.ipcopy.afk.AntiAfkManager.getInstance().recordPlayerAction();
            return handleDotCommand(raw);
        });

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
                .then(ClientCommandManager.literal("full")
                    .then(ClientCommandManager.argument("nick", StringArgumentType.word())
                        .executes(context -> {
                            String nick = StringArgumentType.getString(context, "nick");
                            startExpressAuditCommand(nick);
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("express")
                    .then(ClientCommandManager.argument("nick", StringArgumentType.word())
                        .executes(context -> {
                            String nick = StringArgumentType.getString(context, "nick");
                            startExpressAuditCommand(nick);
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("scan")
                    .then(ClientCommandManager.argument("target", StringArgumentType.greedyString())
                        .executes(context -> {
                            String arg = StringArgumentType.getString(context, "target");
                            startScanCommand(arg);
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("pause")
                    .executes(context -> {
                        ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().pauseScan();
                        context.getSource().sendFeedback(class_2561.method_43470("§6[IPCopy] §e⏸ Сканирование поставлено на паузу."));
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("resume")
                    .executes(context -> {
                        ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().resumeScan();
                        context.getSource().sendFeedback(class_2561.method_43470("§6[IPCopy] §a▶ Сканирование возобновлено."));
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("cancel")
                    .executes(context -> {
                        ru.mqclass.ipcopy.scraper.SessionCaptureFSM.getInstance().cancel();
                        ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().cancelScan();
                        ru.mqclass.ipcopy.network.CommandDispatcher.getInstance().clearQueue();
                        context.getSource().sendFeedback(class_2561.method_43470("§6[IPCopy] §c✖ Все активные очереди проверки и захват сессий отменены."));
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("dump")
                    .executes(context -> {
                        return executeDumpCommand(context.getSource());
                    })
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

    private static long lastDotCommandTime = 0L;
    private static String lastDotCommandMsg = "";

    public static boolean handleDotCommand(String rawMessage) {
        if (rawMessage == null) {
            return true;
        }

        String input = rawMessage.trim();
        if (!input.startsWith(".")) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (now - lastDotCommandTime < 150L && input.equalsIgnoreCase(lastDotCommandMsg)) {
            return false;
        }
        lastDotCommandTime = now;
        lastDotCommandMsg = input;

        String[] parts = input.split("\\s+");
        String command = parts[0].toLowerCase(Locale.ROOT);

        if (command.equals(".ipcopy")) {
            if (parts.length == 1) {
                showLocalMessage(helpMessage());
            } else if (parts.length >= 2 && parts[1].equalsIgnoreCase("gui")) {
                String nick = parts.length >= 3 ? parts[2] : null;
                openConfigGui(nick);
            } else if (parts.length >= 2 && (parts[1].equalsIgnoreCase("full") || parts[1].equalsIgnoreCase("express"))) {
                if (parts.length >= 3) {
                    startExpressAuditCommand(parts[2].trim());
                } else {
                    showLocalMessage(class_2561.method_43470("§cИспользование: .ipcopy full <ник> §7— 1-Click экспресс-аудит под ключ"));
                }
            } else if (parts.length >= 2 && parts[1].equalsIgnoreCase("scan")) {
                if (parts.length >= 3) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 2; i < parts.length; i++) {
                        if (i > 2) sb.append(" ");
                        sb.append(parts[i]);
                    }
                    startScanCommand(sb.toString());
                } else {
                    showLocalMessage(class_2561.method_43470("§cИспользование: .ipcopy scan <ip1,ip2,...|ник> [ник]"));
                }
            } else if (parts.length == 2 && parts[1].equalsIgnoreCase("pause")) {
                ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().pauseScan();
                showLocalMessage(class_2561.method_43470("§6[IPCopy] §e⏸ Сканирование поставлено на паузу."));
            } else if (parts.length == 2 && parts[1].equalsIgnoreCase("resume")) {
                ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().resumeScan();
                showLocalMessage(class_2561.method_43470("§6[IPCopy] §a▶ Сканирование возобновлено."));
            } else if (parts.length == 2 && parts[1].equalsIgnoreCase("dump")) {
                executeDumpCommand(null);
            } else if (parts.length == 2 && parts[1].equalsIgnoreCase("cancel")) {
                ru.mqclass.ipcopy.scraper.SessionCaptureFSM.getInstance().cancel();
                ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().cancelScan();
                ru.mqclass.ipcopy.network.CommandDispatcher.getInstance().clearQueue();
                showLocalMessage(class_2561.method_43470("§6[IPCopy] §c✖ Все активные очереди проверки и сбор сессий отменены."));
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
                showLocalMessage(class_2561.method_43470("§cИспользование: .ipcopy [gui <ник>|full <ник>|scan <ip1,ip2,...|ник>|dump|pause|resume|cancel|test|toggle|history|clear|reload]"));
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

    public static void startExpressAuditCommand(String nick) {
        if (nick == null || nick.trim().isEmpty()) {
            showLocalMessage(class_2561.method_43470("§cУкажите ник игрока: .ipcopy full <ник>"));
            return;
        }
        String clean = IpLookupManager.stripColorCodes(nick).trim();
        clean = clean.replaceAll("[^A-Za-z0-9_]", "");
        if (clean.isEmpty()) return;
        showLocalMessage(class_2561.method_43470("§6[IPCopy] §e🚀 Запуск 1-Click экспресс-аудита для игрока §f" + clean + "§e..."));
        ru.mqclass.ipcopy.lookup.IpLookupManager.startExpressFullAudit(clean);
    }

    public static void startScanCommand(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            showLocalMessage(class_2561.method_43470("§cИспользование: .ipcopy scan <ip1,ip2,...|ник> [ник]"));
            return;
        }

        String[] parts = raw.trim().split("\\s+");
        String ipsOrNick = parts[0];
        String targetNick = parts.length > 1 ? parts[1] : null;

        java.util.List<String> extracted = new java.util.ArrayList<>();
        if (ipsOrNick.contains(",")) {
            for (String p : ipsOrNick.split(",")) {
                String ip = p.trim();
                if (IpCopyProcessor.isValidIp(ip) && !extracted.contains(ip)) {
                    extracted.add(ip);
                }
            }
        } else if (IpCopyProcessor.isValidIp(ipsOrNick)) {
            extracted.add(ipsOrNick);
        }

        if (extracted.isEmpty()) {
            String nick = ipsOrNick;
            IpLookupManager.PlayerLookupData data = IpLookupManager.getData(nick);
            if (data != null && !data.entries.isEmpty()) {
                extracted = data.getUniqueIps();
                targetNick = nick;
            } else {
                showLocalMessage(class_2561.method_43470("§6[IPCopy] §eЗагружаю список IP для §f" + nick + "§e..."));
                ru.mqclass.ipcopy.scraper.SessionCaptureFSM.getInstance().prepareScanForNick(nick);
                IpLookupManager.queryPlayer(nick);
                IpLookupManager.setUpdateListener(updatedNick -> {
                    if (updatedNick != null && updatedNick.equalsIgnoreCase(nick)) {
                        IpLookupManager.PlayerLookupData d = IpLookupManager.getData(nick);
                        if (d != null && (!d.entries.isEmpty() || !d.allSessions.isEmpty())) {
                            IpLookupManager.setUpdateListener(null);
                            List<String> unique = d.getUniqueIps();
                            if (unique.isEmpty()) {
                                showLocalMessage(class_2561.method_43470("§6[IPCopy] §cУ игрока §f" + nick + " §cне найдено IP-адресов."));
                            } else {
                                ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().startScan(
                                    nick, unique, IpCopyConfig.getInstance().secondActionCommand
                                );
                            }
                        }
                    }
                });
                return;
            }
        }

        if (targetNick == null) {
            targetNick = "Player";
        }

        ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().startScan(
            targetNick, extracted, IpCopyConfig.getInstance().secondActionCommand
        );
    }

    private static void executeAuthPlayer(String nick) {
        class_310 client = class_310.method_1551();
        if (client == null || client.field_1724 == null || client.method_1562() == null) {
            showLocalMessage(class_2561.method_43470("§cНужно подключиться к серверу."));
        } else {
            ru.mqclass.ipcopy.scraper.SessionCaptureFSM.getInstance().prepareScanForNick(nick);
            IpLookupManager.queryPlayer(nick);
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
            "§e» §7Клавиша [I] §f— открыть панель IP Copy в 1 клик\n" +
            "§e» §7.ipcopy full <ник> §f— 1-Click экспресс-проверка под ключ\n" +
            "§e» §7.ipcopy gui [ник] §f— поиск IP по нику и настройки в GUI\n" +
            "§e» §7.ipcopy test §f— тест с тремя IP игрока §e" + TEST_PLAYER + "§7\n" +
            "§e» §7.ipcopy toggle §f— быстрое вкл/выкл мода\n" +
            "§e» §7.ipcopy history §f— последние скопированные IP сессии\n" +
            "§e» §7.apf <ник> §f— быстрый поиск сессий игрока"
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

    private static int executeDumpCommand(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        ru.mqclass.ipcopy.scraper.SessionCaptureFSM fsm = ru.mqclass.ipcopy.scraper.SessionCaptureFSM.getInstance();
        java.util.Set<String> rawIps = fsm.getCapturedRawIps();
        java.util.Collection<ru.mqclass.ipcopy.forensics.IpForensicEngine.SubnetCluster> clusters = fsm.getAnalyzedClusters().values();
        String nick = fsm.getCurrentTargetNick();

        if (rawIps.isEmpty() && clusters.isEmpty()) {
            String activeNick = ru.mqclass.ipcopy.lookup.IpLookupManager.getActiveQueryNick();
            if (activeNick != null) {
                ru.mqclass.ipcopy.lookup.IpLookupManager.PlayerLookupData lastData = ru.mqclass.ipcopy.lookup.IpLookupManager.getData(activeNick);
                if (lastData != null && !lastData.entries.isEmpty()) {
                    nick = lastData.nick;
                    java.util.Map<String, ru.mqclass.ipcopy.forensics.IpForensicEngine.SubnetCluster> clMap =
                        ru.mqclass.ipcopy.forensics.IpForensicEngine.clusterSubnets(lastData.getUniqueIps());
                    clusters = clMap.values();
                }
            }
        }

        if (clusters.isEmpty() && rawIps.isEmpty()) {
            class_5250 emptyMsg = class_2561.method_43470("§6[IPCopy] §eНет захваченных данных сессий для дампа. Запустите §f.ipcopy scan <ник>§e.");
            if (source != null) source.sendFeedback(emptyMsg); else showLocalMessage(emptyMsg);
            return 0;
        }

        if (nick == null || nick.isEmpty()) nick = "Target";

        String report = ru.mqclass.ipcopy.forensics.IpForensicEngine.buildDiscordReport(
            nick,
            fsm.getTotalIngestedSessions() > 0 ? fsm.getTotalIngestedSessions() : clusters.size(),
            clusters,
            fsm.getOverallRiskScore()
        );

        class_310 client = class_310.method_1551();
        if (client != null && client.field_1774 != null) {
            client.field_1774.method_1455(report);
        }

        class_5250 feedback = class_2561.method_43470(
            "§6[IPCopy] §a✔ Отчёт судебной экспертизы скопирован в буфер обмена!\n" +
            "§7Игрок: §f" + nick + " §7| Подсетей /24: §b" + clusters.size() + "\n"
        );
        class_5250 copyBtn = class_2561.method_43470("§e[📋 СКОПИРОВАТЬ ПОВТОРНО]")
            .method_10862(class_2583.field_24360
                .method_10958(new class_2558.class_10606(report))
                .method_10949(new class_2568.class_10613(class_2561.method_43470("§7Скопировать отчет Markdown"))));

        class_5250 combined = feedback.method_10852(copyBtn);
        if (source != null) source.sendFeedback(combined); else showLocalMessage(combined);
        return 1;
    }

    public static void showSpaceModerationHelpAddon() {
        showLocalMessage(class_2561.method_43470(
            "\n§6IP Copy §7by mqclass (v" + VERSION + ")\n" +
            " §f▪ §6.ipcopy gui [ник] §8– §fпоиск IP по нику и окно настроек\n" +
            " §f▪ §6.ipcopy scan <ник> §8– §fавтоматическое сканирование всех страниц и проверка подсетей\n" +
            " §f▪ §6.ipcopy dump §8– §fэкспорт Discord-отчёта в буфер обмена\n" +
            " §f▪ §6.ipcopy cancel §8– §fсброс очередей и авто-сбора\n" +
            " §f▪ §6.ipcopy test §8– §fтест трёх IP и кнопок копирования\n" +
            " §f▪ §6.ipcopy toggle §8– §fвключить или выключить IP Copy\n" +
            " §f▪ §6.ipcopy history §8– §fистория скопированных IP\n" +
            " §f▪ §6.apf §7<ник> §8– §fбыстрый поиск сессий игрока"
        ));
    }
}
