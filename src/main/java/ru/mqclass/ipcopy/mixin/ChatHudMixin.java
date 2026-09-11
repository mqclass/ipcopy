package ru.mqclass.ipcopy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.class_2561;
import net.minecraft.class_338;
import ru.mqclass.ipcopy.IpCopyProcessor;

/**
 * Mixin into ChatHud to intercept all incoming chat messages (player chat, system messages, whispers)
 * before rendering, ensuring [Скоп. IP] is added across all message sources.
 */
@Mixin(class_338.class)
public class ChatHudMixin {

    @ModifyVariable(
        method = "method_44811(Lnet/minecraft/class_2561;Lnet/minecraft/class_7469;Lnet/minecraft/class_7591;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private class_2561 ipcopy$modifyChatMessage(class_2561 message) {
        return IpCopyProcessor.processMessage(message);
    }
}
