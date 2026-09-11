package ru.mqclass.ipcopy.history;

import ru.mqclass.ipcopy.IpCopyProcessor;
import ru.mqclass.ipcopy.config.IpCopyConfig;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * In-memory session history manager for copied IP addresses.
 *
 * Guarantees:
 * - Strictly stored in RAM only (never written to disk or network).
 * - Maximum 10 unique most-recently-copied IPs.
 * - Automatically cleared upon disconnecting from any server/world.
 * - Opt-in/out via config.
 */
public final class IpHistoryManager {

    private static final int MAX_HISTORY_SIZE = 10;
    private static final LinkedList<String> HISTORY = new LinkedList<>();

    private IpHistoryManager() {}

    public static synchronized void recordIp(String ip) {
        if (!IpCopyConfig.getInstance().sessionHistory) {
            return;
        }
        if (ip == null || !IpCopyProcessor.isValidIp(ip)) {
            return;
        }

        HISTORY.remove(ip);
        HISTORY.addFirst(ip);

        while (HISTORY.size() > MAX_HISTORY_SIZE) {
            HISTORY.removeLast();
        }
    }

    public static synchronized List<String> getHistory() {
        return new ArrayList<>(HISTORY);
    }

    public static synchronized void clear() {
        HISTORY.clear();
    }

    public static synchronized boolean isEmpty() {
        return HISTORY.isEmpty();
    }
}
