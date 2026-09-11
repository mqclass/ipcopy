package ru.mqclass.ipcopy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import ru.mqclass.ipcopy.IpCopyClient;

@Pseudo
@Mixin(targets = "org.example.spacemoderation.feature.chat.commands.DotCommandHandler", remap = false, priority = 2000)
public abstract class SpaceModerationDotCommandMixin {

    @Inject(
        method = "onChatMessage(Ljava/lang/String;)Z",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void ipcopy$handleDotCommand(String message, CallbackInfoReturnable<Boolean> callback) {
        if (!IpCopyClient.handleDotCommand(message)) {
            callback.setReturnValue(false);
        }
    }
}
