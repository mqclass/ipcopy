package ru.mqclass.ipcopy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.class_124;
import net.minecraft.class_2558;
import net.minecraft.class_2561;
import net.minecraft.class_2568;
import net.minecraft.class_2583;
import net.minecraft.class_5250;
import ru.mqclass.ipcopy.config.IpCopyConfig;

/**
 * Core processor for detecting IPv4 addresses in chat messages
 * and attaching SpaceModeration-style [Скоп. IP] interactive buttons.
 *
 * Authored by mqclass for Minecraft 1.21.11 Fabric.
 */
public final class IpCopyProcessor {

    // Matches valid IPv4 addresses (4 to 12 digits, 7 to 15 chars, 3 dots, 0-255 per octet)
    // Negative lookbehind and lookahead ensure we do not match inside larger strings or version numbers
    private static final Pattern IP_PATTERN = Pattern.compile(
        "(?<![0-9.])(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[0-9]{1,2})\\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[0-9]{1,2})(?![0-9.])"
    );

    private static final String COPY_BUTTON_TAG = "[Скоп. IP";
    private static final String LEGACY_COPY_BUTTON_TAG = "[Коп. IP";
    private static final int MAX_MESSAGE_LENGTH = 32_767;
    private static final int MAX_IPS_PER_MESSAGE = 64;
    private static final AtomicBoolean ERROR_REPORTED = new AtomicBoolean();

    private IpCopyProcessor() {}

    public static boolean isEnabled() {
        return IpCopyConfig.getInstance().enabled;
    }

    public static void setEnabled(boolean state) {
        IpCopyConfig.getInstance().enabled = state;
        IpCopyConfig.save();
    }

    /**
     * Extracts all valid unique IPv4 addresses from the input string.
     */
    public static List<String> extractIps(String text) {
        return extractIps(text, MAX_IPS_PER_MESSAGE);
    }

    private static List<String> extractIps(String text, int limit) {
        if (text == null || text.length() < 7 || limit <= 0) {
            return List.of();
        }

        Set<String> unique = new LinkedHashSet<>();
        Matcher matcher = IP_PATTERN.matcher(text);

        while (unique.size() < limit && matcher.find()) {
            String candidate = matcher.group();
            if (isValidIp(candidate)) {
                unique.add(candidate);
            }
        }

        return new ArrayList<>(unique);
    }

    /**
     * Validates IP address according to user criteria:
     * - Only digits (without dots): from 4 to 12
     * - Total characters (with 3 dots): from 7 to 15
     * - 4 octets separated by dots, each in range 0..255
     */
    public static boolean isValidIp(String ip) {
        if (ip == null) return false;
        int len = ip.length();
        if (len < 7 || len > 15) return false;

        int digits = 0;
        int dots = 0;
        for (int i = 0; i < len; i++) {
            char c = ip.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            } else if (c == '.') {
                dots++;
            } else {
                return false;
            }
        }

