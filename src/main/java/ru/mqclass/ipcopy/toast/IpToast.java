package ru.mqclass.ipcopy.toast;

import net.minecraft.class_2561;
import net.minecraft.class_327;
import net.minecraft.class_332;
import net.minecraft.class_368;
import net.minecraft.class_374;

/**
 * Native Minecraft HUD toast notification for IP copy events.
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class IpToast implements class_368 {

    private static final long DISPLAY_DURATION_MS = 2500L;
    private final class_2561 title;
    private final class_2561 description;
    private class_368.class_369 visibility = class_368.class_369.field_2210;

    public IpToast(class_2561 title, class_2561 description) {
        this.title = title != null ? title : class_2561.method_43470("§6[IPCopy]");
        this.description = description != null ? description : class_2561.method_43470("");
    }

    @Override
    public class_368.class_369 method_61988() {
        return this.visibility;
    }

    @Override
    public void method_61989(class_374 manager, long time) {
        if (time >= DISPLAY_DURATION_MS) {
            this.visibility = class_368.class_369.field_2209;
        }
    }

    @Override
    public void method_1986(class_332 context, class_327 textRenderer, long time) {
        int width = this.method_29049();
        int height = this.method_29050();

        // High-contrast SpaceModeration styled card
        context.method_25294(0, 0, width, height, 0xF0101520);
        // Golden borders
        context.method_25294(0, 0, width, 1, 0xFFFFAA00);
        context.method_25294(0, height - 1, width, height, 0xFFFFAA00);
        context.method_25294(0, 0, 1, height, 0xFFFFAA00);
        context.method_25294(width - 1, 0, width, height, 0xFFFFAA00);

        if (textRenderer != null) {
            context.method_27534(textRenderer, this.title, 8, 6, 0xFFFFAA00);
            context.method_27534(textRenderer, this.description, 8, 18, 0xFFFFFFFF);
        }
    }

    @Override
    public int method_29049() {
        return 160;
    }

    @Override
    public int method_29050() {
        return 32;
    }
}
