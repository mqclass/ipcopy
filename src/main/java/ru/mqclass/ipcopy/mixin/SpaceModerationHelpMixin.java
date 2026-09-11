package ru.mqclass.ipcopy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ru.mqclass.ipcopy.IpCopyClient;

@Pseudo
@Mixin(targets = "org.example.spacemoderation.feature.chat.commands.ModeratorHelpCommand", remap = false)
public abstract class SpaceModerationHelpMixin {

    @Inject(method = "sendHelpMessage()V", at = @At("TAIL"), remap = false, require = 0)
    private static void ipcopy$appendCommandsToHelp(CallbackInfo callback) {
        IpCopyClient.showSpaceModerationHelpAddon();
    }
}
