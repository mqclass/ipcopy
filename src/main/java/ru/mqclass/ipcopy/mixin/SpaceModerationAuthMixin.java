package ru.mqclass.ipcopy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import ru.mqclass.ipcopy.compat.SpaceModerationAuthFix;

import java.util.concurrent.CompletableFuture;

@Pseudo
@Mixin(targets = "org.example.spacemoderation.util.auth.HWIDUtil", remap = false)
public abstract class SpaceModerationAuthMixin {

    @Inject(method = "isHWIDAllowed()Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void ipcopy$forceHwidAllowed(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    @Inject(method = "checkUIDFromBackend()V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void ipcopy$cancelBackendCheck(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "getUID()Ljava/lang/String;", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void ipcopy$forceValidUid(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(SpaceModerationAuthFix.resolveValidUid());
    }

    @Inject(method = "checkUIDFromBackendAsync()Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void ipcopy$cancelBackendCheckAsync(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        cir.setReturnValue(CompletableFuture.completedFuture(null));
    }
}
