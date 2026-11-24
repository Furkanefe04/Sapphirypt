package com.sapphircrypt.sapphirypt.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Shared formatting helpers for sizes, timestamps, and cryptographic identifiers.
 */
public final class FormatUtils {
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final HexFormat HEX = HexFormat.of();

    private FormatUtils() {
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0) return "0 B";

        if (bytes < 1024L) return bytes + " B";

        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unitIdx = 0;
        while (value >= 1024.0 && unitIdx < units.length - 1) {
            value /= 1024.0;
            unitIdx++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unitIdx]);
    }

    public static String formatTimestamp(long epochMillis) {
        if (epochMillis <= 0L) {
            return "-";
        }
        return DATE_FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String formatContainerId(byte[] containerId) {
        if (containerId == null || containerId.length == 0) return "-";

        String hex = HEX.formatHex(containerId);
        if (hex.length() <= 16) return hex;

        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "..." + hex.substring(hex.length() - 8);
    }
}
