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

    public static void onIpCopied(String rawIp) {
        if (rawIp == null) {
            return;
        }

        final String ip = rawIp.trim();
        if (!IpCopyProcessor.isValidIp(ip)) {
            List<String> multiple = IpCopyProcessor.extractIps(rawIp);
            if (multiple.size() == 1) {
                onIpCopied(multiple.get(0));
            } else if (multiple.size() > 1) {
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

    public static void playSuccessSound() {
        class_310 client = class_310.method_1551();
        if (client == null) return;
        client.execute(() -> {
            IpCopyConfig config = IpCopyConfig.getInstance();
            if (config.soundFeedback) {
                try {
                    client.method_1483().method_4873(
                        class_1109.method_4758(class_3417.field_14627, 1.2F)
                    );
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public static void onDossierCopied(String nick) {
        class_310 client = class_310.method_1551();
        if (client == null) return;
        client.execute(() -> {
            IpCopyConfig config = IpCopyConfig.getInstance();
            if (config.soundFeedback) {
                try {
                    client.method_1483().method_4873(
                        class_1109.method_4758(class_3417.field_14627, 1.2F)
                    );
                } catch (Throwable ignored) {
                }
            }
            if (config.actionbarFeedback && client.field_1724 != null) {
                client.field_1724.method_7353(
                    class_2561.method_43470("§a✔ Досье игрока §e" + nick + " §aскопировано в буфер!"),
                    true
                );
            }
            if (config.toastFeedback) {
                net.minecraft.class_374 toastManager = client.method_1566();
                if (toastManager != null) {
                    toastManager.method_1999(new ru.mqclass.ipcopy.toast.IpToast(
                        class_2561.method_43470("§6[IPCopy] §a✔ Досье готово!"),
                        class_2561.method_43470("§eИгрок: §f" + nick)
                    ));
                }
            }
        });
    }

    public static void onAutoCrawlFinished(String nick, int totalSessions, int uniqueIps) {
        class_310 client = class_310.method_1551();
        if (client == null) return;
        client.execute(() -> {
            IpCopyConfig config = IpCopyConfig.getInstance();
            if (config.soundFeedback) {
                try {
                    client.method_1483().method_4873(
                        class_1109.method_4758(class_3417.field_14627, 1.3F)
                    );
                } catch (Throwable ignored) {
                }
            }
            if (config.actionbarFeedback && client.field_1724 != null) {
                client.field_1724.method_7353(
                    class_2561.method_43470("§a✔ Сбор завершён: §f" + totalSessions + " §aсессий (§f" + uniqueIps + " §aуник. IP) для §e" + nick),
                    true
                );
            }
            if (config.toastFeedback) {
                net.minecraft.class_374 toastManager = client.method_1566();
                if (toastManager != null) {
                    toastManager.method_1999(new ru.mqclass.ipcopy.toast.IpToast(
                        class_2561.method_43470("§6[IPCopy] §a✔ Сессии собраны!"),
                        class_2561.method_43470("§f" + totalSessions + " §7сессий | §e" + uniqueIps + " §7уник. IP")
                    ));
                }
            }
        });
    }

    public static void onBatchDupeFinished(int checkedCount, int twinksFound) {
        class_310 client = class_310.method_1551();
        if (client == null) return;
        client.execute(() -> {
            IpCopyConfig config = IpCopyConfig.getInstance();
            if (config.soundFeedback) {
                try {
                    client.method_1483().method_4873(
                        class_1109.method_4758(class_3417.field_14627, 1.3F)
                    );
                } catch (Throwable ignored) {
                }
            }
            if (config.actionbarFeedback && client.field_1724 != null) {
                client.field_1724.method_7353(
                    class_2561.method_43470("§a✔ Проверка DupeIP завершена: §f" + checkedCount + " §aIP (твинков: §c" + twinksFound + "§a)"),
                    true
                );
            }
            if (config.toastFeedback) {
                net.minecraft.class_374 toastManager = client.method_1566();
                if (toastManager != null) {
                    toastManager.method_1999(new ru.mqclass.ipcopy.toast.IpToast(
                        class_2561.method_43470("§6[IPCopy] §a✔ DupeIP завершён!"),
                        class_2561.method_43470("§7Проверено §f" + checkedCount + " §7IP | Твинков: §c" + twinksFound)
                    ));
                }
            }
        });
    }

    public static void onTwinksCopied(String ip, int count) {
        class_310 client = class_310.method_1551();
        if (client == null) return;
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
                client.field_1724.method_7353(
                    class_2561.method_43470("§a✔ Скопировано §f" + count + " §aтвинков для IP: §e" + ip),
                    true
                );
            }
        });
    }

    public static void onReportExported(String message, boolean success) {
        class_310 client = class_310.method_1551();
        if (client == null) return;
        client.execute(() -> {
            IpCopyConfig config = IpCopyConfig.getInstance();
            String prefix = success ? "§a✔ Отчёт сохранён: §f" : "§c✖ ";
            if (config.actionbarFeedback && client.field_1724 != null) {
                client.field_1724.method_7353(class_2561.method_43470(prefix + message), true);
            }
            if (config.toastFeedback) {
                net.minecraft.class_374 toastManager = client.method_1566();
                if (toastManager != null) {
                    toastManager.method_1999(new ru.mqclass.ipcopy.toast.IpToast(
                        class_2561.method_43470(success ? "§6[IPCopy] §a✔ Отчёт готов" : "§6[IPCopy] §c✖ Ошибка экспорта"),
                        class_2561.method_43470("§f" + message)
                    ));
                }
            }
        });
    }

    public static void playAuditStartSound() {
        class_310 client = class_310.method_1551();
        if (client == null) return;
        client.execute(() -> {
            IpCopyConfig config = IpCopyConfig.getInstance();
            if (config.soundFeedback) {
                try {
                    client.method_1483().method_4873(
                        class_1109.method_4758(class_3417.field_14710, 1.2F) // block.note_block.hat
                    );
                } catch (Throwable ignored) {}
            }
        });
    }

    public static void playTabSwitchSound() {
        class_310 client = class_310.method_1551();
        if (client == null) return;
        client.execute(() -> {
            IpCopyConfig config = IpCopyConfig.getInstance();
            if (config.soundFeedback) {
                try {
                    client.method_1483().method_4873(
                        class_1109.method_47978(class_3417.field_15239, 1.2F) // ui.button.click
                    );
                } catch (Throwable ignored) {}
            }
        });
    }

    public static void onLookupError(String reason) {
        class_310 client = class_310.method_1551();
        if (client == null) return;
        client.execute(() -> {
            IpCopyConfig config = IpCopyConfig.getInstance();
            if (config.soundFeedback) {
                try {
                    client.method_1483().method_4873(
                        class_1109.method_4758(class_3417.field_14697, 0.6F) // block.note_block.bass
                    );
                } catch (Throwable ignored) {}
            }
            if (config.actionbarFeedback && client.field_1724 != null) {
                client.field_1724.method_7353(class_2561.method_43470("§c✖ " + reason), true);
            }
        });
    }

    public static void onExpressAuditFinished(String nick, int sessions, int uniqueIps, int twinks) {
        class_310 client = class_310.method_1551();
        if (client == null) return;
        client.execute(() -> {
            IpCopyConfig config = IpCopyConfig.getInstance();
            if (config.soundFeedback) {
                try {
                    client.method_1483().method_4873(
                        class_1109.method_4758(class_3417.field_14627, 1.2F) // entity.player.levelup
                    );
                    net.minecraft.class_3414 bell = net.minecraft.class_3414.method_47908(
                        net.minecraft.class_2960.method_60655("minecraft", "block.note_block.bell")
                    );
                    client.method_1483().method_4873(
                        class_1109.method_4758(bell, 1.0F)
                    );
                } catch (Throwable ignored) {}
            }

            if (config.actionbarFeedback && client.field_1724 != null) {
                client.field_1724.method_7353(
                    class_2561.method_43470("§a✔ Экспресс-досье §e" + nick + " §aскопировано! §7(Твинков: §c" + twinks + "§7)"),
                    true
                );
            }

            if (config.toastFeedback) {
                net.minecraft.class_374 toastManager = client.method_1566();
                if (toastManager != null) {
                    toastManager.method_1999(new ru.mqclass.ipcopy.toast.IpToast(
                        class_2561.method_43470("§6[IPCopy] §a✔ Экспресс-аудит завершён!"),
                        class_2561.method_43470("§e" + nick + " §8| §f" + uniqueIps + " IP §8| §c" + twinks + " тв.")
                    ));
                }
            }
        });
    }
}
