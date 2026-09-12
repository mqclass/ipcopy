package ru.mqclass.ipcopy.keybind;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.class_2960;
import net.minecraft.class_304;
import ru.mqclass.ipcopy.IpCopyClient;

/**
 * Manages native keybindings for IP Copy.
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class IpKeyBindings {

    private static final int KEY_I = 73; // GLFW_KEY_I
    private static class_304 openGuiKey;

    private IpKeyBindings() {}

    public static void register() {
        class_304.class_11900 category = class_304.class_11900.method_74698(
            class_2960.method_60655("ipcopy", "main")
        );

        openGuiKey = KeyBindingHelper.registerKeyBinding(new class_304(
            "key.ipcopy.open_gui",
            KEY_I,
            category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null) {
                return;
            }
            while (openGuiKey.method_1436()) {
                if (client.field_1755 == null && client.field_1724 != null) {
                    IpCopyClient.openConfigGui();
                }
            }
        });
    }
}
