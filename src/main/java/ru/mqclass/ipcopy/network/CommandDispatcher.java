package ru.mqclass.ipcopy.network;

import net.minecraft.class_310;
import net.minecraft.class_634;
import ru.mqclass.ipcopy.config.IpCopyConfig;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    public static final long HARD_MIN_INTERVAL_MS = 1450L;

    private final ConcurrentLinkedQueue<String> commandQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "IPCopy-CommandDispatcher");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean isDispatchInFlight = new AtomicBoolean(false);
    private final AtomicBoolean isWorkerScheduled = new AtomicBoolean(false);
    private volatile long lastDispatchTime = 0L;
    private volatile ScheduledFuture<?> nextTask = null;

    private CommandDispatcher() {}

    public static CommandDispatcher getInstance() {
        return INSTANCE;
    }

    public long getMinIntervalMs() {
        int cfgDelay = IpCopyConfig.getInstance().scanDelayMs;
        return Math.max(HARD_MIN_INTERVAL_MS, (long) cfgDelay);
    }

    /**
     * Dispatches a command to the server with guaranteed anti-spam rate limiting.
     * All commands are strictly serialized in an atomic FIFO queue spaced by >= 1450ms.
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

        getInstance().enqueue(sanitized);
        return true;
    }

    /**
     * Enqueues a command for sequential, rate-limited execution.
     * Prevents queue spam by deduplicating identical commands already waiting in queue.
     */
    public synchronized void enqueue(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) return;
        String sanitized = sanitize(rawCommand);
        if (sanitized.isEmpty()) return;

        if (commandQueue.contains(sanitized)) {
            return;
        }

        commandQueue.offer(sanitized);
        scheduleWorkerLocked();
    }

    /**
     * Handles server anti-spam signal ("Подождите 1 сек...").
     * Immediately applies an aggressive penalty backoff of 1800ms to guarantee no kick occurs.
     */
    public synchronized void handleServerThrottle() {
        long now = System.currentTimeMillis();
        this.lastDispatchTime = Math.max(this.lastDispatchTime, now) + 1800L;
        if (nextTask != null && !nextTask.isDone()) {
            nextTask.cancel(false);
            nextTask = null;
        }
        isWorkerScheduled.set(false);
        scheduleWorkerLocked();
    }

    public synchronized void clearQueue() {
        if (nextTask != null) {
            nextTask.cancel(true);
            nextTask = null;
        }
        commandQueue.clear();
        isDispatchInFlight.set(false);
        isWorkerScheduled.set(false);
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

    private synchronized void scheduleWorkerLocked() {
        if (isDispatchInFlight.get() || isWorkerScheduled.get() || commandQueue.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        long interval = getMinIntervalMs();
        long elapsed = now - lastDispatchTime;
        long delay = Math.max(0L, interval - elapsed);

        isWorkerScheduled.set(true);
        nextTask = scheduler.schedule(this::executeNextCommand, delay, TimeUnit.MILLISECONDS);
    }

    private void executeNextCommand() {
        String cmd;
        synchronized (this) {
            isWorkerScheduled.set(false);
            if (isDispatchInFlight.get()) {
                return;
            }
            cmd = commandQueue.poll();
            if (cmd == null) {
                return;
            }
            isDispatchInFlight.set(true);
        }

        dispatchDirectToNet(cmd);
    }

    /**
     * Executes the sanitized command on the Minecraft render thread.
     */
    private boolean dispatchDirectToNet(String sanitized) {
        class_310 client = class_310.method_1551();
        if (client == null) {
            onDispatchFinished();
            return false;
        }

        client.execute(() -> {
            try {
                class_634 networkHandler = client.method_1562();
                if (networkHandler != null) {
                    networkHandler.method_45730(sanitized);
                    synchronized (this) {
                        this.lastDispatchTime = Math.max(this.lastDispatchTime, System.currentTimeMillis());
                    }
                } else {
                    System.err.println("[IPCopy] CommandDispatcher: NetworkHandler is null, cannot dispatch: " + sanitized);
                }
            } catch (Throwable t) {
                System.err.println("[IPCopy] CommandDispatcher failed to dispatch: " + sanitized + " -> " + t.getMessage());
            } finally {
                onDispatchFinished();
            }
        });
        return true;
    }

    private void onDispatchFinished() {
        synchronized (this) {
            isDispatchInFlight.set(false);
            if (!commandQueue.isEmpty()) {
                scheduleWorkerLocked();
            }
        }
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
