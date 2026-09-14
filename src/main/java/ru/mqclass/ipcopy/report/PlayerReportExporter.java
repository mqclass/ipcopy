package ru.mqclass.ipcopy.report;

import net.fabricmc.loader.api.FabricLoader;
import ru.mqclass.ipcopy.gui.IpCopyScreen;
import ru.mqclass.ipcopy.lookup.IpLookupManager.PlayerIpEntry;
import ru.mqclass.ipcopy.lookup.IpLookupManager.PlayerProfile;
import ru.mqclass.ipcopy.lookup.SubnetMatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class PlayerReportExporter {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS");
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PlayerReportExporter() {}

    public static CompletableFuture<Path> export(String rawNick, PlayerProfile profile, List<PlayerIpEntry> rawEntries, Collection<String> collectedIps) {
        String nick = IpCopyScreen.sanitizeNick(rawNick);
        List<PlayerIpEntry> entries = rawEntries == null ? List.of() : List.copyOf(rawEntries);
        List<String> ips = collectedIps == null ? List.of() : List.copyOf(collectedIps);
        PlayerProfile profileSnapshot = profile;
        return CompletableFuture.supplyAsync(() -> {
            if (nick.isEmpty()) throw new IllegalArgumentException("Некорректный никнейм");
            try {
                Path reportsDir = FabricLoader.getInstance().getGameDir().resolve("ipcopy").resolve("reports");
                Files.createDirectories(reportsDir);
                LocalDateTime now = LocalDateTime.now();
                Path report = reportsDir.resolve(nick + "_" + FILE_TIME.format(now) + ".txt");
                Files.writeString(report, buildReport(nick, profileSnapshot, entries, ips, now), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return report;
            } catch (IOException exception) {
                throw new IllegalStateException("Не удалось сохранить отчёт", exception);
            }
        });
    }

    private static String buildReport(String nick, PlayerProfile profile, List<PlayerIpEntry> entries, List<String> collectedIps, LocalDateTime now) {
        Set<String> uniqueIps = new LinkedHashSet<>();
        uniqueIps.addAll(collectedIps);
        for (PlayerIpEntry entry : entries) uniqueIps.add(entry.ip());

        String uuid = profile != null ? safe(profile.uuid()) : "-";
        String premium = profile != null ? safe(profile.premium()) : "-";
        String vk = profile != null ? safe(profile.vk()) : "-";
        String telegram = profile != null ? safe(profile.telegram()) : "-";
        String discord = profile != null ? safe(profile.discord()) : "-";
        String separator = "==================================================\n";
        StringBuilder report = new StringBuilder(512 + entries.size() * 100);
        report.append(separator)
            .append("IP COPY MODERATOR REPORT — Игрок: ").append(nick).append('\n')
            .append("Дата экспорта: ").append(REPORT_TIME.format(now)).append('\n')
            .append("UUID: ").append(uuid).append(" | Премиум: ").append(premium).append('\n')
            .append("VK: ").append(vk).append(" | Telegram: ").append(telegram).append(" | Discord: ").append(discord).append('\n')
            .append("Всего уникальных IP: ").append(uniqueIps.size()).append('\n')
            .append(separator).append("ВХОДЫ И СЕССИИ:\n");
        for (int i = 0; i < entries.size(); i++) {
            PlayerIpEntry entry = entries.get(i);
            report.append(i + 1).append(". ").append(safe(entry.date())).append(" | ")
                .append(entry.ip()).append(" | [").append(safe(entry.sessionType())).append("] [⚡/24: ")
                .append(SubnetMatcher.getSubnet24String(entry.ip())).append("]\n");
        }
        report.append(separator).append("СПИСОК ВСЕХ IP ЧЕРЕЗ ПРОБЕЛ:\n")
            .append(String.join(" ", uniqueIps)).append('\n')
            .append(separator).append("Сгенерировано модом IP Copy by mqclass\n");
        return report.toString();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "-";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
