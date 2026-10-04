package ru.mqclass.ipcopy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.class_2561;
import net.minecraft.class_338;
import net.minecraft.class_7469;
import net.minecraft.class_7591;
import ru.mqclass.ipcopy.IpCopyProcessor;
import ru.mqclass.ipcopy.config.IpCopyConfig;
import ru.mqclass.ipcopy.lookup.IpLookupManager;
import ru.mqclass.ipcopy.scraper.SessionCaptureFSM;

/**
 * Mixin into net.minecraft.client.gui.hud.ChatHud (class_338).
 * Intercepts all inbound chat packets before rendering:
 * 1. Feeds the SessionCaptureFSM auto-pager and suppresses raw page output lines from chat.
 * 2. Suppresses server duplicate scan responses during background verification queues.
 * 3. Enriches regular chat messages containing IPv4 addresses with SpaceModeration copy buttons.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
@Mixin(class_338.class)
public class MixinChatHud {

    @Inject(
        method = "method_44811(Lnet/minecraft/class_2561;Lnet/minecraft/class_7469;Lnet/minecraft/class_7591;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ipcopy$interceptAndSuppressChat(
        class_2561 message,
        class_7469 signatureData,
        class_7591 indicator,
        CallbackInfo ci
    ) {
        if (message == null) return;

        String rawText = message.getString();
        if (rawText == null || rawText.isEmpty()) return;

        // 1. SessionCaptureFSM Auto-Pager interception & tracking
        boolean handledByFsm = SessionCaptureFSM.getInstance().handleInboundMessage(rawText, message);

        // 2. Always feed dashboard lookup manager so GUI stays synchronized in real time
        IpLookupManager.inspectMessage(message, rawText);

        // 3. Suppress raw lines from visible chat if handled by FSM auto-pager
        if (handledByFsm) {
            ci.cancel();
            return;
        }

        // 3. Intercept scan responses during batch command queues
        if (ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().isScanning()) {
            boolean handledByQueue = ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().handleServerChatLine(rawText);
            if (handledByQueue) {
                ci.cancel();
                return;
            }
        }

        // 4. Silent chat mode for auth messages
        if (IpCopyConfig.getInstance().silentChatMode && IpLookupManager.isAuthMessage(rawText)) {
            ci.cancel();
        }
    }

    @ModifyVariable(
        method = "method_44811(Lnet/minecraft/class_2561;Lnet/minecraft/class_7469;Lnet/minecraft/class_7591;)V",
        at = @At("HEAD"),
        argsOnly = true,
        require = 0
    )
    private class_2561 ipcopy$enrichVisibleMessages(class_2561 message) {
        return IpCopyProcessor.processMessage(message);
    }
}
