package com.sapphircrypt.sapphirypt.storage;

import com.sapphircrypt.sapphirypt.crypto.SensitiveBytes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages temporary plaintext files created when opening vault files in external OS applications.
 * Uses a 3-level CSPRNG-isolated directory layout (<vault>/<session>/<open>/<random>.<ext>),
 * OS owner-only permissions / disabled content indexing, session locking against concurrent sweeps,
 * automatic live sync-back when external edits are saved, and best-effort zero-fill cleanup.
 */
public final class TempPreviewManager {
    @FunctionalInterface
    public interface ExternalEditHandler {
        void onFileModified(UUID entryId, Path tempFile) throws Exception;
    }

    private static final class WatchedFile {
        final Path file;
        final UUID entryId;
        final ExternalEditHandler handler;
        long lastSyncedMtimeMillis;
        long lastSyncedSize;
        byte[] lastSyncedSha256;

        WatchedFile(Path file, UUID entryId, ExternalEditHandler handler,
                    long mtimeMillis, long size, byte[] sha256) {
            this.file = file;
            this.entryId = entryId;
            this.handler = handler;
            this.lastSyncedMtimeMillis = mtimeMillis;
            this.lastSyncedSize = size;
            this.lastSyncedSha256 = sha256;
        }
    }

    private static final long MAX_BEST_EFFORT_ZERO_BYTES = 16L * 1024L * 1024L;
    private static final long WATCH_POLL_MILLIS = 1200L;
    private static final long EDIT_DEBOUNCE_MILLIS = 350L;
    private static final String SESSION_LOCK_FILE = ".session.lock";
    private static final SecureRandom RNG = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final Path baseDir;
    private final Object sessionInitLock = new Object();
    private final Set<Path> trackedFiles = ConcurrentHashMap.newKeySet();
    private final Set<Path> trackedOpenDirs = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Path, WatchedFile> watchedFiles = new ConcurrentHashMap<>();

    private Path vaultDir;
    private Path sessionDir;
    private FileChannel sessionLockChannel;
    private FileLock sessionLock;
    private ScheduledExecutorService watcherExecutor;

    public TempPreviewManager() {
        this(tempDirectory());
    }

    TempPreviewManager(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    public static Path tempDirectory() {
        return Path.of(System.getProperty("user.home"), ".sapphirypt", "tmp");
    }

    public static void sweepOrphanTempFiles() {
        sweepOrphanTempFiles(tempDirectory());
    }

    static void sweepOrphanTempFiles(Path targetBaseDir) {
        if (targetBaseDir == null || !Files.isDirectory(targetBaseDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetBaseDir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    bestEffortOverwriteAndDelete(entry);
                } else if (Files.isDirectory(entry)) {
                    sweepVaultTempDirectory(entry);
                }
            }
        } catch (IOException ignored) {
            // Best-effort startup sweep.
        }
    }

