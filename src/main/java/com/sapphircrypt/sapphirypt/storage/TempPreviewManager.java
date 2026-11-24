package com.sapphircrypt.sapphirypt.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages temporary plaintext files created when opening vault files in external OS applications.
 * Uses a private user-scoped directory and best-effort cleanup on startup, vault lock, and exit.
 */
public final class TempPreviewManager {
    private static final long MAX_BEST_EFFORT_ZERO_BYTES = 16L * 1024L * 1024L;
    private final Set<Path> trackedFiles = ConcurrentHashMap.newKeySet();

    public static Path tempDirectory() {
        return Path.of(System.getProperty("user.home"), ".sapphirypt", "tmp");
    }

    public static void sweepOrphanTempFiles() {
        Path dir = tempDirectory();
        if (!Files.isDirectory(dir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) bestEffortOverwriteAndDelete(file);

            }
        } catch (IOException ignored) {
            // Best-effort startup sweep.
        }
    }

    public Path createTempFile(String originalFileName) throws IOException {
        Path dir = tempDirectory();
        Files.createDirectories(dir);
        setOwnerOnlyDirPermissions(dir);

        String sanitized = sanitizeName(originalFileName);
        String uniqueName = "sapphirypt-" + UUID.randomUUID().toString().substring(0, 8) + "-" + sanitized;
        Path target = dir.resolve(uniqueName);
        Files.createFile(target);
        setOwnerOnlyFilePermissions(target);
        target.toFile().deleteOnExit();
        trackedFiles.add(target);
        return target;
    }

    public void cleanupAll() {
        for (Path path : trackedFiles) {
            bestEffortOverwriteAndDelete(path);
        }
        trackedFiles.clear();
    }

    private static void bestEffortOverwriteAndDelete(Path path) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            long size = Files.size(path);
            if (size > 0 && size <= MAX_BEST_EFFORT_ZERO_BYTES) {
                try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    byte[] zeros = new byte[(int) Math.min(65536, size)];
                    long remaining = size;
                    long pos = 0;
                    while (remaining > 0) {
                        int writeLen = (int) Math.min(zeros.length, remaining);
                        ch.write(ByteBuffer.wrap(zeros, 0, writeLen), pos);
                        pos += writeLen;
                        remaining -= writeLen;
                    }
                    ch.force(false);
                } catch (IOException ignored) {
                    // External app may hold an exclusive lock on Windows.
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Will be swept on next startup if currently locked by external viewer.
        }
    }

    private static String sanitizeName(String original) {
        if (original == null || original.isBlank()) return "dosya.bin";

        String base = Path.of(original).getFileName().toString();
        String cleaned = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        return cleaned.isBlank() ? "dosya.bin" : cleaned;
    }

    private static void setOwnerOnlyDirPermissions(Path dir) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(dir, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static void setOwnerOnlyFilePermissions(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }
}
