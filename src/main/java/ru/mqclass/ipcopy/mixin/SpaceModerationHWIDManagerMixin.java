package ru.mqclass.ipcopy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "org.example.spacemoderation.util.auth.hwid.HWIDManager", remap = false)
public abstract class SpaceModerationHWIDManagerMixin {

    @Inject(method = "isBlacklisted()Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void ipcopy$forceNotBlacklisted(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
