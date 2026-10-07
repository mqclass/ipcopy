package ru.mqclass.ipcopy.hud;

import net.minecraft.class_310;
import net.minecraft.class_327;
import net.minecraft.class_332;
import net.minecraft.class_9779;
import ru.mqclass.ipcopy.config.IpCopyConfig;
import ru.mqclass.ipcopy.forensics.IpForensicEngine;
import ru.mqclass.ipcopy.scraper.SessionCaptureFSM;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Modern Glassmorphic Forensics HUD Overlay.
 * Renders multi-page session scraping progress, dynamic status badges,
 * and /24 subnet risk breakdown via native DrawContext for 100% compatibility
 * with Sodium, ImmediatelyFast, and Nvidium.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class ForensicHudOverlay {

    private static final int CARD_WIDTH = 260;
    private static final long FADEOUT_TIME_MS = 8000L;

    private static long lastCompletionTime = 0L;
    private static boolean wasCompleted = false;

    private ForensicHudOverlay() {}

    public static void render(class_332 context, class_9779 tickCounter) {
        if (context == null || !IpCopyConfig.getInstance().showHudOverlay) {
            return;
        }

        try {
            SessionCaptureFSM fsm = SessionCaptureFSM.getInstance();
            SessionCaptureFSM.State state = fsm.getState();

            long now = System.currentTimeMillis();

            if (state == SessionCaptureFSM.State.COMPLETED) {
                if (!wasCompleted) {
                    wasCompleted = true;
                    lastCompletionTime = now;
                } else if (now - lastCompletionTime > FADEOUT_TIME_MS) {
                    return;
                }
            } else if (state == SessionCaptureFSM.State.IDLE) {
                wasCompleted = false;
                return;
            } else {
                wasCompleted = false;
            }

            class_310 client = class_310.method_1551();
            if (client == null || client.field_1772 == null) return;

            class_327 tr = client.field_1772;
            int screenWidth = context.method_51443();

            Map<String, IpForensicEngine.SubnetCluster> clusters = fsm.getAnalyzedClusters();
            List<IpForensicEngine.SubnetCluster> clusterList = new ArrayList<>(clusters.values());
            int displayRows = Math.min(4, clusterList.size());

            boolean isScraping = (state == SessionCaptureFSM.State.SCRAPING_PAGES || state == SessionCaptureFSM.State.SESSION_START);
            int cardHeight = isScraping ? 52 : (44 + (displayRows > 0 ? (displayRows * 14 + 10) : 0));
            int cardX = Math.max(4, screenWidth - CARD_WIDTH - 10);
            int cardY = (ru.mqclass.ipcopy.scanner.ScanQueueManager.getInstance().isScanning()) ? 62 : 10;

            // 1. Draw Main Glassmorphic Card (Drop shadow + border + dark glass fill)
            context.method_25294(cardX + 2, cardY + 2, cardX + CARD_WIDTH + 2, cardY + cardHeight + 2, 0x66000000);
            context.method_25294(cardX - 1, cardY - 1, cardX + CARD_WIDTH + 1, cardY + cardHeight + 1, 0xFF2D2D3D);
            context.method_25294(cardX, cardY, cardX + CARD_WIDTH, cardY + cardHeight, 0xE6101016);

            // 2. Draw Status Badge Pill
            String statusText;
            int badgeBgColor;
            int badgeBorderColor;

            if (isScraping) {
                statusText = "[СБОР СТРАНИЦ]";
                badgeBgColor = 0xAA664400;
                badgeBorderColor = 0xFFFFAA00;
            } else if (state == SessionCaptureFSM.State.PROCESSING) {
                statusText = "[АНАЛИЗ РИСКА]";
                badgeBgColor = 0xAA004466;
                badgeBorderColor = 0xFF00AAFF;
            } else {
                statusText = "[ГОТОВО]";
                badgeBgColor = 0xAA006622;
                badgeBorderColor = 0xFF00FF66;
            }

            int badgeWidth = tr.method_1727(statusText) + 8;
            int badgeX = cardX + CARD_WIDTH - badgeWidth - 8;
            int badgeY = cardY + 6;

            context.method_25294(badgeX - 1, badgeY - 1, badgeX + badgeWidth + 1, badgeY + 13, badgeBorderColor);
            context.method_25294(badgeX, badgeY, badgeX + badgeWidth, badgeY + 12, badgeBgColor);

            // 3. Progress bar during scraping or table divider when completed
            if (isScraping) {
                int barX = cardX + 8;
                int barY = cardY + 38;
                int barWidth = CARD_WIDTH - 16;
                int barHeight = 5;
                context.method_25294(barX, barY, barX + barWidth, barY + barHeight, 0xFF222530);

                float pct = Math.max(0.05f, Math.min(1.0f, fsm.getIngestedPagesCount() / (float) Math.max(1, fsm.getTotalExpectedPages())));
                int fillWidth = Math.max(2, Math.min(barWidth, (int) (barWidth * pct)));
                context.method_25294(barX, barY, barX + fillWidth, barY + barHeight, 0xFFFFAA00);
            } else if (displayRows > 0) {
                int tableStartY = cardY + 38;
                context.method_25294(cardX + 8, tableStartY - 2, cardX + CARD_WIDTH - 8, tableStartY - 1, 0xFF282836);

                for (int i = 0; i < displayRows; i++) {
                    int rowY = tableStartY + 2 + i * 14;
                    IpForensicEngine.SubnetCluster c = clusterList.get(i);

                    int riskBorder = (c.riskScore() >= 70) ? 0xFFE04040 : ((c.riskScore() >= 26) ? 0xFFE0A020 : 0xFF40C060);
                    int riskFill = (riskBorder & 0x00FFFFFF) | 0x44000000;
                    int pillX = cardX + CARD_WIDTH - 36;
                    int pillY = rowY + 1;
                    context.method_25294(pillX - 1, pillY - 1, pillX + 29, pillY + 11, riskBorder);
                    context.method_25294(pillX, pillY, pillX + 28, pillY + 10, riskFill);
                }
            }

            // 4. Draw Typography on top via Minecraft Text Renderer
            String targetNick = fsm.getCurrentTargetNick();
            if (targetNick == null || targetNick.isEmpty()) targetNick = "Target";

            // Title and target name
            context.method_51433(tr, "§6§lIPCopy §7| §f" + targetNick, cardX + 8, cardY + 8, 0xFFFFFFFF, true);

            if (isScraping) {
                String scrapeInfo = "§7Стр. §e" + fsm.getIngestedPagesCount() + "§7/§e" + fsm.getTotalExpectedPages() +
                    " §8| §7Уник. IP: §b" + fsm.getCapturedRawIps().size();
                context.method_51433(tr, scrapeInfo, cardX + 8, cardY + 22, 0xFFAAAAAA, true);

                int remainingPages = Math.max(0, fsm.getTotalExpectedPages() - fsm.getIngestedPagesCount());
                int eta = (int) Math.ceil(remainingPages * 1.45f);
                String etaText = "§8ETA: §7" + eta + "s";
                int etaWidth = tr.method_1727(etaText);
                context.method_51433(tr, etaText, cardX + CARD_WIDTH - etaWidth - 8, cardY + 22, 0xFF888888, true);
            } else {
                // Subtitle counters
                String statsText = "§7Сессий: §e" + fsm.getTotalIngestedSessions() +
                    " §7| Уник. IP: §a" + fsm.getCapturedRawIps().size() +
                    " §7| /24: §b" + clusters.size();
                context.method_51433(tr, statsText, cardX + 8, cardY + 22, 0xFFAAAAAA, true);

                // Table Rows Text
                if (displayRows > 0) {
                    int tableStartY = cardY + 38;
                    for (int i = 0; i < displayRows; i++) {
                        int rowY = tableStartY + 2 + i * 14;
                        IpForensicEngine.SubnetCluster c = clusterList.get(i);

                        // Subnet name
                        context.method_51433(tr, "§f" + c.cidr24(), cardX + 8, rowY + 2, 0xFFFFFFFF, true);

                        // Short ISP / City (truncated to fit)
                        String loc = c.countryCode() + " • " + c.ispOrganization();
                        if (loc.length() > 18) loc = loc.substring(0, 16) + "..";
                        context.method_51433(tr, "§8" + loc, cardX + 90, rowY + 2, 0xFF888899, true);

                        // Risk percentage
                        String riskStr = c.riskScore() + "%";
                        int riskW = tr.method_1727(riskStr);
                        int riskColor = (c.riskScore() >= 70) ? 0xFFFF5555 : ((c.riskScore() >= 26) ? 0xFFFFAA00 : 0xFF55FF55);
                        context.method_51433(tr, riskStr, cardX + CARD_WIDTH - 22 - (riskW / 2), rowY + 2, riskColor, true);
                    }
                }
            }

            // Status pill text
            context.method_51433(tr, statusText, badgeX + 4, badgeY + 2, 0xFFFFFFFF, true);
        } catch (Throwable ignored) {
            // Never allow HUD overlay rendering to crash the client render loop
        }
    }
}
