package ru.mqclass.ipcopy.lookup;

import net.minecraft.class_310;
import ru.mqclass.ipcopy.IpCopyProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages player IP lookups and parsing of server /auth player info responses.
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class IpLookupManager {

    public record PlayerIpEntry(String date, String ip, String sessionType) {}

    private static final int MAX_CACHE_PLAYERS = 100;
    private static final Map<String, List<PlayerIpEntry>> CACHE =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<PlayerIpEntry>> eldest) {
                return size() > MAX_CACHE_PLAYERS;
            }
        });

    private static final Pattern NICK_HEADER_PATTERN = Pattern.compile(
        "(?:Входы|Авторизации|История входов|Logins of)\\s+(?:игрока\\s+)?([A-Za-z0-9_]{1,16})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern DATE_PATTERN = Pattern.compile(
        "(\\d{2}[-./]\\d{2}[-./]\\d{4}\\s+\\d{2}:\\d{2})"
    );

    private static volatile String activeQueryNick = null;
    private static volatile long activeQueryTime = 0L;
    private static volatile Consumer<String> updateListener = null;

    static {
        // Pre-fill test entries for demonstration and offline testing
        List<PlayerIpEntry> testEntries = new ArrayList<>();
        testEntries.add(new PlayerIpEntry("11-09-2026 23:08", "178.62.204.18", "session"));
        testEntries.add(new PlayerIpEntry("05-09-2026 21:45", "94.25.180.12", "session"));
        testEntries.add(new PlayerIpEntry("02-09-2026 23:02", "185.230.240.209", "session"));
        CACHE.put("dinoky", testEntries);
    }

    private IpLookupManager() {}

    public static String stripColorCodes(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)§[0-9a-fk-or]", "");
    }

    public static void setUpdateListener(Consumer<String> listener) {
        updateListener = listener;
    }

    public static String getActiveQueryNick() {
        return activeQueryNick;
    }

    public static boolean isQueryPending(String nick) {
        if (activeQueryNick == null || nick == null) return false;
        if (!activeQueryNick.equalsIgnoreCase(nick)) return false;
        return (System.currentTimeMillis() - activeQueryTime) < 5000L;
    }

    public static boolean queryPlayer(String nick) {
        if (nick == null || nick.trim().isEmpty()) {
            return false;
        }

        String target = nick.trim();
        activeQueryNick = target;
        activeQueryTime = System.currentTimeMillis();

        class_310 client = class_310.method_1551();
        if (client != null && client.method_1562() != null) {
            client.method_1562().method_45730("auth player " + target + " info");
            return true;
        }
        return false;
    }

    public static List<PlayerIpEntry> getEntries(String nick) {
        if (nick == null) return Collections.emptyList();
        List<PlayerIpEntry> entries = CACHE.get(nick.trim().toLowerCase(Locale.ROOT));
        return entries != null ? new ArrayList<>(entries) : Collections.emptyList();
    }

    public static void inspectMessage(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return;
        }

        String cleanText = stripColorCodes(rawText);

        String targetNick = null;
        Matcher nickMatcher = NICK_HEADER_PATTERN.matcher(cleanText);
        if (nickMatcher.find()) {
            targetNick = nickMatcher.group(1);
        } else if (activeQueryNick != null && (System.currentTimeMillis() - activeQueryTime) < 5000L) {
            String lower = cleanText.toLowerCase(Locale.ROOT);
            if (lower.contains("вход") || lower.contains("сессия") || lower.contains("session") || lower.contains("login") || DATE_PATTERN.matcher(cleanText).find()) {
                targetNick = activeQueryNick;
            }
        }

        if (targetNick == null) {
            return;
        }

        String[] lines = cleanText.split("\n");
        List<PlayerIpEntry> parsedEntries = new ArrayList<>();

        for (String line : lines) {
            List<String> ips = IpCopyProcessor.extractIps(line);
            if (ips.isEmpty()) {
                continue;
            }

            String ip = ips.get(0);
            String date = "Неизвестно";
            Matcher dateMatcher = DATE_PATTERN.matcher(line);
            if (dateMatcher.find()) {
                date = dateMatcher.group(1);
            }

            String type = line.toLowerCase(Locale.ROOT).contains("session") ? "session" : "login";
            parsedEntries.add(new PlayerIpEntry(date, ip, type));
        }

        if (!parsedEntries.isEmpty()) {
            CACHE.put(targetNick.toLowerCase(Locale.ROOT), parsedEntries);

            Consumer<String> listener = updateListener;
            if (listener != null) {
                listener.accept(targetNick);
            }
        }
    }

    public static void clearCache() {
        CACHE.clear();
        updateListener = null;
        activeQueryNick = null;
        activeQueryTime = 0L;

        // Restore default demo
        List<PlayerIpEntry> testEntries = new ArrayList<>();
        testEntries.add(new PlayerIpEntry("11-09-2026 23:08", "178.62.204.18", "session"));
        testEntries.add(new PlayerIpEntry("05-09-2026 21:45", "94.25.180.12", "session"));
        testEntries.add(new PlayerIpEntry("02-09-2026 23:02", "185.230.240.209", "session"));
        CACHE.put("dinoky", testEntries);
    }
}
