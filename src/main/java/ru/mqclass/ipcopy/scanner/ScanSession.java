package ru.mqclass.ipcopy.scanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Snapshot and real-time state of an active batch IP scan session.
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class ScanSession {

    public enum State {
        IDLE,
        RUNNING,
        PAUSED,
        COMPLETED
    }

    private final String targetNick;
    private final String commandTemplate;
    private final int totalIps;
    private final long startTime;
    private volatile long completedTime = 0L;

    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger cachedHitsCount = new AtomicInteger(0);
    private final AtomicInteger twinksFoundCount = new AtomicInteger(0);

    private volatile String currentIp = null;
    private volatile State state = State.IDLE;

    // IP -> List of associated alts / twinks
    private final Map<String, List<String>> results = new ConcurrentHashMap<>();

    public ScanSession(String targetNick, int totalIps, String commandTemplate) {
        this.targetNick = targetNick != null ? targetNick.trim() : "Unknown";
        this.totalIps = Math.max(0, totalIps);
        this.commandTemplate = (commandTemplate != null && !commandTemplate.isBlank())
            ? commandTemplate : "/dupeip %ip%";
        this.startTime = System.currentTimeMillis();
    }

    public String getTargetNick() {
        return targetNick;
    }

    public String getCommandTemplate() {
        return commandTemplate;
    }

    public int getTotalIps() {
        return totalIps;
    }

    public int getProcessedCount() {
        return processedCount.get();
    }

    public int getCachedHitsCount() {
        return cachedHitsCount.get();
    }

    public int getTwinksFoundCount() {
        return twinksFoundCount.get();
    }

    public String getCurrentIp() {
        return currentIp;
    }

    public void setCurrentIp(String ip) {
        this.currentIp = ip;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
        if (state == State.COMPLETED) {
            this.completedTime = System.currentTimeMillis();
        }
    }

    public long getStartTime() {
        return startTime;
    }

    public long getCompletedTime() {
        return completedTime;
    }

    public float getProgressPercentage() {
        if (totalIps <= 0) return 100.0F;
        return Math.min(100.0F, (processedCount.get() * 100.0F) / totalIps);
    }

    public int getEtaSeconds(long delayPerIpMs) {
        if (state == State.COMPLETED) return 0;
        int remaining = totalIps - processedCount.get();
        if (remaining <= 0) return 0;
        return (int) Math.ceil((remaining * delayPerIpMs) / 1000.0);
    }

    public synchronized void recordResult(String ip, List<String> twinks, boolean fromCache) {
        if (ip == null || ip.isBlank()) return;
        List<String> existing = results.get(ip);
        if (existing == null) {
            List<String> safeList = (twinks != null) ? new ArrayList<>(twinks) : new ArrayList<>();
            results.put(ip, safeList);
            if (!safeList.isEmpty()) {
                twinksFoundCount.addAndGet(safeList.size());
            }
            if (fromCache) {
                cachedHitsCount.incrementAndGet();
            }
            processedCount.incrementAndGet();
        } else if (twinks != null && !twinks.isEmpty()) {
            List<String> merged = new ArrayList<>(existing);
            int added = 0;
            for (String t : twinks) {
                if (!merged.contains(t)) {
                    merged.add(t);
                    added++;
                }
            }
            if (added > 0) {
                results.put(ip, merged);
                twinksFoundCount.addAndGet(added);
            }
        }
    }

    public Map<String, List<String>> getResults() {
        return Collections.unmodifiableMap(results);
    }

    public String buildSummaryReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("═════════════ ОТЧЕТ ПРОВЕРКИ IP ═════════════\n");
        sb.append("Цель: ").append(targetNick).append("\n");
        sb.append("Проверено адресов: ").append(totalIps);
        sb.append(" (из кэша RAM: ").append(cachedHitsCount.get()).append(")\n");
        sb.append("Обнаружено твинков: ").append(twinksFoundCount.get()).append("\n");
        sb.append("─────────────────────────────────────────────\n");

        if (results.isEmpty()) {
            sb.append("  (Результаты отсутствуют)\n");
        } else {
            int idx = 1;
            for (Map.Entry<String, List<String>> entry : results.entrySet()) {
                String ip = entry.getKey();
                List<String> twinks = entry.getValue();
                sb.append(idx++).append(". IP: ").append(ip);
                if (twinks.isEmpty()) {
                    sb.append(" — [Чист / Твинки не найдены]\n");
                } else {
                    sb.append(" — [Твинков: ").append(twinks.size()).append("] -> ")
                      .append(String.join(", ", twinks)).append("\n");
                }
            }
        }
        sb.append("═════════════════════════════════════════════\n");
        sb.append("Сгенерировано IP Copy by mqclass\n");
        return sb.toString();
    }
}
