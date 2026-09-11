package ru.mqclass.ipcopy.feedback;

import net.minecraft.class_1109;
import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_3417;
import ru.mqclass.ipcopy.IpCopyProcessor;
import ru.mqclass.ipcopy.config.IpCopyConfig;
import ru.mqclass.ipcopy.history.IpHistoryManager;

/**
 * Handles tactile audio and actionbar visual feedback when an IP is copied.
 */
public final class IpFeedback {

    private static long lastCopyTime = 0L;
    private static final long DEBOUNCE_MS = 250L;

    private IpFeedback() {}

    public static void onIpCopied(String ip) {
        if (ip == null || !IpCopyProcessor.isValidIp(ip)) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean debounced = (now - lastCopyTime) < DEBOUNCE_MS;
        lastCopyTime = now;

        // 1. Record in session history
        IpHistoryManager.recordIp(ip);

        if (debounced) {
            return;
        }

        class_310 client = class_310.method_1551();
        if (client == null) {
            return;
        }

        client.execute(() -> {
            IpCopyConfig config = IpCopyConfig.getInstance();

            // 2. Play subtle UI button click sound
            if (config.soundFeedback) {
                try {
                    client.method_1483().method_4873(
                        class_1109.method_47978(class_3417.field_15239, 1.0F)
                    );
                } catch (Throwable ignored) {
                    // Safe guard against audio device errors
                }
            }

            // 3. Display actionbar confirmation overlay above hotbar
            if (config.actionbarFeedback && client.field_1724 != null) {
                try {
                    client.field_1724.method_7353(
                        class_2561.method_43470("§a✔ IP скопирован в буфер: §f" + ip),
                        true
                    );
                } catch (Throwable ignored) {
                    // Safe guard against overlay formatting errors
                }
            }
        });
    }
}
