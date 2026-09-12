package ru.mqclass.ipcopy.history;

import ru.mqclass.ipcopy.IpCopyProcessor;
import ru.mqclass.ipcopy.config.IpCopyConfig;
import ru.mqclass.ipcopy.lookup.SubnetMatcher;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * In-memory session history manager for copied IP addresses.
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 *
 * Guarantees:
 * - Strictly stored in RAM only (never written to disk or network).
 * - Maximum 20 unique most-recently-copied IPs.
 * - Automatically cleared upon disconnecting from any server/world.
 * - Opt-in/out via config.
 */
public final class IpHistoryManager {

    private static final int MAX_HISTORY_SIZE = 20;
    private static final LinkedList<HistoryEntry> HISTORY = new LinkedList<>();
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public record HistoryEntry(String ip, long timestamp) {
        public String getFormattedTime() {
            return Instant.ofEpochMilli(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .format(TIME_FORMATTER);
        }

        public String getSubnet24() {
            return SubnetMatcher.getSubnet24String(ip);
        }
    }

    private IpHistoryManager() {}

    public static synchronized void recordIp(String ip) {
        if (!IpCopyConfig.getInstance().sessionHistory) {
            return;
        }
        if (ip == null || !IpCopyProcessor.isValidIp(ip)) {
            return;
        }

        // Remove existing entry for same IP to bring it to top
        HISTORY.removeIf(entry -> entry.ip().equalsIgnoreCase(ip));
        HISTORY.addFirst(new HistoryEntry(ip, System.currentTimeMillis()));

        while (HISTORY.size() > MAX_HISTORY_SIZE) {
            HISTORY.removeLast();
        }
    }

    public static synchronized List<String> getHistory() {
        List<String> ips = new ArrayList<>(HISTORY.size());
        for (HistoryEntry entry : HISTORY) {
            ips.add(entry.ip());
        }
        return ips;
    }

    public static synchronized List<HistoryEntry> getDetailedHistory() {
        return new ArrayList<>(HISTORY);
    }

    public static synchronized void clear() {
        HISTORY.clear();
    }

    public static synchronized boolean isEmpty() {
        return HISTORY.isEmpty();
    }

    public static synchronized int size() {
        return HISTORY.size();
    }
}