    private static void sweepVaultTempDirectory(Path vaultDirCandidate) {
        try (DirectoryStream<Path> sessions = Files.newDirectoryStream(vaultDirCandidate)) {
            for (Path sessionCandidate : sessions) {
                if (Files.isRegularFile(sessionCandidate)) {
                    bestEffortOverwriteAndDelete(sessionCandidate);
                } else if (Files.isDirectory(sessionCandidate)) {
                    if (!isSessionLockedByAnotherProcess(sessionCandidate)) {
                        recursivelyOverwriteAndDeleteDir(sessionCandidate);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        try {
            if (isDirectoryEmpty(vaultDirCandidate)) {
                Files.deleteIfExists(vaultDirCandidate);
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean isSessionLockedByAnotherProcess(Path sessionDirCandidate) {
        Path lockFile = sessionDirCandidate.resolve(SESSION_LOCK_FILE);
        if (!Files.exists(lockFile)) {
            return false;
        }
        try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            FileLock lock = ch.tryLock();
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        } catch (OverlappingFileLockException | IOException e) {
            return true;
        }
    }

    public Path createTempFile(String originalFileName) throws IOException {
        Path openDir;
        synchronized (sessionInitLock) {
            ensureSessionInitializedLocked();
            openDir = sessionDir.resolve(randomTokenHex(8));
            Files.createDirectories(openDir);
            hardenDirectory(openDir);
        }

        String safeExtension = extractSafeExtension(originalFileName);
        Path target = openDir.resolve(randomTokenHex(8) + safeExtension);
        Files.createFile(target);
        hardenFile(target);
        target.toFile().deleteOnExit();

        trackedOpenDirs.add(openDir);
        trackedFiles.add(target);
        return target;
    }

    public void hardenFile(Path file) {
        if (file == null || !Files.exists(file)) {
            return;
        }
        setOwnerOnlyFilePermissions(file);
        applyOwnerOnlyAcl(file, false);
        applyDosPrivacyAttributes(file, false);
    }

    public void watchExternalFile(Path tempFile, UUID entryId, ExternalEditHandler handler) throws IOException {
        if (tempFile == null || entryId == null || handler == null || !Files.isRegularFile(tempFile)) {
            return;
        }
        Path normalized = tempFile.toAbsolutePath().normalize();
        long mtime = Files.getLastModifiedTime(normalized).toMillis();
        long size = Files.size(normalized);
        byte[] sha = computeFileSha256(normalized);

        WatchedFile wf = new WatchedFile(normalized, entryId, handler, mtime, size, sha);
        watchedFiles.put(normalized, wf);
        ensureWatcherStarted();
    }

    public void syncPendingEditsNow() {
        for (WatchedFile wf : watchedFiles.values()) {
            try {
                syncWatchedFile(wf, false);
            } catch (Exception ignored) {
            }
        }
    }

    public void cleanupAll() {
        synchronized (sessionInitLock) {
            if (watcherExecutor != null) {
                watcherExecutor.shutdownNow();
                watcherExecutor = null;
            }
            watchedFiles.clear();

            for (Path path : new ArrayList<>(trackedFiles)) {
                bestEffortOverwriteAndDelete(path);
            }
            trackedFiles.clear();

            for (Path openDir : new ArrayList<>(trackedOpenDirs)) {
                recursivelyOverwriteAndDeleteDir(openDir);
            }
            trackedOpenDirs.clear();

            releaseSessionLockLocked();
            if (sessionDir != null) {
                recursivelyOverwriteAndDeleteDir(sessionDir);
                sessionDir = null;
            }
            if (vaultDir != null) {
                try {
                    if (isDirectoryEmpty(vaultDir)) {
                        Files.deleteIfExists(vaultDir);
                    }
                } catch (IOException ignored) {
                }
                vaultDir = null;
            }
        }
    }

    private void ensureSessionInitializedLocked() throws IOException {
        if (sessionDir != null) {
            return;
        }
        Files.createDirectories(baseDir);
        hardenDirectory(baseDir);

        Path vDir = baseDir.resolve(randomTokenHex(8));
        Files.createDirectories(vDir);
        hardenDirectory(vDir);

        Path sDir = vDir.resolve(randomTokenHex(8));
        Files.createDirectories(sDir);
        hardenDirectory(sDir);

        Path lockPath = sDir.resolve(SESSION_LOCK_FILE);
        FileChannel ch = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = ch.tryLock();
        if (lock == null) {
            ch.close();
            throw new IOException("Geçici oturum kilidi alınamadı: " + lockPath);
        }
        hardenFile(lockPath);
        lockPath.toFile().deleteOnExit();

        this.vaultDir = vDir;
        this.sessionDir = sDir;
        this.sessionLockChannel = ch;
        this.sessionLock = lock;
    }

    private void releaseSessionLockLocked() {
        if (sessionLock != null) {
            try {
                sessionLock.release();
            } catch (IOException ignored) {
            }
            sessionLock = null;
        }
        if (sessionLockChannel != null) {
            try {
                sessionLockChannel.close();
            } catch (IOException ignored) {
            }
            sessionLockChannel = null;
        }
        if (sessionDir != null) {
            try {
                Files.deleteIfExists(sessionDir.resolve(SESSION_LOCK_FILE));
            } catch (IOException ignored) {
            }
        }
    }

    private void ensureWatcherStarted() {
        synchronized (sessionInitLock) {
            if (watcherExecutor != null && !watcherExecutor.isShutdown()) {
                return;
            }
            watcherExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sapphirypt-ext-watch");
                t.setDaemon(true);
                return t;
            });
            watcherExecutor.scheduleWithFixedDelay(
                    this::pollWatchedFiles, WATCH_POLL_MILLIS, WATCH_POLL_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void pollWatchedFiles() {
        for (WatchedFile wf : watchedFiles.values()) {
            try {
                syncWatchedFile(wf, true);
            } catch (Exception ignored) {
                // Keep watcher alive across temporary lock / I/O errors.
            }
        }
    }

    private void syncWatchedFile(WatchedFile wf, boolean debounce) {
        synchronized (wf) {
            if (!Files.isRegularFile(wf.file)) {
                return;
            }
            try {
                long mtime1 = Files.getLastModifiedTime(wf.file).toMillis();
                long size1 = Files.size(wf.file);
                if (mtime1 == wf.lastSyncedMtimeMillis && size1 == wf.lastSyncedSize) {
                    return;
                }
                if (debounce) {
                    Thread.sleep(EDIT_DEBOUNCE_MILLIS);
                    if (!Files.isRegularFile(wf.file)) {
                        return;
                    }
                    long mtime2 = Files.getLastModifiedTime(wf.file).toMillis();
                    long size2 = Files.size(wf.file);
                    if (mtime2 != mtime1 || size2 != size1) {
                        return;
                    }
                }
                byte[] currentSha = computeFileSha256(wf.file);
                if (SensitiveBytes.constantTimeEquals(currentSha, wf.lastSyncedSha256)) {
                    wf.lastSyncedMtimeMillis = mtime1;
                    wf.lastSyncedSize = size1;
                    return;
                }
                wf.handler.onFileModified(wf.entryId, wf.file);
                wf.lastSyncedSha256 = currentSha;
                wf.lastSyncedMtimeMillis = mtime1;
                wf.lastSyncedSize = size1;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Retry on next poll if external app holds an exclusive write lock or write is mid-flush.
            }
        }
    }

    private static byte[] computeFileSha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buf)) > 0) {
                    md.update(buf, 0, read);
                }
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void recursivelyOverwriteAndDeleteDir(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        if (Files.isRegularFile(dir)) {
            bestEffortOverwriteAndDelete(dir);
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    recursivelyOverwriteAndDeleteDir(child);
                } else {
                    bestEffortOverwriteAndDelete(child);
                }
            }
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
        }
    }

    private static boolean isDirectoryEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        }
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

    private static String randomTokenHex(int numBytes) {
        byte[] bytes = new byte[numBytes];
        RNG.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    private static String extractSafeExtension(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return ".bin";
        }
        String base = Path.of(originalFileName).getFileName().toString();
        int dot = base.lastIndexOf('.');
        if (dot <= 0 || dot >= base.length() - 1) {
            return ".bin";
        }
        String rawExt = base.substring(dot + 1).toLowerCase(Locale.ROOT);
        String cleanExt = rawExt.replaceAll("[^a-z0-9]", "");
        if (cleanExt.isEmpty()) {
            return ".bin";
        }
        if (cleanExt.length() > 12) {
            cleanExt = cleanExt.substring(0, 12);
        }
        return "." + cleanExt;
    }

    private static void hardenDirectory(Path dir) {
        setOwnerOnlyDirPermissions(dir);
        applyOwnerOnlyAcl(dir, true);
        applyDosPrivacyAttributes(dir, true);
    }

    private static void applyOwnerOnlyAcl(Path path, boolean isDirectory) {
        try {
            AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (aclView == null) {
                return;
            }
            UserPrincipal owner = Files.getOwner(path);
            AclEntry.Builder builder = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (isDirectory) {
                builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            }
            aclView.setAcl(List.of(builder.build()));
        } catch (IllegalArgumentException | UnsupportedOperationException | IOException ignored) {
        }
    }

    private static void applyDosPrivacyAttributes(Path path, boolean hideDirectory) {
        try {
            DosFileAttributeView dosView = Files.getFileAttributeView(path, DosFileAttributeView.class);
            if (dosView != null && hideDirectory) {
                dosView.setHidden(true);
            }
        } catch (IllegalArgumentException | UnsupportedOperationException | IOException ignored) {
        }
    }

    private static void setOwnerOnlyDirPermissions(Path dir) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(dir, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static void setOwnerOnlyFilePermissions(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }
}
