package ru.mqclass.ipcopy.afk;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.class_1268;
import net.minecraft.class_2828;
import net.minecraft.class_2879;
import net.minecraft.class_310;
import ru.mqclass.ipcopy.config.IpCopyConfig;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Intelligent Anti-AFK KeepAlive manager to protect staff members from being
 * kicked for "idle timeout" while conducting ScreenShare checks or browsing logs.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class AntiAfkManager {

    private static final AntiAfkManager INSTANCE = new AntiAfkManager();

    private final AtomicLong lastActionTime = new AtomicLong(System.currentTimeMillis());
    private long nextIntervalMs = getRandomInterval();
    private boolean initialized = false;

    private AntiAfkManager() {}

    public static AntiAfkManager getInstance() {
        return INSTANCE;
    }

    public void init() {
        if (initialized) return;
        initialized = true;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.field_1724 == null || client.field_1687 == null) {
                return;
            }

            if (!IpCopyConfig.getInstance().antiAfk) {
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastActionTime.get() >= nextIntervalMs) {
                sendKeepAlive(client);
                lastActionTime.set(now);
                nextIntervalMs = getRandomInterval();
            }
        });
    }

    /**
     * Call this whenever player moves, types, or performs active UI actions.
     */
    public void recordPlayerAction() {
        this.lastActionTime.set(System.currentTimeMillis());
    }

    /**
     * Sends non-intrusive micro-packets to reset server-side idle timer without visual twitching.
     */
    private void sendKeepAlive(class_310 client) {
        if (client.field_1724 == null || client.field_1724.field_3944 == null) {
            return;
        }

        try {
            // 1. Silent hand swing packet (C2S)
            client.field_1724.field_3944.method_52787(new class_2879(class_1268.field_5808));

            // 2. Micro-rotation packet (+0.001 degree pitch change and return)
            float currentYaw = client.field_1724.method_36454();
            float currentPitch = client.field_1724.method_36455();
            boolean onGround = client.field_1724.method_24828();
            boolean horizCollision = client.field_1724.field_36331;

            float microOffset = 0.0015F;
            client.field_1724.field_3944.method_52787(
                new class_2828.class_2831(currentYaw, currentPitch + microOffset, onGround, horizCollision)
            );
            client.field_1724.field_3944.method_52787(
                new class_2828.class_2831(currentYaw, currentPitch, onGround, horizCollision)
            );
        } catch (Throwable ignored) {
            // Keep completely quiet on packet transmission errors
        }
    }

    private static long getRandomInterval() {
        // Random interval between 42 and 55 seconds to prevent fixed-pattern detection
        return ThreadLocalRandom.current().nextLong(42_000L, 55_000L);
    }
}
