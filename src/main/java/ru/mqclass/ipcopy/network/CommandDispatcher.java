package ru.mqclass.ipcopy.network;

import net.minecraft.class_310;
import net.minecraft.class_634;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe Brigadier Command Dispatcher for Fabric 1.21.11.
 * Resolves Brigadier syntax dispatch bugs by strictly stripping leading slashes,
 * preventing accidental public chat leaks, and executing safely on the client render thread.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class CommandDispatcher {

    private static final CommandDispatcher INSTANCE = new CommandDispatcher();

    private final ConcurrentLinkedQueue<String> commandQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "IPCopy-CommandDispatcher");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean isProcessingQueue = new AtomicBoolean(false);
    private volatile long minIntervalMs = 1250L;
    private volatile long lastDispatchTime = 0L;

    private CommandDispatcher() {}

    public static CommandDispatcher getInstance() {
        return INSTANCE;
    }

    /**
     * Sanitizes and immediately dispatches a command to the server on the render thread.
     * Guaranteed never to leak commands into public chat and never to send leading slashes.
     *
     * @param rawCommand the command string (with or without leading slash)
     * @return true if command was scheduled/dispatched, false if network handler is offline
     */
    public static boolean dispatch(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return false;
        }

        String sanitized = sanitize(rawCommand);
        if (sanitized.isEmpty()) {
            return false;
        }

        class_310 client = class_310.method_1551();
        if (client == null) {
            return false;
        }

        client.execute(() -> {
            try {
                class_634 networkHandler = client.method_1562();
                if (networkHandler != null) {
                    // method_45730 is sendChatCommand(String) in Yarn mappings
                    networkHandler.method_45730(sanitized);
                } else {
                    System.err.println("[IPCopy] CommandDispatcher: NetworkHandler is null, cannot dispatch: " + sanitized);
                }
            } catch (Throwable t) {
                System.err.println("[IPCopy] CommandDispatcher failed to dispatch command: " + sanitized + " -> " + t.getMessage());
            }
        });

        return true;
    }

    /**
     * Enqueues a command for rate-limited background execution.
     */
    public void enqueue(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) return;
        String sanitized = sanitize(rawCommand);
        if (!sanitized.isEmpty()) {
            commandQueue.offer(sanitized);
            ensureQueueWorker();
        }
    }

    /**
     * Sets the minimum cooldown between consecutive queued commands (default: 1250ms).
     */
    public void setMinIntervalMs(long intervalMs) {
        this.minIntervalMs = Math.max(50L, intervalMs);
    }

    /**
     * Clears all pending queued commands.
     */
    public void clearQueue() {
        commandQueue.clear();
    }

    public int getQueueSize() {
        return commandQueue.size();
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
        long elapsed = now - lastDispatchTime;
        long delay = Math.max(0L, minIntervalMs - elapsed);

        scheduler.schedule(() -> {
            try {
                dispatch(nextCmd);
                lastDispatchTime = System.currentTimeMillis();
            } finally {
                scheduleNextQueueItem();
            }
        }, delay, TimeUnit.MILLISECONDS);
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
