package ru.mqclass.ipcopy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration manager for IP Copy mod by mqclass.
 * Stored safely at .minecraft/config/ipcopy.json.
 */
public final class IpCopyConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("ipcopy.json");
    private static volatile IpCopyConfig INSTANCE = new IpCopyConfig();

    // Configuration fields
    public volatile boolean enabled = true;
    public volatile String buttonPrefix = "[Скоп. IP]";
    public volatile boolean soundFeedback = true;
    public volatile boolean actionbarFeedback = true;
    public volatile boolean toastFeedback = true;
    public volatile boolean highlightSubnets = true;
    public volatile boolean sessionHistory = true;
    public volatile boolean showSecondAction = false;
    public volatile String secondActionTitle = "[DupeIP]";
    public volatile String secondActionCommand = "/dupeip %ip%";
    public volatile boolean silentChatMode = false;

    private IpCopyConfig() {}

    public static IpCopyConfig getInstance() {
        return INSTANCE;
    }

    public static synchronized void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(CONFIG_PATH)) {
            IpCopyConfig loaded = GSON.fromJson(reader, IpCopyConfig.class);
            if (loaded != null) {
                INSTANCE = loaded;
            }
        } catch (Exception e) {
            System.err.println("[IPCopy] Failed to read config from " + CONFIG_PATH + ", using defaults.");
            e.printStackTrace();
        }
    }

    public static synchronized void save() {
        try {
            Path parent = CONFIG_PATH.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (Exception e) {
            System.err.println("[IPCopy] Failed to save config to " + CONFIG_PATH);
            e.printStackTrace();
        }
    }
}
