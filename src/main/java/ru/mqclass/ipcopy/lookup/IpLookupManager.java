package ru.mqclass.ipcopy.lookup;

import net.minecraft.class_2558;
import net.minecraft.class_2561;
import net.minecraft.class_2583;
import net.minecraft.class_310;
import ru.mqclass.ipcopy.IpCopyProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages player IP lookups, parses player profiles and login history responses.
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class IpLookupManager {

    public record PlayerIpEntry(String date, String ip, String sessionType) {}

    public record PlayerProfile(
        String nick,
        String uuid,
        String premium,
        String vk,
        String telegram,
        String discord
    ) {}

    public enum LookupStatus {
        IDLE,
        WAITING_INFO,
        FETCHING_HISTORY,
        FOUND,
        NOT_REGISTERED,
        NO_HISTORY,
        TIMED_OUT
    }

    public static final class PlayerLookupData {
        public final String nick;
        public volatile PlayerProfile profile;
        public final List<PlayerIpEntry> entries = new CopyOnWriteArrayList<>();
        public volatile LookupStatus status = LookupStatus.IDLE;
        public volatile String errorMessage = null;
        public volatile String followUpCommand = null;
        public volatile long lastUpdated = System.currentTimeMillis();

        public PlayerLookupData(String nick) {
            this.nick = nick;
        }
    }

    private static final int MAX_CACHE_PLAYERS = 100;
    private static final Map<String, PlayerLookupData> CACHE =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PlayerLookupData> eldest) {
                return size() > MAX_CACHE_PLAYERS;
            }
        });

    private static final Pattern NICK_INFO_PATTERN = Pattern.compile(
        "Ник:\\s*(?:\\[[^\\]]*\\]\\s*)?([A-Za-z0-9_]{1,16})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "UUID:\\s*([0-9a-fA-F-]+)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PREMIUM_PATTERN = Pattern.compile(
        "Премиум:\\s*([^\r\n]+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern VK_PATTERN = Pattern.compile(
        "VK:\\s*([^\r\n]+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern TELEGRAM_PATTERN = Pattern.compile(
        "Telegram:\\s*([^\r\n]+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern DISCORD_PATTERN = Pattern.compile(
        "Discord:\\s*([^\r\n]+)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern NOT_REGISTERED_PATTERN = Pattern.compile(
        "(?:Указанный\\s+)?игрок,?\\s*(?:\\[[^\\]]*\\]\\s*)?([A-Za-z0-9_]{1,16}),?\\s+не\\s+(?:зарегистрирован|зарегестрирован|найден)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern LOGINS_HEADER_PATTERN = Pattern.compile(
        "(?:Входы|Авторизации|История входов|Logins of)\\s+(?:игрока\\s+)?(?:\\[[^\\]]*\\]\\s*)?([A-Za-z0-9_]{1,16})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern DATE_PATTERN = Pattern.compile(
        "(\\d{2}[-./]\\d{2}[-./]\\d{4}\\s+\\d{2}:\\d{2})"
    );

    private static volatile String activeQueryNick = null;
    private static volatile long activeQueryTime = 0L;
    private static volatile Consumer<String> updateListener = null;

    static {
        // Pre-fill Odinoky profile from actual server format
        PlayerLookupData odinoky = new PlayerLookupData("Odinoky");
        odinoky.profile = new PlayerProfile(
            "Odinoky",
            "bb291d35-9b8f-382b-9c2f-dee1a151c559",
            "нет",
            "Odinoky Raze",
            "@mq_class",
            "-"
        );
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 12:59", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 12:34", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 00:30", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 00:20", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 00:01", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("11-09-2026 23:50", "185.230.240.209", "session"));
        odinoky.status = LookupStatus.FOUND;
        CACHE.put("odinoky", odinoky);

        // Pre-fill demo DiNoKy
        PlayerLookupData dinoky = new PlayerLookupData("DiNoKy");
        dinoky.entries.add(new PlayerIpEntry("11-09-2026 23:08", "178.62.204.18", "session"));
        dinoky.entries.add(new PlayerIpEntry("05-09-2026 21:45", "94.25.180.12", "session"));
        dinoky.entries.add(new PlayerIpEntry("02-09-2026 23:02", "185.230.240.209", "session"));
        dinoky.status = LookupStatus.FOUND;
        CACHE.put("dinoky", dinoky);
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

        PlayerLookupData data = getOrCreateData(target);
        data.status = LookupStatus.WAITING_INFO;
        data.errorMessage = null;

        class_310 client = class_310.method_1551();
        if (client != null && client.method_1562() != null) {
            client.method_1562().method_45730("auth player " + target + " info");
            return true;
        }
        return false;
    }

    public static PlayerLookupData getData(String nick) {
        if (nick == null) return null;
        return CACHE.get(nick.trim().toLowerCase(Locale.ROOT));
    }

    public static PlayerProfile getProfile(String nick) {
        PlayerLookupData data = getData(nick);
        return data != null ? data.profile : null;
    }

    public static List<PlayerIpEntry> getEntries(String nick) {
        PlayerLookupData data = getData(nick);
        return data != null ? new ArrayList<>(data.entries) : Collections.emptyList();
    }

    public static LookupStatus getStatus(String nick) {
        PlayerLookupData data = getData(nick);
        return data != null ? data.status : LookupStatus.IDLE;
    }

    private static synchronized PlayerLookupData getOrCreateData(String nick) {
        String key = nick.trim().toLowerCase(Locale.ROOT);
        PlayerLookupData data = CACHE.get(key);
        if (data == null) {
            data = new PlayerLookupData(nick.trim());
            CACHE.put(key, data);
        }
        return data;
    }

    public static void inspectMessage(String rawText) {
        inspectMessage(null, rawText);
    }

    public static void inspectMessage(class_2561 message, String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return;
        }

        String cleanText = stripColorCodes(rawText);

        // Case 1: "Указанный игрок, ник, не зарегистрирован"
        Matcher notRegMatcher = NOT_REGISTERED_PATTERN.matcher(cleanText);
        if (notRegMatcher.find()) {
            String nick = notRegMatcher.group(1);
            if (nick == null && activeQueryNick != null) {
                nick = activeQueryNick;
            }
            if (nick != null) {
                PlayerLookupData data = getOrCreateData(nick);
                data.entries.clear();
                data.profile = null;
                data.status = LookupStatus.NOT_REGISTERED;
                data.errorMessage = "Указанный игрок не зарегистрирован на сервере";
                data.lastUpdated = System.currentTimeMillis();

                if (activeQueryNick != null && activeQueryNick.equalsIgnoreCase(nick)) {
                    activeQueryNick = null;
                }

                notifyListener(nick);
            }
            return;
        }

        // Case 2: "Информация об игроке" (has ClickEvent for history)
        if (cleanText.contains("Информация об игроке")) {
            Matcher nickMatcher = NICK_INFO_PATTERN.matcher(cleanText);
            String nick = nickMatcher.find() ? nickMatcher.group(1) : activeQueryNick;
            if (nick != null) {
                Matcher uuidM = UUID_PATTERN.matcher(cleanText);
                Matcher premM = PREMIUM_PATTERN.matcher(cleanText);
                Matcher vkM = VK_PATTERN.matcher(cleanText);
                Matcher tgM = TELEGRAM_PATTERN.matcher(cleanText);
                Matcher dsM = DISCORD_PATTERN.matcher(cleanText);

                String uuid = uuidM.find() ? uuidM.group(1) : "";
                String premium = premM.find() ? premM.group(1).trim() : "нет";
                String vk = vkM.find() ? vkM.group(1).trim() : "-";
                String tg = tgM.find() ? tgM.group(1).trim() : "-";
                String ds = dsM.find() ? dsM.group(1).trim() : "-";

                PlayerLookupData data = getOrCreateData(nick);
                data.profile = new PlayerProfile(nick, uuid, premium, vk, tg, ds);
                data.status = LookupStatus.FETCHING_HISTORY;
                data.lastUpdated = System.currentTimeMillis();

                // Extract ClickEvent command from "Посмотреть историю входов"
                String clickCmd = findClickCommand(message, "историю входов");
                if (clickCmd == null) {
                    clickCmd = findAnyAuthClickCommand(message);
                }
                if (clickCmd == null) {
                    clickCmd = "auth player " + nick + " logins";
                }
                data.followUpCommand = clickCmd;

                // If user initiated a query, automatically fetch the login history!
                if (isQueryPending(nick) || (activeQueryNick != null && activeQueryNick.equalsIgnoreCase(nick))) {
                    class_310 client = class_310.method_1551();
                    if (client != null && client.method_1562() != null && clickCmd != null) {
                        String cmd = clickCmd.startsWith("/") ? clickCmd.substring(1) : clickCmd;
                        client.method_1562().method_45730(cmd);
                    }
                }

                notifyListener(nick);
            }
            return;
        }

        // Case 3: "Входы игрока [head]nick (28/28)"
        String targetNick = null;
        Matcher loginsHeaderMatcher = LOGINS_HEADER_PATTERN.matcher(cleanText);
        if (loginsHeaderMatcher.find()) {
            targetNick = loginsHeaderMatcher.group(1);
        } else if (activeQueryNick != null && (System.currentTimeMillis() - activeQueryTime) < 5000L) {
            String lower = cleanText.toLowerCase(Locale.ROOT);
            boolean isAnticheat = lower.contains("sac") || lower.contains("античит") || lower.contains("anticheat");
            if (!isAnticheat && (lower.contains("вход") || lower.contains("сессия") || lower.contains("session") || lower.contains("login") || DATE_PATTERN.matcher(cleanText).find())) {
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
            PlayerLookupData data = getOrCreateData(targetNick);
            data.entries.clear();
            data.entries.addAll(parsedEntries);
            data.status = LookupStatus.FOUND;
            data.lastUpdated = System.currentTimeMillis();

            if (activeQueryNick != null && activeQueryNick.equalsIgnoreCase(targetNick)) {
                activeQueryNick = null;
            }

            notifyListener(targetNick);
        } else if (cleanText.contains("(0/0)") || cleanText.contains("(0/") || cleanText.toLowerCase(Locale.ROOT).contains("нет записей") || cleanText.toLowerCase(Locale.ROOT).contains("нет сессий")) {
            PlayerLookupData data = getOrCreateData(targetNick);
            data.entries.clear();
            data.status = LookupStatus.NO_HISTORY;
            data.lastUpdated = System.currentTimeMillis();

            if (activeQueryNick != null && activeQueryNick.equalsIgnoreCase(targetNick)) {
                activeQueryNick = null;
            }

            notifyListener(targetNick);
        }
    }

    public static String findClickCommand(class_2561 text, String targetSnippet) {
        if (text == null || targetSnippet == null) return null;

        class_2583 style = text.method_10866();
        if (style != null && style.method_10970() != null) {
            String content = text.getString();
            if (content != null && content.toLowerCase(Locale.ROOT).contains(targetSnippet.toLowerCase(Locale.ROOT))) {
                String cmd = extractCommandFromClickEvent(style.method_10970());
                if (cmd != null) return cmd;
            }
        }

        for (class_2561 sibling : text.method_10855()) {
            String cmd = findClickCommand(sibling, targetSnippet);
            if (cmd != null) return cmd;
        }

        return null;
    }

    public static String findAnyAuthClickCommand(class_2561 text) {
        if (text == null) return null;

        class_2583 style = text.method_10866();
        if (style != null && style.method_10970() != null) {
            String cmd = extractCommandFromClickEvent(style.method_10970());
            if (cmd != null && (cmd.toLowerCase(Locale.ROOT).contains("auth") || cmd.toLowerCase(Locale.ROOT).contains("player") || cmd.toLowerCase(Locale.ROOT).contains("login"))) {
                return cmd;
            }
        }

        for (class_2561 sibling : text.method_10855()) {
            String cmd = findAnyAuthClickCommand(sibling);
            if (cmd != null) return cmd;
        }

        return null;
    }

    public static String extractCommandFromClickEvent(class_2558 clickEvent) {
        if (clickEvent == null) return null;

        if (clickEvent instanceof class_2558.class_10609 runCmd) {
            return runCmd.comp_3506();
        }

        if (clickEvent instanceof class_2558.class_10610 suggestCmd) {
            return suggestCmd.comp_3507();
        }

        try {
            for (java.lang.reflect.Method m : clickEvent.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                    m.setAccessible(true);
                    String val = (String) m.invoke(clickEvent);
                    if (val != null && (val.startsWith("/") || val.toLowerCase(Locale.ROOT).contains("auth"))) {
                        return val;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static void notifyListener(String nick) {
        Consumer<String> listener = updateListener;
        if (listener != null) {
            class_310 client = class_310.method_1551();
            if (client != null) {
                client.execute(() -> {
                    Consumer<String> l = updateListener;
                    if (l != null) {
                        l.accept(nick);
                    }
                });
            } else {
                listener.accept(nick);
            }
        }
    }

    public static void clearCache() {
        CACHE.clear();
        updateListener = null;
        activeQueryNick = null;
        activeQueryTime = 0L;

        // Restore default demos
        PlayerLookupData odinoky = new PlayerLookupData("Odinoky");
        odinoky.profile = new PlayerProfile(
            "Odinoky",
            "bb291d35-9b8f-382b-9c2f-dee1a151c559",
            "нет",
            "Odinoky Raze",
            "@mq_class",
            "-"
        );
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 12:59", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 12:34", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 00:30", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 00:20", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("12-09-2026 00:01", "185.230.240.209", "session"));
        odinoky.entries.add(new PlayerIpEntry("11-09-2026 23:50", "185.230.240.209", "session"));
        odinoky.status = LookupStatus.FOUND;
        CACHE.put("odinoky", odinoky);

        PlayerLookupData dinoky = new PlayerLookupData("DiNoKy");
        dinoky.entries.add(new PlayerIpEntry("11-09-2026 23:08", "178.62.204.18", "session"));
        dinoky.entries.add(new PlayerIpEntry("05-09-2026 21:45", "94.25.180.12", "session"));
        dinoky.entries.add(new PlayerIpEntry("02-09-2026 23:02", "185.230.240.209", "session"));
        dinoky.status = LookupStatus.FOUND;
        CACHE.put("dinoky", dinoky);
    }
}