        if (dots != 3 || digits < 4 || digits > 12) {
            return false;
        }

        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;

        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            try {
                int val = Integer.parseInt(part);
                if (val < 0 || val > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        return true;
    }

    /**
     * Processes a chat message. If it contains IPv4 addresses, attaches
     * gold [Скоп. IP] buttons without modifying or corrupting existing contents.
     */
    public static class_2561 processMessage(class_2561 message) {
        return processMessage(message, false);
    }

    static class_2561 processPreviewMessage(class_2561 message) {
        return processMessage(message, true);
    }

    private static class_2561 processMessage(class_2561 message, boolean ignoreToggle) {
        if ((!isEnabled() && !ignoreToggle) || message == null) {
            return message;
        }

        try {
            String rawText = message.getString();
            if (rawText == null || rawText.isEmpty() || rawText.length() > MAX_MESSAGE_LENGTH) {
                return message;
            }

            // Inspect message for player auth responses
            ru.mqclass.ipcopy.lookup.IpLookupManager.inspectMessage(rawText);

            // Guard against duplicate processing
            if (rawText.contains(COPY_BUTTON_TAG) || rawText.contains(LEGACY_COPY_BUTTON_TAG)) {
                return message;
            }

            if (!rawText.contains("\n")) {
                List<String> ips = extractIps(rawText);
                if (ips.isEmpty()) {
                    return message;
                }
                class_5250 copy = message.method_27661();
                appendButtons(copy, ips);
                return copy;
            }

            /*
             * Session messages may arrive either as separate chat messages or as one Text
             * containing several lines. Rebuilding styled runs lets us put each
             * button beside its own history row while retaining the server's
             * colours, hover events and click events.
             */
            String[] lines = rawText.split("\n", -1);
            List<List<String>> ipsByLine = new ArrayList<>(lines.length);
            int remainingIps = MAX_IPS_PER_MESSAGE;
            boolean foundIp = false;
            for (String line : lines) {
                List<String> lineIps = extractIps(line, remainingIps);
                ipsByLine.add(lineIps);
                remainingIps -= lineIps.size();
                foundIp |= !lineIps.isEmpty();
            }
            if (!foundIp) {
                return message;
            }

            class_5250 rebuilt = class_2561.method_43473();
            int[] lineIndex = {0};
            message.method_27658((style, fragment) -> {
                String[] fragments = fragment.split("\n", -1);
                for (int i = 0; i < fragments.length; i++) {
                    if (!fragments[i].isEmpty()) {
                        rebuilt.method_10852(class_2561.method_43470(fragments[i]).method_10862(style));
                    }

                    if (i < fragments.length - 1) {
                        appendLineButtons(rebuilt, ipsByLine, lineIndex[0]);
                        rebuilt.method_27693("\n");
                        lineIndex[0]++;
                    }
                }
                return Optional.empty();
            }, class_2583.field_24360);

            appendLineButtons(rebuilt, ipsByLine, lineIndex[0]);
            return rebuilt;
        } catch (RuntimeException exception) {
            if (ERROR_REPORTED.compareAndSet(false, true)) {
                System.err.println("[IPCopy] Failed to process a chat component; the original message was preserved.");
                exception.printStackTrace(System.err);
            }
            return message;
        }
    }

    private static void appendLineButtons(class_5250 target, List<List<String>> ipsByLine, int lineIndex) {
        if (lineIndex >= 0 && lineIndex < ipsByLine.size()) {
            appendButtons(target, ipsByLine.get(lineIndex));
        }
    }

    private static void appendButtons(class_5250 target, List<String> ips) {
        boolean showIpInTitle = ips.size() > 1;
        IpCopyConfig config = IpCopyConfig.getInstance();
        for (String ip : ips) {
            target.method_27693(" ");
            target.method_10852(createIpButton(ip, showIpInTitle));

            if (config.showSecondAction) {
                target.method_27693(" ");
                target.method_10852(createSecondActionButton(ip));
            }
        }
    }

    /**
     * Creates an interactive Gold button in SpaceModeration style.
     * Clicking copies the IP to clipboard.
     * Hovering displays a formatted tooltip with the IP.
     */
    public static class_2561 createIpButton(String ip, boolean showIpInTitle) {
        IpCopyConfig config = IpCopyConfig.getInstance();
        String prefix = config.buttonPrefix != null ? config.buttonPrefix : "[Скоп. IP]";
        String buttonText = showIpInTitle ? prefix.replace("]", ": " + ip + "]") : prefix;

        class_5250 button = class_2561.method_43470(buttonText);

        class_5250 tooltipText = class_2561.method_43470(
            "§6[IPCopy] §eНажмите, чтобы скопировать IP:\n§a" + ip + "\n§7Мод сделал §6mqclass"
        );

        class_2583 style = class_2583.field_24360
            .method_10977(class_124.field_1065) // Formatting.GOLD
            .method_10958(new class_2558.class_10606(ip)) // ClickEvent.CopyToClipboard
            .method_10949(new class_2568.class_10613(tooltipText)); // HoverEvent.ShowText

        return button.method_10862(style);
    }

    /**
     * Creates an optional second action button (e.g. [DupeIP]) in cyan/aqua.
     */
    public static class_2561 createSecondActionButton(String ip) {
        IpCopyConfig config = IpCopyConfig.getInstance();
        String title = config.secondActionTitle != null ? config.secondActionTitle : "[DupeIP]";
        String rawCmd = config.secondActionCommand != null ? config.secondActionCommand : "/dupeip %ip%";
        String command = rawCmd.replace("%ip%", ip);

        class_5250 button = class_2561.method_43470(title);

        class_5250 tooltipText = class_2561.method_43470(
            "§b[IPCopy] §eНажмите, чтобы запустить:\n§f" + command
        );

        class_2583 style = class_2583.field_24360
            .method_10977(class_124.field_1078) // Formatting.AQUA
            .method_10958(new class_2558.class_10609(command)) // ClickEvent.RunCommand
            .method_10949(new class_2568.class_10613(tooltipText)); // HoverEvent.ShowText

        return button.method_10862(style);
    }
}
