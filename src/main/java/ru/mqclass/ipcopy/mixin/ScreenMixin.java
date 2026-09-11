package ru.mqclass.ipcopy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.class_2558;
import net.minecraft.class_310;
import net.minecraft.class_437;
import ru.mqclass.ipcopy.feedback.IpFeedback;

/**
 * Mixin into Screen text click handler to intercept IP copy events
 * and trigger tactile sound and actionbar feedback.
 */
@Mixin(class_437.class)
public abstract class ScreenMixin {

    @Inject(
        method = "method_71847(Lnet/minecraft/class_2558;Lnet/minecraft/class_310;Lnet/minecraft/class_437;)V",
        at = @At("HEAD")
    )
    private static void ipcopy$onHandleClickEvent(
        class_2558 clickEvent,
        class_310 client,
        class_437 screen,
        CallbackInfo ci
    ) {
        if (clickEvent instanceof class_2558.class_10606 copyEvent) {
            String text = copyEvent.comp_3503();
            if (text != null) {
                IpFeedback.onIpCopied(text);
            }
        }
    }
}
