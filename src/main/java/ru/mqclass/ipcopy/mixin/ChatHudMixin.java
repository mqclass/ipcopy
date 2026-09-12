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

/**
 * Mixin into ChatHud to intercept all incoming chat messages (player chat, system messages, whispers)
 * before rendering, ensuring [Скоп. IP] is added across all message sources,
 * and enabling optional silent background lookup processing.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
@Mixin(class_338.class)
public class ChatHudMixin {

    @Inject(
        method = "method_44811(Lnet/minecraft/class_2561;Lnet/minecraft/class_7469;Lnet/minecraft/class_7591;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ipcopy$handleSilentChatAndInspect(
        class_2561 message,
        class_7469 signatureData,
        class_7591 indicator,
        CallbackInfo ci
    ) {
        if (message == null) return;

        String rawText = message.getString();
        if (rawText == null || rawText.isEmpty()) return;

        // 1. Always inspect incoming messages for dashboard updates
        IpLookupManager.inspectMessage(message, rawText);

        // 2. If silent mode is enabled and message is server auth info/history/pagination, suppress from chat HUD
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
    private class_2561 ipcopy$modifyChatMessage(class_2561 message) {
        return IpCopyProcessor.processMessage(message);
    }
}
