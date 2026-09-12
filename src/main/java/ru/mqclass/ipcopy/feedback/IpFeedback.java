package ru.mqclass.ipcopy.feedback;

import net.minecraft.class_1109;
import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_3417;
import ru.mqclass.ipcopy.IpCopyProcessor;
import ru.mqclass.ipcopy.config.IpCopyConfig;
import ru.mqclass.ipcopy.history.IpHistoryManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles tactile audio and actionbar visual feedback when an IP is copied.
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class IpFeedback {

    private static final AtomicLong LAST_COPY_TIME = new AtomicLong(0L);
    private static final long DEBOUNCE_MS = 250L;

    private IpFeedback() {}

    public static void onIpCopied(String ip) {
        if (ip == null) {
            return;
        }

        if (!IpCopyProcessor.isValidIp(ip)) {
            List<String> multiple = IpCopyProcessor.extractIps(ip);
            if (!multiple.isEmpty()) {
                onMultipleIpsCopied(multiple);
            }
            return;
        }

        long now = System.currentTimeMillis();
        long prev = LAST_COPY_TIME.getAndSet(now);
        boolean debounced = (now - prev) < DEBOUNCE_MS;

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
                }
            }

            // 4. Display native Minecraft HUD toast
            if (config.toastFeedback) {
                try {
                    net.minecraft.class_374 toastManager = client.method_1566();
                    if (toastManager != null) {
                        toastManager.method_1999(new ru.mqclass.ipcopy.toast.IpToast(
                            class_2561.method_43470("§6[IPCopy] §a✔ IP скопирован!"),
                            class_2561.method_43470("§e" + ip)
                        ));
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public static void onMultipleIpsCopied(List<String> ips) {
        if (ips == null || ips.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        long prev = LAST_COPY_TIME.getAndSet(now);
        boolean debounced = (now - prev) < DEBOUNCE_MS;

        for (String ip : ips) {
            IpHistoryManager.recordIp(ip);
        }

        if (debounced) {
            return;
        }

        class_310 client = class_310.method_1551();
        if (client == null) {
            return;
        }

        client.execute(() -> {
            IpCopyConfig config = IpCopyConfig.getInstance();

            if (config.soundFeedback) {
                try {
                    client.method_1483().method_4873(
                        class_1109.method_47978(class_3417.field_15239, 1.0F)
                    );
                } catch (Throwable ignored) {
                }
            }

            if (config.actionbarFeedback && client.field_1724 != null) {
                try {
                    client.field_1724.method_7353(
                        class_2561.method_43470("§a✔ Скопировано §f" + ips.size() + " §aIP в буфер обмена!"),
                        true
                    );
                } catch (Throwable ignored) {
                }
            }

            if (config.toastFeedback) {
                try {
                    net.minecraft.class_374 toastManager = client.method_1566();
                    if (toastManager != null) {
                        toastManager.method_1999(new ru.mqclass.ipcopy.toast.IpToast(
                            class_2561.method_43470("§6[IPCopy] §a✔ IP скопированы!"),
                            class_2561.method_43470("§eВсего адресов: §f" + ips.size())
                        ));
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
