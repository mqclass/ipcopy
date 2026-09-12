package ru.mqclass.ipcopy.compat;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Ensures stability by bypassing remote HWID backend checks
 * and resolving the user's authentic local UID.
 * Authored by mqclass.
 */
public final class SpaceModerationAuthFix {

    private static volatile String cachedUid = null;

    private SpaceModerationAuthFix() {}

    public static void init() {
        resolveValidUid();
        tryReflectivelyPatchHWIDUtil();
    }

    public static synchronized String resolveValidUid() {
        if (cachedUid != null && !cachedUid.isEmpty() && !"unknown-uid".equals(cachedUid)) {
            return cachedUid;
        }

        try {
            Path uidFile = Paths.get("config", "spacemoderation", "uid.txt");
            if (Files.exists(uidFile)) {
                try (BufferedReader reader = Files.newBufferedReader(uidFile, StandardCharsets.UTF_8)) {
                    String line = reader.readLine();
                    if (line != null) {
                        line = line.trim();
                        if (line.contains(":")) {
                            String[] parts = line.split(":");
                            if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                                cachedUid = parts[1].trim();
                                return cachedUid;
                            }
                        } else if (line.matches("\\d+")) {
                            cachedUid = line;
                            return cachedUid;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        cachedUid = "3";
        return cachedUid;
    }

    public static void tryReflectivelyPatchHWIDUtil() {
        try {
            Class<?> clazz = Class.forName("org.example.spacemoderation.util.auth.HWIDUtil");
            java.lang.reflect.Field uidField = clazz.getDeclaredField("cachedUID");
            uidField.setAccessible(true);
            uidField.set(null, resolveValidUid());

            java.lang.reflect.Field timeField = clazz.getDeclaredField("lastBackendCheckMs");
            timeField.setAccessible(true);
            timeField.setLong(null, System.currentTimeMillis() + 864000000L);
        } catch (Throwable ignored) {
        }
    }
}
