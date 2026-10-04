package ru.mqclass.ipcopy.risk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.mqclass.ipcopy.forensics.IpForensicEngine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Concurrent ASN, Geolocation, and VPN/Proxy risk scoring service.
 * Operates on Java Virtual Threads to execute parallel HTTP lookups for unique /24 subnets
 * with sub-400ms target response times and a 30-minute in-memory LRU cache.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class AsnRiskLookupService {

    private static final AsnRiskLookupService INSTANCE = new AsnRiskLookupService();

    private static final int CACHE_CAPACITY = 2048;
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L; // 30 minutes

    private final ExecutorService virtualExecutor;
    private final HttpClient httpClient;

    // LRU Cache backed by thread-safe synchronized LinkedHashMap
    private final Map<String, CachedRiskEntry> lruCache = Collections.synchronizedMap(
        new LinkedHashMap<String, CachedRiskEntry>(CACHE_CAPACITY, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedRiskEntry> eldest) {
                return size() > CACHE_CAPACITY;
            }
        }
    );

    public record AsnRiskRecord(
        String ip,
        String cidr24,
        String asn,
        String ispOrganization,
        String countryCode,
        String city,
        boolean isProxy,
        boolean isHosting,
        int riskScore
    ) {
        public static AsnRiskRecord defaultClean(String ip, String cidr24) {
            return new AsnRiskRecord(
                ip, cidr24, "AS0 (Unknown)", "Residential ISP",
                "XX", "Unknown", false, false, 15
            );
        }
    }

    private record CachedRiskEntry(AsnRiskRecord record, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private AsnRiskLookupService() {
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
            .executor(virtualExecutor)
            .connectTimeout(Duration.ofMillis(800))
            .build();
    }

    public static AsnRiskLookupService getInstance() {
        return INSTANCE;
    }

    /**
     * Resolves metadata and risk score for a single IPv4 address asynchronously.
     */
    public CompletableFuture<AsnRiskRecord> lookupIpAsync(String ip) {
        if (!IpForensicEngine.isValidIpv4(ip)) {
            return CompletableFuture.completedFuture(AsnRiskRecord.defaultClean(ip, "0.0.0.0/24"));
        }

        String cidr = IpForensicEngine.getCidr24(ip);

        // Check cache by CIDR /24 pool
        CachedRiskEntry cached = lruCache.get(cidr);
        if (cached != null && !cached.isExpired()) {
            return CompletableFuture.completedFuture(cached.record());
        }

        return CompletableFuture.supplyAsync(() -> queryEndpoint(ip, cidr), virtualExecutor)
            .exceptionally(t -> AsnRiskRecord.defaultClean(ip, cidr));
    }

    /**
     * Concurrently analyzes all /24 subnets in a collection, resolving them in parallel.
     */
    public CompletableFuture<Map<String, AsnRiskRecord>> lookupAllSubnetsAsync(Collection<String> ips) {
        if (ips == null || ips.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        Map<String, String> subnetToSampleIp = new LinkedHashMap<>();
        for (String ip : ips) {
            if (IpForensicEngine.isValidIpv4(ip)) {
                String cidr = IpForensicEngine.getCidr24(ip);
                subnetToSampleIp.putIfAbsent(cidr, ip);
            }
        }

        Map<String, AsnRiskRecord> resultMap = new ConcurrentHashMap<>();
        CompletableFuture<?>[] futures = subnetToSampleIp.entrySet().stream()
            .map(entry -> lookupIpAsync(entry.getValue()).thenAccept(record -> {
                resultMap.put(entry.getKey(), record);
            }))
            .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures).thenApply(v -> resultMap);
    }

    private AsnRiskRecord queryEndpoint(String ip, String cidr) {
        try {
            // Using ip-api endpoint with proxy & hosting fields
            String url = "http://ip-api.com/json/" + ip + "?fields=status,message,country,countryCode,city,isp,org,as,proxy,hosting,query";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(1200))
                .header("User-Agent", "IPCopy/1.5.0")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body() != null && !response.body().isEmpty()) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (json.has("status") && "success".equalsIgnoreCase(json.get("status").getAsString())) {
                    String countryCode = json.has("countryCode") ? json.get("countryCode").getAsString() : "XX";
                    String city = json.has("city") ? json.get("city").getAsString() : "Unknown";
                    String isp = json.has("isp") ? json.get("isp").getAsString() : "Unknown ISP";
                    String org = json.has("org") ? json.get("org").getAsString() : "";
                    String as = json.has("as") ? json.get("as").getAsString() : "AS0";
                    boolean proxy = json.has("proxy") && json.get("proxy").getAsBoolean();
                    boolean hosting = json.has("hosting") && json.get("hosting").getAsBoolean();

                    String ispDisplay = !org.isEmpty() && !org.equalsIgnoreCase(isp) ? (isp + " (" + org + ")") : isp;
                    int risk = calculateRisk(as, isp, org, proxy, hosting);

                    AsnRiskRecord record = new AsnRiskRecord(
                        ip, cidr, as, ispDisplay, countryCode, city, proxy, hosting, risk
                    );

                    lruCache.put(cidr, new CachedRiskEntry(record, System.currentTimeMillis()));
                    return record;
                }
            }
        } catch (Throwable ignored) {}

        AsnRiskRecord fallback = AsnRiskRecord.defaultClean(ip, cidr);
        lruCache.put(cidr, new CachedRiskEntry(fallback, System.currentTimeMillis()));
        return fallback;
    }

    private int calculateRisk(String as, String isp, String org, boolean proxy, boolean hosting) {
        int score = 10;
        if (proxy) score += 50;
        if (hosting) score += 40;

        String combined = (as + " " + isp + " " + org).toLowerCase(Locale.ROOT);
        if (combined.contains("vpn") || combined.contains("proxy") || combined.contains("tor")
            || combined.contains("tunnel") || combined.contains("ovh") || combined.contains("hetzner")
            || combined.contains("digitalocean") || combined.contains("m247") || combined.contains("linode")
            || combined.contains("choopa") || combined.contains("vultr") || combined.contains("amazon")
            || combined.contains("google cloud") || combined.contains("microsoft azure")) {
            score += 35;
        }

        // Residential providers reduction
        if (combined.contains("rostelecom") || combined.contains("mts") || combined.contains("beeline")
            || combined.contains("megafon") || combined.contains("tele2") || combined.contains("er-telecom")
            || combined.contains("comcast") || combined.contains("verizon") || combined.contains("vodafone")) {
            score = Math.max(5, score - 20);
        }

        return Math.min(100, Math.max(0, score));
    }

    public void clearCache() {
        lruCache.clear();
    }
}
