package ru.mqclass.ipcopy.network;

import net.minecraft.class_310;
import net.minecraft.class_634;
import ru.mqclass.ipcopy.config.IpCopyConfig;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe Brigadier Command Dispatcher & Universal Rate Limiter for Fabric 1.21.11.
 * Enforces a strict minimum cooldown (default: 1350ms) between ALL consecutive server commands,
 * completely preventing server anti-spam kicks and "Подождите 1 сек" rejections.
 * Strips leading slashes to conform to Minecraft 1.20+ sendChatCommand protocol.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class CommandDispatcher {

    private static final CommandDispatcher INSTANCE = new CommandDispatcher();
    public static final long DEFAULT_MIN_INTERVAL_MS = 1350L;

    private final ConcurrentLinkedQueue<String> commandQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "IPCopy-CommandDispatcher");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean isProcessingQueue = new AtomicBoolean(false);
    private volatile long lastDispatchTime = 0L;

    private CommandDispatcher() {}

    public static CommandDispatcher getInstance() {
        return INSTANCE;
    }

    public long getMinIntervalMs() {
        int cfgDelay = IpCopyConfig.getInstance().scanDelayMs;
        return Math.max(DEFAULT_MIN_INTERVAL_MS, cfgDelay > 0 ? (long) cfgDelay : DEFAULT_MIN_INTERVAL_MS);
    }

    /**
     * Dispatches a command to the server with guaranteed anti-spam rate limiting.
     * If the server cooldown is currently active, the command is automatically enqueued
     * and executed the moment the cooldown expires.
     *
     * @param rawCommand the command string (with or without leading slash)
     * @return true if command was scheduled or dispatched
     */
    public static boolean dispatch(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return false;
        }

        String sanitized = sanitize(rawCommand);
        if (sanitized.isEmpty()) {
            return false;
        }

        CommandDispatcher dispatcher = getInstance();
        long interval = dispatcher.getMinIntervalMs();
        long now = System.currentTimeMillis();

        synchronized (dispatcher) {
            if (dispatcher.commandQueue.isEmpty() && !dispatcher.isProcessingQueue.get() && (now - dispatcher.lastDispatchTime >= interval)) {
                dispatcher.lastDispatchTime = now;
                return dispatcher.dispatchDirectToNet(sanitized);
            } else {
                dispatcher.enqueue(sanitized);
                return true;
            }
        }
    }

    /**
     * Enqueues a command for sequential, rate-limited execution.
     */
    public void enqueue(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) return;
        String sanitized = sanitize(rawCommand);
        if (sanitized.isEmpty()) return;

        commandQueue.offer(sanitized);
        ensureQueueWorker();
    }

    public synchronized void clearQueue() {
        commandQueue.clear();
        isProcessingQueue.set(false);
    }

    public int getQueueSize() {
        return commandQueue.size();
    }

    public boolean isCoolingDown() {
        return System.currentTimeMillis() - lastDispatchTime < getMinIntervalMs();
    }

    public long getRemainingCooldownMs() {
        long elapsed = System.currentTimeMillis() - lastDispatchTime;
        long interval = getMinIntervalMs();
        return Math.max(0L, interval - elapsed);
    }

    private void ensureQueueWorker() {
        if (isProcessingQueue.compareAndSet(false, true)) {
            scheduleNextQueueItem();
        }
    }

    private void scheduleNextQueueItem() {
        String nextCmd = commandQueue.poll();
        if (nextCmd == null) {
            isProcessingQueue.set(false);
            return;
        }

        long now = System.currentTimeMillis();
        long interval = getMinIntervalMs();
        long elapsed = now - lastDispatchTime;
        long delay = Math.max(0L, interval - elapsed);

        scheduler.schedule(() -> {
            try {
                lastDispatchTime = System.currentTimeMillis();
                dispatchDirectToNet(nextCmd);
            } finally {
                scheduleNextQueueItem();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Executes the sanitized command on the Minecraft render thread.
     */
    private boolean dispatchDirectToNet(String sanitized) {
        class_310 client = class_310.method_1551();
        if (client == null) {
            return false;
        }

        client.execute(() -> {
            try {
                class_634 networkHandler = client.method_1562();
                if (networkHandler != null) {
                    networkHandler.method_45730(sanitized);
                } else {
                    System.err.println("[IPCopy] CommandDispatcher: NetworkHandler is null, cannot dispatch: " + sanitized);
                }
            } catch (Throwable t) {
                System.err.println("[IPCopy] CommandDispatcher failed to dispatch: " + sanitized + " -> " + t.getMessage());
            }
        });
        return true;
    }

    /**
     * Strips leading slash and extraneous whitespace to conform to Minecraft 1.20+ sendChatCommand protocol.
     */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }
}
