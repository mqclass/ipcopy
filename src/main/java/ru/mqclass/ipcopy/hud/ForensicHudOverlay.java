package ru.mqclass.ipcopy.hud;

import net.minecraft.class_310;
import net.minecraft.class_327;
import net.minecraft.class_332;
import net.minecraft.class_9779;
import org.joml.Matrix4f;
import ru.mqclass.ipcopy.forensics.IpForensicEngine;
import ru.mqclass.ipcopy.render.SdfRenderer2D;
import ru.mqclass.ipcopy.scraper.SessionCaptureFSM;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Modern Glassmorphic Forensics HUD Overlay.
 * Renders hardware-instanced rounded cards, 12px Gaussian drop shadows,
 * dynamic status badges, and /24 subnet risk breakdown via SdfRenderer2D.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class ForensicHudOverlay {

    private static final int CARD_WIDTH = 260;
    private static final long FADEOUT_TIME_MS = 8000L;

    private static long lastCompletionTime = 0L;
    private static boolean wasCompleted = false;

    private static final Matrix4f orthoMatrix = new Matrix4f();

    private ForensicHudOverlay() {}

    public static void render(class_332 context, class_9779 tickCounter) {
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
        int screenWidth = client.method_22683().method_4486();
        int screenHeight = client.method_22683().method_4502();

        Map<String, IpForensicEngine.SubnetCluster> clusters = fsm.getAnalyzedClusters();
        List<IpForensicEngine.SubnetCluster> clusterList = new ArrayList<>(clusters.values());
        int displayRows = Math.min(4, clusterList.size());

        int cardHeight = 44 + (displayRows > 0 ? (displayRows * 14 + 14) : 0);
        int cardX = screenWidth - CARD_WIDTH - 10;
        int cardY = 10;

        // 1. Setup 2D Orthographic projection for SdfRenderer2D
        orthoMatrix.setOrtho(0.0f, (float) screenWidth, (float) screenHeight, 0.0f, -1000.0f, 1000.0f);

        SdfRenderer2D renderer = SdfRenderer2D.getInstance();
        renderer.begin(orthoMatrix);

        // 2. Draw Main Glassmorphic Card (8px radius, dark glass 0xB0101014, subtle border 0xFF353545, 12px blur shadow)
        renderer.drawGlassCard(
            cardX, cardY, CARD_WIDTH, cardHeight,
            8.0f,
            0xC8101014,
            0xFF2D2D3D,
            12.0f,
            4.0f
        );

        // 3. Draw Status Badge Pill
        String statusText;
        int badgeBgColor;
        int badgeBorderColor;

        if (state == SessionCaptureFSM.State.SCRAPING_PAGES || state == SessionCaptureFSM.State.SESSION_START) {
            statusText = "[SCRAPING...]";
            badgeBgColor = 0xAA664400;
            badgeBorderColor = 0xFFFFAA00;
        } else if (state == SessionCaptureFSM.State.PROCESSING) {
            statusText = "[ANALYZING NETWORKS]";
            badgeBgColor = 0xAA004466;
            badgeBorderColor = 0xFF00AAFF;
        } else {
            statusText = "[READY]";
            badgeBgColor = 0xAA006622;
            badgeBorderColor = 0xFF00FF66;
        }

        int badgeWidth = tr.method_1727(statusText) + 8;
        int badgeX = cardX + CARD_WIDTH - badgeWidth - 8;
        int badgeY = cardY + 7;

        renderer.drawRoundedRectWithStroke(
            badgeX, badgeY, badgeWidth, 12, 4.0f,
            badgeBgColor, 1.0f, badgeBorderColor
        );

        // 4. Draw Row separators and Risk pills for Subnet table
        if (displayRows > 0) {
            int tableStartY = cardY + 38;
            renderer.drawRoundedRect(cardX + 8, tableStartY - 2, CARD_WIDTH - 16, 1, 0.5f, 0xFF282836);

            for (int i = 0; i < displayRows; i++) {
                int rowY = tableStartY + 2 + i * 14;
                IpForensicEngine.SubnetCluster c = clusterList.get(i);

                int riskColor = (c.riskScore() >= 70) ? 0xFFE04040 : ((c.riskScore() >= 26) ? 0xFFE0A020 : 0xFF40C060);
                renderer.drawRoundedRect(cardX + CARD_WIDTH - 36, rowY + 1, 28, 10, 3.0f, (riskColor & 0x00FFFFFF) | 0x44000000);
                renderer.drawRoundedRectWithStroke(cardX + CARD_WIDTH - 36, rowY + 1, 28, 10, 3.0f, 0, 0.75f, riskColor);
            }
        }

        renderer.end();

        // 5. Draw Typography on top via Minecraft Text Renderer
        String targetNick = fsm.getCurrentTargetNick();
        if (targetNick.isEmpty()) targetNick = "Target";

        // Title and target name
        context.method_51433(tr, "§6§lIPCopy §7| §f" + targetNick, cardX + 8, cardY + 8, 0xFFFFFFFF, false);

        // Subtitle counters
        String statsText = "§7Сессий: §e" + fsm.getTotalIngestedSessions() + " §7| Подсетей /24: §b" + clusters.size();
        context.method_51433(tr, statsText, cardX + 8, cardY + 22, 0xFFAAAAAA, false);

        // Status pill text
        context.method_51433(tr, statusText, badgeX + 4, badgeY + 2, 0xFFFFFFFF, false);

        // Table Rows Text
        if (displayRows > 0) {
            int tableStartY = cardY + 38;
            for (int i = 0; i < displayRows; i++) {
                int rowY = tableStartY + 2 + i * 14;
                IpForensicEngine.SubnetCluster c = clusterList.get(i);

                // Subnet name
                context.method_51433(tr, "§f" + c.cidr24(), cardX + 8, rowY + 2, 0xFFFFFFFF, false);

                // Short ISP / City (truncated to fit)
                String loc = c.countryCode() + " • " + c.ispOrganization();
                if (loc.length() > 18) loc = loc.substring(0, 16) + "..";
                context.method_51433(tr, "§8" + loc, cardX + 90, rowY + 2, 0xFF888899, false);

                // Risk percentage
                String riskStr = c.riskScore() + "%";
                int riskW = tr.method_1727(riskStr);
                int riskColor = (c.riskScore() >= 70) ? 0xFFFF5555 : ((c.riskScore() >= 26) ? 0xFFFFAA00 : 0xFF55FF55);
                context.method_51433(tr, riskStr, cardX + CARD_WIDTH - 22 - (riskW / 2), rowY + 2, riskColor, false);
            }
        }
    }
}
