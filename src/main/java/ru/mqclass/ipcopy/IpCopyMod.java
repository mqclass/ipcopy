package ru.mqclass.ipcopy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.class_2558;
import net.minecraft.class_2561;
import net.minecraft.class_2568;
import net.minecraft.class_2583;
import net.minecraft.class_310;
import net.minecraft.class_5250;
import ru.mqclass.ipcopy.forensics.IpForensicEngine;
import ru.mqclass.ipcopy.hud.ForensicHudOverlay;
import ru.mqclass.ipcopy.risk.AsnRiskLookupService;
import ru.mqclass.ipcopy.scanner.ScanQueueManager;
import ru.mqclass.ipcopy.scraper.SessionCaptureFSM;

import java.util.Collection;
import java.util.Set;

/**
 * Client entrypoint for IP Copy Forensic & Moderation Suite.
 * Registers /ipcopy cancel, /ipcopy dump, /ipcopy scan commands and hooks Forensic HUD overlay.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class IpCopyMod implements ClientModInitializer {

    private static boolean initialized = false;

    @Override
    public void onInitializeClient() {
        if (initialized) return;
        initialized = true;

        // 1. Register modern Glassmorphic Forensic HUD overlay
        HudRenderCallback.EVENT.register(ForensicHudOverlay::render);

        // 2. Disconnect cleanup hooks
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SessionCaptureFSM.getInstance().cancel();
            AsnRiskLookupService.getInstance().clearCache();
        });

        // 3. Register client commands: /ipcopy cancel, /ipcopy dump, /ipcopy scan
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            registerCommands(dispatcher);
        });

        System.out.println("[IPCopy] IpCopyMod forensic subsystem initialized successfully by mqclass!");
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("ipcopy")
            .then(ClientCommandManager.literal("cancel")
                .executes(context -> {
                    SessionCaptureFSM.getInstance().cancel();
                    ScanQueueManager.getInstance().cancelScan();
                    ru.mqclass.ipcopy.network.CommandDispatcher.getInstance().clearQueue();

                    context.getSource().sendFeedback(class_2561.method_43470(
                        "§6[IPCopy] §c✖ Все активные очереди проверки и сбор сессий отменены."
                    ));
                    return 1;
                })
            )
            .then(ClientCommandManager.literal("dump")
                .executes(context -> {
                    return executeDumpCommand(context.getSource());
                })
            )
        );
    }

    private static int executeDumpCommand(FabricClientCommandSource source) {
        SessionCaptureFSM fsm = SessionCaptureFSM.getInstance();
        Set<String> rawIps = fsm.getCapturedRawIps();
        Collection<IpForensicEngine.SubnetCluster> clusters = fsm.getAnalyzedClusters().values();
        String nick = fsm.getCurrentTargetNick();

        if (rawIps.isEmpty() && clusters.isEmpty()) {
            source.sendFeedback(class_2561.method_43470(
                "§6[IPCopy] §eНет захваченных данных сессий для дампа. Просмотрите историю входов игрока или используйте §f/ipcopy scan <ник>§e."
            ));
            return 0;
        }

        if (nick == null || nick.isEmpty()) {
            nick = "Target";
        }

        String report = IpForensicEngine.buildDiscordReport(
            nick,
            fsm.getTotalIngestedSessions(),
            clusters,
            fsm.getOverallRiskScore()
        );

        // Copy to system clipboard
        class_310 client = class_310.method_1551();
        if (client != null && client.field_1774 != null) {
            client.field_1774.method_1455(report);
        }

        class_5250 feedback = class_2561.method_43470(
            "§6[IPCopy] §a✔ Отчёт судебной экспертизы скопирован в буфер обмена!\n" +
            "§7Игрок: §f" + nick + " §7| Сессий: §e" + fsm.getTotalIngestedSessions() +
            " §7| Подсетей /24: §b" + clusters.size() +
            " §7| Риск: §c" + fsm.getOverallRiskScore() + "%\n"
        );

        class_5250 copyBtn = class_2561.method_43470("§e[📋 СКОПИРОВАТЬ ПОВТОРНО]")
            .method_10862(class_2583.field_24360
                .method_10958(new class_2558.class_10606(report))
                .method_10949(new class_2568.class_10613(class_2561.method_43470("§7Нажмите, чтобы скопировать Markdown отчёт"))));

        source.sendFeedback(feedback.method_10852(copyBtn));
        return 1;
    }
}
