package com.sapphircrypt.sapphirypt.ui;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Format classification and pure-Java helpers for extended image, document, audio, and video previews.
 */
public final class MediaFormatSupport {
    public static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "json", "xml", "csv", "log", "java", "kt", "kts", "gradle",
            "py", "js", "ts", "html", "css", "yml", "yaml", "properties", "ini",
            "sql", "sh", "bat", "conf", "cfg", "toml");
    public static final Set<String> STANDARD_IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "jfif", "gif", "bmp", "tif", "tiff");
    public static final Set<String> WEBVIEW_IMAGE_EXTENSIONS = Set.of("svg", "webp");
    public static final Set<String> HEIC_EXTENSIONS = Set.of("heic", "heif");
    public static final Set<String> PDF_EXTENSIONS = Set.of("pdf");
    public static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "wav", "aac", "m4a", "aif", "aiff");
    public static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "m4v");

    private static final int MIN_EMBEDDED_JPEG_BYTES = 1024;
    private static final int MAX_EMBEDDED_JPEG_BYTES = 4 * 1024 * 1024;

    private MediaFormatSupport() {
    }

    public static boolean isText(String ext) {
        return TEXT_EXTENSIONS.contains(norm(ext));
    }

    public static boolean isStandardImage(String ext) {
        return STANDARD_IMAGE_EXTENSIONS.contains(norm(ext));
    }

    public static boolean isWebViewImage(String ext) {
        return WEBVIEW_IMAGE_EXTENSIONS.contains(norm(ext));
    }

    public static boolean isHeic(String ext) {
        return HEIC_EXTENSIONS.contains(norm(ext));
    }

    public static boolean isAnyImage(String ext) {
        return isStandardImage(ext) || isWebViewImage(ext) || isHeic(ext);
    }

    public static boolean isPdf(String ext) {
        return PDF_EXTENSIONS.contains(norm(ext));
    }

    public static boolean isAudio(String ext) {
        return AUDIO_EXTENSIONS.contains(norm(ext));
    }

    public static boolean isVideo(String ext) {
        return VIDEO_EXTENSIONS.contains(norm(ext));
    }

    /**
     * Extracts the largest embedded JPEG preview / EXIF thumbnail from a HEIC/HEIF container if present.
     *
     * @return raw JPEG bytes or {@code null} if none found
     */
    public static byte[] extractEmbeddedJpegFromHeic(byte[] heicBytes) {
        if (heicBytes == null || heicBytes.length < MIN_EMBEDDED_JPEG_BYTES) {
            return null;
        }
        // Verify ISOBMFF ftyp signature in first 32 bytes when present
        if (heicBytes.length >= 12) {
            String ftyp = new String(heicBytes, 4, 4, StandardCharsets.US_ASCII);
            if (!"ftyp".equals(ftyp)) {
                return null;
            }
        }

        int scanLimit = Math.min(heicBytes.length, 16 * 1024 * 1024);
        int bestStart = -1;
        int bestEnd = -1;

        for (int i = 0; i + 3 < scanLimit; i++) {
            if ((heicBytes[i] & 0xFF) == 0xFF
                    && (heicBytes[i + 1] & 0xFF) == 0xD8
                    && (heicBytes[i + 2] & 0xFF) == 0xFF) {
                int marker = heicBytes[i + 3] & 0xFF;
                if (marker < 0xC0) {
                    continue;
                }
                int maxEnd = Math.min(scanLimit, i + MAX_EMBEDDED_JPEG_BYTES);
                for (int j = i + MIN_EMBEDDED_JPEG_BYTES; j + 1 < maxEnd; j++) {
                    if ((heicBytes[j] & 0xFF) == 0xFF && (heicBytes[j + 1] & 0xFF) == 0xD9) {
                        int candidateLen = (j + 2) - i;
                        if (candidateLen > (bestEnd - bestStart)) {
                            bestStart = i;
                            bestEnd = j + 2;
                        }
                        i = j + 1;
                        break;
                    }
                }
            }
        }
        if (bestStart >= 0 && bestEnd > bestStart) {
            return Arrays.copyOfRange(heicBytes, bestStart, bestEnd);
        }
        return null;
    }

    /**
     * Wraps raw SVG or WebP bytes in a self-contained HTML data-URI document for in-memory WebView rendering.
     */
    public static String buildWebViewImageHtml(byte[] bytes, String extensionLower, boolean darkTheme) {
        String ext = norm(extensionLower);
        String mime = "svg".equals(ext) ? "image/svg+xml" : "image/webp";
        String base64 = Base64.getEncoder().encodeToString(bytes == null ? new byte[0] : bytes);
        String bg = darkTheme ? "#060B16" : "#F4F8FF";
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8">
                <style>
                  html, body {
                    margin: 0; padding: 0; width: 100%%; height: 100%%;
                    background: %s;
                    display: flex; align-items: center; justify-content: center;
                    overflow: hidden; user-select: none;
                  }
                  img {
                    max-width: 100%%; max-height: 100%%;
                    object-fit: contain;
                    display: block;
                  }
                </style>
                </head>
                <body>
                  <img src="data:%s;base64,%s" alt="preview" />
                </body>
                </html>
                """.formatted(bg, mime, base64);
    }

    private static String norm(String ext) {
        return ext == null ? "" : ext.trim().toLowerCase(Locale.ROOT);
    }
}
