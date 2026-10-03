package ru.mqclass.ipcopy.hud;

import net.minecraft.class_310;
import net.minecraft.class_327;
import net.minecraft.class_332;
import net.minecraft.class_9779;
import ru.mqclass.ipcopy.config.IpCopyConfig;
import ru.mqclass.ipcopy.scanner.ScanQueueManager;
import ru.mqclass.ipcopy.scanner.ScanSession;

/**
 * Modern, high-contrast HUD overlay displaying real-time batch IP scanning progress,
 * target nick, current IP, ETA, and animated progress bar.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class ScanHudOverlay {

    private static final int CARD_WIDTH = 180;
    private static final int CARD_HEIGHT = 44;
    private static final long FADEOUT_DURATION_MS = 3500L;

    private static long completionTimestamp = 0L;
    private static ScanSession lastCompletedSession = null;

    private ScanHudOverlay() {}

    public static void render(class_332 context, class_9779 tickCounter) {
        if (!IpCopyConfig.getInstance().showHudOverlay) {
            return;
        }

        class_310 client = class_310.method_1551();
        if (client == null || client.field_1772 == null) {
            return;
        }

        ScanSession session = ScanQueueManager.getInstance().getCurrentSession();
        long now = System.currentTimeMillis();

        if (session == null || session.getState() == ScanSession.State.IDLE) {
            // Check if we should display the recent completion banner
            if (lastCompletedSession == null || now - completionTimestamp > FADEOUT_DURATION_MS) {
                return;
            }
            session = lastCompletedSession;
        } else if (session.getState() == ScanSession.State.COMPLETED) {
            if (lastCompletedSession != session) {
                lastCompletedSession = session;
                completionTimestamp = now;
            } else if (now - completionTimestamp > FADEOUT_DURATION_MS) {
                return;
            }
        }

        class_327 tr = client.field_1772;
        int screenWidth = context.method_51443();
        int x = screenWidth - CARD_WIDTH - 12;
        int y = 10;

        // Card glass background
        context.method_25294(x - 1, y - 1, x + CARD_WIDTH + 1, y + CARD_HEIGHT + 1, 0xAA2B2E3D);
        context.method_25294(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, 0xF012141C);

        // Header status and counters
        String headerTitle;
        int headerColor;
        ScanSession.State state = session.getState();
        if (state == ScanSession.State.COMPLETED) {
            headerTitle = "§a✔ Сканирование завершено";
            headerColor = 0xFF55FF55;
        } else if (state == ScanSession.State.PAUSED) {
            headerTitle = "§6⏸ Пауза сканирования";
            headerColor = 0xFFFFAA00;
        } else {
            headerTitle = "§b⚡ Проверка IP";
            headerColor = 0xFF55FFFF;
        }

        context.method_51433(tr, headerTitle, x + 6, y + 5, headerColor, true);

        String progressText = "§f" + session.getProcessedCount() + "§7/§e" + session.getTotalIps();
        int progressTextWidth = tr.method_1727(progressText);
        context.method_51433(tr, progressText, x + CARD_WIDTH - progressTextWidth - 6, y + 5, 0xFFFFFFFF, true);

        // Subtitle line (Target nick and current IP or ETA)
        String subText;
        if (state == ScanSession.State.COMPLETED) {
            int uniqueTwinks = session.getTwinksFoundCount();
            subText = "§7Игрок: §f" + session.getTargetNick() + " §8| §a+" + uniqueTwinks + " акк.";
        } else {
            String ip = session.getCurrentIp();
            if (ip == null || ip.isEmpty()) ip = "...";
            int eta = session.getEtaSeconds(IpCopyConfig.getInstance().scanDelayMs);
            subText = "§7" + ip + " §8| §7ETA: §b" + eta + "s";
        }
        context.method_51433(tr, subText, x + 6, y + 18, 0xFFAAAAAA, true);

        // Progress bar track
        int barX = x + 6;
        int barY = y + 30;
        int barWidth = CARD_WIDTH - 12;
        int barHeight = 5;
        context.method_25294(barX, barY, barX + barWidth, barY + barHeight, 0xFF222530);

        // Progress bar fill
        int progressPercent = (int) session.getProgressPercentage();
        int fillWidth = Math.max(0, Math.min(barWidth, (int) (barWidth * (progressPercent / 100.0f))));
        if (fillWidth > 0) {
            int barColor = (state == ScanSession.State.COMPLETED) ? 0xFF55FF55
                         : (state == ScanSession.State.PAUSED) ? 0xFFFFAA00 : 0xFF00AAFF;
            context.method_25294(barX, barY, barX + fillWidth, barY + barHeight, barColor);
        }
    }
}
