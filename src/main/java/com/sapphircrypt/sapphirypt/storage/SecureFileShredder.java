package com.sapphircrypt.sapphirypt.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Best-effort secure deletion utility for optional post-import source file shredding.
 * Never follows symbolic links to avoid unintended external targets.
 */
public final class SecureFileShredder {
    private static final int CHUNK_SIZE = 64 * 1024;
    private static final SecureRandom RNG = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private SecureFileShredder() {
    }

    /**
     * Overwrites a regular file with random data followed by zeros, truncates, renames to a
     * random filename, and deletes it. Does nothing for non-existent paths or symbolic links.
     */
    public static boolean shredFile(Path file) {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            long size = Files.size(file);
            if (size > 0) {
                try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                    byte[] buf = new byte[(int) Math.min(CHUNK_SIZE, size)];

                    // Pass 1: cryptographically random overwrite
                    long pos = 0L;
                    long remaining = size;
                    while (remaining > 0L) {
                        RNG.nextBytes(buf);
                        int len = (int) Math.min(buf.length, remaining);
                        writeFully(ch, ByteBuffer.wrap(buf, 0, len), pos);
                        pos += len;
                        remaining -= len;
                    }
                    ch.force(true);

                    // Pass 2: zero-fill + truncate
                    Arrays.fill(buf, (byte) 0);
                    pos = 0L;
                    remaining = size;
                    while (remaining > 0L) {
                        int len = (int) Math.min(buf.length, remaining);
                        writeFully(ch, ByteBuffer.wrap(buf, 0, len), pos);
                        pos += len;
                        remaining -= len;
                    }
                    ch.truncate(0L);
                    ch.force(true);
                }
            }
            Path toDelete = bestEffortScrubName(file);
            Files.deleteIfExists(toDelete);
            return !Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Recursively shreds regular files within {@code dir} without following symlinks, then deletes empty folders.
     *
     * @return number of files shredded
     */
    public static int shredDirectory(Path dir) {
        if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        AtomicInteger shreddedCount = new AtomicInteger();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(d)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(file)) {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException ignored) {
                        }
                    } else if (attrs.isRegularFile()) {
                        if (shredFile(file)) {
                            shreddedCount.incrementAndGet();
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    try {
                        Files.deleteIfExists(d);
                    } catch (IOException ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
        return shreddedCount.get();
    }

    private static Path bestEffortScrubName(Path original) {
        Path parent = original.getParent();
        if (parent == null) {
            return original;
        }
        byte[] rnd = new byte[8];
        RNG.nextBytes(rnd);
        Path candidate = parent.resolve("." + HEX.formatHex(rnd) + ".del");
        try {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Files.move(original, candidate, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
        return original;
    }

    private static void writeFully(FileChannel ch, ByteBuffer buf, long pos) throws IOException {
        long cursor = pos;
        while (buf.hasRemaining()) {
            int written = ch.write(buf, cursor);
            if (written <= 0) {
                throw new IOException("Dosya üzerine yazılamadı");
            }
            cursor += written;
        }
    }
}
