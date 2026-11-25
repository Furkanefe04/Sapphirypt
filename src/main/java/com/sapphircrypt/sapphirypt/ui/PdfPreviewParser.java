package com.sapphircrypt.sapphirypt.ui;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Pure-Java in-memory PDF metadata, text, and embedded JPEG preview extractor.
 * Designed with strict memory caps so corrupt or huge PDFs fail safely without blocking UI threads.
 */
public final class PdfPreviewParser {
    private static final int MAX_STREAM_DECODE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_EMBEDDED_JPEG_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EMBEDDED_JPEGS = 3;
    private static final int MAX_EXTRACTED_TEXT_CHARS = 64 * 1024;

    private static final Pattern PAGE_TYPE_PATTERN = Pattern.compile("/Type\\s*/Page\\b(?!s)");
    private static final Pattern PAGES_COUNT_PATTERN = Pattern.compile("/Type\\s*/Pages\\b[^>]{0,400}?/Count\\s+(\\d+)");

    public record PdfSummary(
            String pdfVersion,
            int pageCount,
            String title,
            String author,
            String extractedText,
            List<byte[]> embeddedJpegs) {

        public PdfSummary {
            pdfVersion = pdfVersion == null ? "" : pdfVersion;
            pageCount = Math.max(0, pageCount);
            title = title == null ? "" : title;
            author = author == null ? "" : author;
            extractedText = extractedText == null ? "" : extractedText;
            embeddedJpegs = embeddedJpegs == null ? List.of() : List.copyOf(embeddedJpegs);
        }

        public boolean isValidPdf() {
            return !pdfVersion.isEmpty();
        }
    }

    private PdfPreviewParser() {
    }

    public static PdfSummary parse(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length < 8) {
            return new PdfSummary("", 0, "", "", "", List.of());
        }

        String raw = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        int headerIdx = findPdfHeaderOffset(raw);
        if (headerIdx < 0) {
            return new PdfSummary("", 0, "", "", "", List.of());
        }

        String version = extractVersion(raw, headerIdx);
        int pageCount = countPages(raw);
        String title = extractInfoString(raw, "/Title");
        String author = extractInfoString(raw, "/Author");

        StringBuilder textOut = new StringBuilder();
        List<byte[]> jpegs = new ArrayList<>();
        scanStreams(pdfBytes, raw, textOut, jpegs);

