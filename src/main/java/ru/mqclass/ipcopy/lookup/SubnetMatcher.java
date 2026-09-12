package ru.mqclass.ipcopy.lookup;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fast bitwise subnet utility for IPv4 addresses.
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class SubnetMatcher {

    private SubnetMatcher() {}

    /**
     * Checks if two IPv4 addresses belong to the same /24 subnet (first 3 octets match).
     */
    public static boolean isSameSubnet24(String ip1, String ip2) {
        Integer bits1 = parseIpv4ToBits(ip1);
        Integer bits2 = parseIpv4ToBits(ip2);
        if (bits1 == null || bits2 == null) {
            return false;
        }
        int mask24 = 0xFFFFFF00;
        return (bits1 & mask24) == (bits2 & mask24);
    }

    /**
     * Extracts human-readable /24 subnet representation (e.g. "178.62.204.0/24").
     */
    public static String getSubnet24String(String ip) {
        Integer bits = parseIpv4ToBits(ip);
        if (bits == null) {
            return "";
        }
        int octet1 = (bits >>> 24) & 0xFF;
        int octet2 = (bits >>> 16) & 0xFF;
        int octet3 = (bits >>> 8) & 0xFF;
        return octet1 + "." + octet2 + "." + octet3 + ".0/24";
    }

    /**
     * Checks if an IPv4 address matches a given CIDR notation (e.g. "185.230.240.0/24").
     */
    public static boolean matchesSubnet(String ip, String cidr) {
        if (ip == null || cidr == null) {
            return false;
        }

        String trimmedIp = ip.trim();
        String trimmedCidr = cidr.trim();

        if (trimmedIp.indexOf(':') != -1 || trimmedCidr.indexOf(':') != -1) {
            return false;
        }

        int slashIndex = trimmedCidr.indexOf('/');
        String subnetBase;
        int prefix;

        if (slashIndex == -1) {
            subnetBase = trimmedCidr;
            prefix = 32;
        } else {
            subnetBase = trimmedCidr.substring(0, slashIndex).trim();
            String prefixStr = trimmedCidr.substring(slashIndex + 1).trim();
            if (prefixStr.isEmpty() || prefixStr.length() > 2) {
                return false;
            }
            try {
                prefix = Integer.parseInt(prefixStr);
                if (prefix < 0 || prefix > 32) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }

        Integer ipBits = parseIpv4ToBits(trimmedIp);
        Integer subnetBits = parseIpv4ToBits(subnetBase);
        if (ipBits == null || subnetBits == null) {
            return false;
        }

        int mask = (prefix == 0) ? 0 : (0xFFFFFFFF << (32 - prefix));
        return (ipBits & mask) == (subnetBits & mask);
    }

    /**
     * Counts occurrences of each /24 subnet across a list of IP addresses.
     */
    public static Map<String, Integer> countSubnetOccurrences(List<String> ips) {
        if (ips == null || ips.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String ip : ips) {
            String subnet = getSubnet24String(ip);
            if (!subnet.isEmpty()) {
                counts.put(subnet, counts.getOrDefault(subnet, 0) + 1);
            }
        }
        return counts;
    }

    /**
     * Parses IPv4 string into 32-bit integer.
     * Guaranteed O(1), 0 allocations, 0 regex, immune to ReDoS and blocking DNS calls.
     */
    public static Integer parseIpv4ToBits(String ip) {
        if (ip == null) return null;
        int len = ip.length();
        if (len < 7 || len > 15) return null;

        int result = 0;
        int currentOctet = 0;
        int octetCount = 0;
        boolean hasDigitsInOctet = false;

        for (int i = 0; i < len; i++) {
            char c = ip.charAt(i);
            if (c >= '0' && c <= '9') {
                currentOctet = (currentOctet * 10) + (c - '0');
                if (currentOctet > 255) return null;
                hasDigitsInOctet = true;
            } else if (c == '.') {
                if (!hasDigitsInOctet || octetCount >= 3) return null;
                result = (result << 8) | currentOctet;
                currentOctet = 0;
                hasDigitsInOctet = false;
                octetCount++;
            } else {
                return null;
            }
        }

        if (!hasDigitsInOctet || octetCount != 3) return null;
        return (result << 8) | currentOctet;
    }
}
