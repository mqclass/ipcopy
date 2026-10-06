package ru.mqclass.ipcopy.forensics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forensic IP Engine providing strict IPv4 extraction, octet boundary validation,
 * CIDR /24 subnet clustering, dynamic ISP pool deduplication, and Discord Markdown report generation.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class IpForensicEngine {

    private static final Pattern IPV4_PATTERN = Pattern.compile(
        "\\b(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\." +
        "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\." +
        "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\." +
        "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b"
    );

    private static final Pattern COLOR_CODES = Pattern.compile("(?i)§[0-9a-fk-or]");
    private static final Pattern ANSI_CODES = Pattern.compile("\\x1B\\[[0-9;]*[a-zA-Z]");
    private static final Pattern PORT_SUFFIX = Pattern.compile(":\\d{1,5}$");

    private IpForensicEngine() {}

    /**
     * Cleans string from Minecraft color codes, ANSI sequences, and whitespace.
     */
    public static String cleanText(String input) {
        if (input == null) return "";
        String text = COLOR_CODES.matcher(input).replaceAll("");
        return ANSI_CODES.matcher(text).replaceAll("").trim();
    }

    /**
     * Validates whether a string is a strictly valid IPv4 address (0.0.0.0 to 255.255.255.255).
     */
    public static boolean isValidIpv4(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String clean = cleanText(ip);
        clean = PORT_SUFFIX.matcher(clean).replaceFirst("");
        String[] parts = clean.split("\\.");
        if (parts.length != 4) return false;

        try {
            for (String part : parts) {
                if (part.isEmpty() || (part.length() > 1 && part.startsWith("0"))) {
                    // Prevent leading zeroes unless single zero
                    if (!part.equals("0")) return false;
                }
                int val = Integer.parseInt(part);
                if (val < 0 || val > 255) return false;
            }
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * Extracts all valid, unique IPv4 addresses from text.
     */
    public static List<String> extractUniqueIps(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        String clean = cleanText(text);

        Set<String> unique = new LinkedHashSet<>();
        Matcher matcher = IPV4_PATTERN.matcher(clean);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (isValidIpv4(candidate)) {
                unique.add(candidate);
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * Calculates the CIDR /24 subnet for a given IPv4 (e.g. 217.196.163.236 -> 217.196.163.0/24).
     */
    public static String getCidr24(String ip) {
        if (ip == null || !isValidIpv4(ip)) return "0.0.0.0/24";
        String clean = cleanText(ip);
        clean = PORT_SUFFIX.matcher(clean).replaceFirst("");
        int lastDot = clean.lastIndexOf('.');
        if (lastDot <= 0) return clean + "/24";
        return clean.substring(0, lastDot) + ".0/24";
    }

    /**
     * Represents a clustered /24 subnet dynamic pool.
     */
    public record SubnetCluster(
        String cidr24,
        List<String> rawIps,
        int sessionCount,
        String sampleIp,
        String asn,
        String ispOrganization,
        String countryCode,
        String city,
        boolean isProxy,
        boolean isHosting,
        int riskScore,
        String riskBadge
    ) {
        public static SubnetCluster of(String cidr24, List<String> ips) {
            String sample = !ips.isEmpty() ? ips.getFirst() : "";
            return new SubnetCluster(
                cidr24,
                new ArrayList<>(ips),
                ips.size(),
                sample,
                "Unknown",
                "Residential Pool",
                "XX",
                "Unknown",
                false,
                false,
                15,
                "[CLEAN]"
            );
        }

        public SubnetCluster withRiskData(
            String asn, String isp, String country, String city,
            boolean proxy, boolean hosting, int risk
        ) {
            String badge;
            if (risk >= 70 || proxy || hosting) {
                badge = "[DATACENTER / VPN / PROXY]";
            } else if (risk >= 26) {
                badge = "[RESIDENTIAL / DYNAMIC]";
            } else {
                badge = "[CLEAN]";
            }

            return new SubnetCluster(
                cidr24, rawIps, sessionCount, sampleIp,
                asn, isp, country, city,
                proxy, hosting, risk, badge
            );
        }
    }

    /**
     * Clusters a collection of IPv4 addresses into CIDR /24 subnet pools.
     */
    public static Map<String, SubnetCluster> clusterSubnets(Collection<String> ips) {
        if (ips == null || ips.isEmpty()) return Collections.emptyMap();

        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String ip : ips) {
            if (!isValidIpv4(ip)) continue;
            String cidr = getCidr24(ip);
            groups.computeIfAbsent(cidr, k -> new ArrayList<>()).add(ip);
        }

        Map<String, SubnetCluster> clusters = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            clusters.put(entry.getKey(), SubnetCluster.of(entry.getKey(), entry.getValue()));
        }
        return clusters;
    }

    /**
     * Selects representative IPs for server queries (e.g. /dupeip),
     * dispatching ONLY ONCE per /24 subnet instead of 40+ redundant requests.
     */
    public static List<String> selectRepresentativeSubnetIps(Collection<String> ips) {
        if (ips == null || ips.isEmpty()) return Collections.emptyList();
        Map<String, SubnetCluster> clusters = clusterSubnets(ips);
        List<String> representatives = new ArrayList<>(clusters.size());
        for (SubnetCluster cluster : clusters.values()) {
            if (cluster.sampleIp != null && !cluster.sampleIp.isEmpty()) {
                representatives.add(cluster.sampleIp);
            }
        }
        return representatives;
    }

    /**
     * Builds a comprehensive Markdown / Discord report for moderation dossier.
     */
    public static String buildDiscordReport(
        String targetNick,
        int totalSessions,
        Collection<SubnetCluster> clusters,
        int overallRisk
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 🛡️ Отчёт судебной экспертизы IP: `").append(targetNick).append("`\n");
        sb.append("**Всего сессий:** `").append(totalSessions).append("` | ");
        sb.append("**Уникальных подсетей /24:** `").append(clusters.size()).append("` | ");

        String overallBadge;
        if (overallRisk >= 70) {
            overallBadge = "🔴 ВЫСОКИЙ РИСК (VPN / PROXY / DATACENTER)";
        } else if (overallRisk >= 26) {
            overallBadge = "🟡 СРЕДНИЙ РИСК (ДИНАМИЧЕСКИЙ ПУЛ ПРОВАЙДЕРА)";
        } else {
            overallBadge = "🟢 НИЗКИЙ РИСК (ЧИСТЫЙ РЕЗИДЕНТСКИЙ IP)";
        }
        sb.append("**Оценка риска:** ").append(overallBadge).append(" (`").append(overallRisk).append("%`)\n\n");

        sb.append("| Подсеть /24 | Провайдер / ASN | Локация | Сессий | Риск | Статус |\n");
        sb.append("| :--- | :--- | :--- | :---: | :---: | :--- |\n");

        for (SubnetCluster c : clusters) {
            sb.append("| `").append(c.cidr24).append("` ");
            sb.append("| ").append(c.ispOrganization).append(" (").append(c.asn).append(") ");
            sb.append("| ").append(c.countryCode).append(", ").append(c.city).append(" ");
            sb.append("| `").append(c.sessionCount).append("` ");
            sb.append("| `").append(c.riskScore).append("%` ");
            sb.append("| ").append(c.riskBadge).append(" |\n");
        }

        sb.append("\n**Уникальные IP-адреса:**\n```\n");
        Set<String> allIps = new LinkedHashSet<>();
        for (SubnetCluster c : clusters) {
            allIps.addAll(c.rawIps);
        }
        sb.append(String.join(",\n", allIps));
        sb.append("\n```\n");
        sb.append("*Сгенерировано клиентом IP Copy by mqclass*");
        return sb.toString();
    }
}