        return new PdfSummary(version, pageCount, title, author, textOut.toString().trim(), jpegs);
    }

    private static int findPdfHeaderOffset(String raw) {
        int limit = Math.min(raw.length(), 1024);
        int idx = raw.indexOf("%PDF-");
        return (idx >= 0 && idx < limit) ? idx : -1;
    }

    private static String extractVersion(String raw, int headerIdx) {
        int start = headerIdx + 5;
        int end = Math.min(raw.length(), start + 8);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            char ch = raw.charAt(i);
            if ((ch >= '0' && ch <= '9') || ch == '.') {
                sb.append(ch);
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private static int countPages(String raw) {
        int count = 0;
        Matcher pageMatcher = PAGE_TYPE_PATTERN.matcher(raw);
        while (pageMatcher.find()) {
            count++;
        }
        if (count > 0) {
            return count;
        }
        int best = 0;
        Matcher treeMatcher = PAGES_COUNT_PATTERN.matcher(raw);
        while (treeMatcher.find()) {
            try {
                best = Math.max(best, Integer.parseInt(treeMatcher.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    private static String extractInfoString(String raw, String key) {
        int pos = 0;
        while (pos < raw.length()) {
            int idx = raw.indexOf(key, pos);
            if (idx < 0) {
                return "";
            }
            int afterKey = idx + key.length();
            if (afterKey < raw.length()) {
                char nextChar = raw.charAt(afterKey);
                if (!Character.isWhitespace(nextChar) && nextChar != '(' && nextChar != '<') {
                    pos = afterKey;
                    continue;
                }
            }
            int p = skipWhitespace(raw, afterKey);
            if (p < raw.length()) {
                if (raw.charAt(p) == '(') {
                    int[] endOut = new int[1];
                    String decoded = readLiteralString(raw, p, endOut);
                    if (!decoded.isBlank()) {
                        return decoded.trim();
                    }
                } else if (raw.charAt(p) == '<' && (p + 1 >= raw.length() || raw.charAt(p + 1) != '<')) {
                    String decoded = readHexString(raw, p);
                    if (!decoded.isBlank()) {
                        return decoded.trim();
                    }
                }
            }
            pos = afterKey;
        }
        return "";
    }

    private static void scanStreams(
            byte[] pdfBytes,
            String raw,
            StringBuilder textOut,
            List<byte[]> jpegs) {
        int searchFrom = 0;
        while (searchFrom < raw.length()) {
            if (textOut.length() >= MAX_EXTRACTED_TEXT_CHARS && jpegs.size() >= MAX_EMBEDDED_JPEGS) {
                return;
            }
            int streamKw = findKeyword(raw, "stream", searchFrom);
            if (streamKw < 0) {
                return;
            }
            int dataStart = streamKw + 6;
            if (dataStart < raw.length() && raw.charAt(dataStart) == '\r') {
                dataStart++;
            }
            if (dataStart < raw.length() && raw.charAt(dataStart) == '\n') {
                dataStart++;
            } else if (raw.charAt(streamKw + 6) != '\r') {
                // Not a valid stream delimiter
                searchFrom = streamKw + 6;
                continue;
            }

            int endKw = findKeyword(raw, "endstream", dataStart);
            if (endKw < 0) {
                return;
            }
            int dataEnd = endKw;
            while (dataEnd > dataStart && (raw.charAt(dataEnd - 1) == '\n' || raw.charAt(dataEnd - 1) == '\r')) {
                dataEnd--;
            }

            String dictWindow = raw.substring(Math.max(0, streamKw - 600), streamKw);
            int streamLen = dataEnd - dataStart;

            if (streamLen > 0) {
                if (dictWindow.contains("/DCTDecode")) {
                    if (jpegs.size() < MAX_EMBEDDED_JPEGS
                            && streamLen >= 256
                            && streamLen <= MAX_EMBEDDED_JPEG_BYTES
                            && looksLikeJpeg(pdfBytes, dataStart, streamLen)) {
                        jpegs.add(Arrays.copyOfRange(pdfBytes, dataStart, dataEnd));
                    }
                } else if (textOut.length() < MAX_EXTRACTED_TEXT_CHARS && !dictWindow.contains("/Subtype /Image")) {
                    byte[] decoded = null;
                    if (dictWindow.contains("/FlateDecode")) {
                        decoded = inflateBounded(pdfBytes, dataStart, streamLen);
                    } else if (!dictWindow.contains("/Filter")) {
                        decoded = Arrays.copyOfRange(pdfBytes, dataStart, Math.min(dataEnd, dataStart + MAX_STREAM_DECODE_BYTES));
                    }
                    if (decoded != null && decoded.length > 0) {
                        extractTextOperators(new String(decoded, StandardCharsets.ISO_8859_1), textOut);
                    }
                }
            }
            searchFrom = endKw + 9;
        }
    }

    private static int findKeyword(String raw, String keyword, int fromIdx) {
        int pos = fromIdx;
        int kwLen = keyword.length();
        while (pos < raw.length()) {
            int idx = raw.indexOf(keyword, pos);
            if (idx < 0) {
                return -1;
            }
            boolean leftOk = idx == 0 || !isPdfWordChar(raw.charAt(idx - 1));
            int rightIdx = idx + kwLen;
            boolean rightOk = rightIdx >= raw.length() || !isPdfWordChar(raw.charAt(rightIdx));
            if (leftOk && rightOk) {
                return idx;
            }
            pos = idx + kwLen;
        }
        return -1;
    }

    private static boolean isPdfWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '/';
    }

    private static boolean looksLikeJpeg(byte[] bytes, int offset, int len) {
        return len >= 4
                && (bytes[offset] & 0xFF) == 0xFF
                && (bytes[offset + 1] & 0xFF) == 0xD8
                && (bytes[offset + 2] & 0xFF) == 0xFF;
    }

    private static byte[] inflateBounded(byte[] data, int offset, int length) {
        if (length <= 0 || length > MAX_STREAM_DECODE_BYTES) {
            return null;
        }
        Inflater inflater = new Inflater();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(length * 3, 65536))) {
            inflater.setInput(data, offset, length);
            byte[] buf = new byte[8192];
            int total = 0;
            while (!inflater.finished() && total < MAX_STREAM_DECODE_BYTES) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                } else {
                    int writable = Math.min(n, MAX_STREAM_DECODE_BYTES - total);
                    out.write(buf, 0, writable);
                    total += writable;
                }
            }
            return out.toByteArray();
        } catch (DataFormatException | RuntimeException e) {
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    private static void extractTextOperators(String streamText, StringBuilder out) {
        if (!streamText.contains("BT") && !streamText.contains("Tj") && !streamText.contains("TJ")) {
            return;
        }
        int len = streamText.length();
        int i = 0;
        int lineChars = 0;
        while (i < len && out.length() < MAX_EXTRACTED_TEXT_CHARS) {
            char ch = streamText.charAt(i);
            if (ch == '(') {
                int[] nextOut = new int[1];
                String literal = readLiteralString(streamText, i, nextOut);
                int after = skipWhitespace(streamText, nextOut[0]);
                if (matchesOperator(streamText, after, "Tj")
                        || matchesOperator(streamText, after, "'")
                        || matchesOperator(streamText, after, "\"")) {
                    if (!literal.isEmpty()) {
                        appendReadablePiece(out, literal);
                        lineChars += literal.length();
                        if (matchesOperator(streamText, after, "'") || matchesOperator(streamText, after, "\"")) {
                            out.append('\n');
                            lineChars = 0;
                        }
                    }
                }
                i = nextOut[0];
                continue;
            }
            if (ch == '[') {
                int arrayEnd = findMatchingBracket(streamText, i);
                int after = skipWhitespace(streamText, arrayEnd + 1);
                if (arrayEnd > i && matchesOperator(streamText, after, "TJ")) {
                    StringBuilder chunk = new StringBuilder();
                    int p = i + 1;
                    while (p < arrayEnd) {
                        if (streamText.charAt(p) == '(') {
                            int[] nextOut = new int[1];
                            chunk.append(readLiteralString(streamText, p, nextOut));
                            p = nextOut[0];
                        } else {
                            p++;
                        }
                    }
                    String assembled = chunk.toString();
                    if (!assembled.isEmpty()) {
                        appendReadablePiece(out, assembled);
                        lineChars += assembled.length();
                    }
                    i = arrayEnd + 1;
                    continue;
                }
            }
            if (lineChars > 0 && i + 2 <= len && streamText.startsWith("ET", i)
                    && (i == 0 || Character.isWhitespace(streamText.charAt(i - 1)))) {
                out.append('\n');
                lineChars = 0;
                i += 2;
                continue;
            }
            if (lineChars > 0 && i + 2 <= len && (streamText.startsWith("T*", i) || streamText.startsWith("Td", i) || streamText.startsWith("TD", i))
                    && (i == 0 || Character.isWhitespace(streamText.charAt(i - 1)))
                    && (i + 2 == len || Character.isWhitespace(streamText.charAt(i + 2)) || streamText.charAt(i + 2) == '(' || streamText.charAt(i + 2) == '[')) {
                out.append('\n');
                lineChars = 0;
                i += 2;
                continue;
            }
            i++;
        }
        if (lineChars > 0 && out.length() < MAX_EXTRACTED_TEXT_CHARS) {
            out.append('\n');
        }
    }

    private static void appendReadablePiece(StringBuilder out, String piece) {
        for (int i = 0; i < piece.length() && out.length() < MAX_EXTRACTED_TEXT_CHARS; i++) {
            char c = piece.charAt(i);
            if (c >= 32 || c == '\t' || c == '\n') {
                out.append(c);
            }
        }
    }

    private static int findMatchingBracket(String s, int openIdx) {
        int depth = 0;
        boolean inParen = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '(') {
                inParen = true;
            } else if (c == ')') {
                inParen = false;
            } else if (!inParen) {
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private static boolean matchesOperator(String s, int idx, String op) {
        if (idx < 0 || idx + op.length() > s.length() || !s.startsWith(op, idx)) {
            return false;
        }
        int end = idx + op.length();
        return end == s.length() || Character.isWhitespace(s.charAt(end)) || s.charAt(end) == '(' || s.charAt(end) == '[';
    }

    private static int skipWhitespace(String s, int idx) {
        int p = idx;
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) {
            p++;
        }
        return p;
    }

    private static String readLiteralString(String s, int openParenIdx, int[] nextIndexOut) {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        int i = openParenIdx + 1;
        while (i < s.length() && depth > 0) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char esc = s.charAt(i + 1);
                switch (esc) {
                    case 'n' -> { sb.append('\n'); i += 2; }
                    case 'r' -> { sb.append('\r'); i += 2; }
                    case 't' -> { sb.append('\t'); i += 2; }
                    case 'b' -> { sb.append('\b'); i += 2; }
                    case 'f' -> { sb.append('\f'); i += 2; }
                    case '(', ')', '\\' -> { sb.append(esc); i += 2; }
                    case '\r' -> {
                        i += (i + 2 < s.length() && s.charAt(i + 2) == '\n') ? 3 : 2;
                    }
                    case '\n' -> i += 2;
                    default -> {
                        if (esc >= '0' && esc <= '7') {
                            int oct = esc - '0';
                            int consumed = 1;
                            while (consumed < 3 && i + 1 + consumed < s.length()) {
                                char oc = s.charAt(i + 1 + consumed);
                                if (oc >= '0' && oc <= '7') {
                                    oct = (oct << 3) | (oc - '0');
                                    consumed++;
                                } else {
                                    break;
                                }
                            }
                            sb.append((char) oct);
                            i += 1 + consumed;
                        } else {
                            sb.append(esc);
                            i += 2;
                        }
                    }
                }
            } else if (ch == '(') {
                depth++;
                sb.append(ch);
                i++;
            } else if (ch == ')') {
                depth--;
                if (depth > 0) {
                    sb.append(ch);
                }
                i++;
            } else {
                sb.append(ch);
                i++;
            }
        }
        if (nextIndexOut != null) {
            nextIndexOut[0] = i;
        }
        if (sb.length() >= 2 && sb.charAt(0) == (char) 0xFE && sb.charAt(1) == (char) 0xFF) {
            byte[] utf16 = new byte[sb.length() - 2];
            for (int k = 2; k < sb.length(); k++) {
                utf16[k - 2] = (byte) sb.charAt(k);
            }
            return new String(utf16, StandardCharsets.UTF_16BE);
        }
        return sb.toString();
    }

    private static String readHexString(String s, int openAngleIdx) {
        int closeIdx = s.indexOf('>', openAngleIdx + 1);
        if (closeIdx <= openAngleIdx + 1) {
            return "";
        }
        String hex = s.substring(openAngleIdx + 1, closeIdx).replaceAll("\\s+", "");
        if ((hex.length() & 1) == 1) {
            hex = hex + "0";
        }
        byte[] raw = new byte[hex.length() / 2];
        try {
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2).toLowerCase(Locale.ROOT), 16);
            }
        } catch (NumberFormatException e) {
            return "";
        }
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE);
        }
        return new String(raw, StandardCharsets.UTF_8);
    }
}
