package com.sapphircrypt.sapphirypt.crypto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Independent 100-byte binary recovery key file ({@code .key}).
 * Contains the 32-byte recovery secret (NOT the DEK itself) and container binding metadata.
 */
public final class RecoveryKeyFile implements AutoCloseable {
    public static final int FILE_SIZE = 100;
    public static final short KEY_FILE_VERSION = 1;
    public static final short VAULT_FORMAT_VERSION = 1;
    public static final int SECRET_BYTES = 32;
    private static final byte[] MAGIC = "SAPPKEY1".getBytes(StandardCharsets.US_ASCII);

    private final short keyFileVersion;
    private final short vaultFormatVersion;
    private final byte[] containerId;
    private final byte[] recoverySecret;
    private final long createdAtEpochMillis;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private RecoveryKeyFile(short keyFileVersion, short vaultFormatVersion, byte[] containerId, byte[] recoverySecret, long createdAtEpochMillis) {
        this.keyFileVersion = keyFileVersion;
        this.vaultFormatVersion = vaultFormatVersion;
        this.containerId = Arrays.copyOf(containerId, 16);
        this.recoverySecret = Arrays.copyOf(recoverySecret, SECRET_BYTES);
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public static RecoveryKeyFile generate(byte[] containerId) {
        if (containerId == null || containerId.length != 16) throw new IllegalArgumentException("Container ID must be 16 bytes");

        byte[] secret = SensitiveBytes.randomBytes(SECRET_BYTES);
        try {
            return new RecoveryKeyFile(KEY_FILE_VERSION, VAULT_FORMAT_VERSION, containerId, secret, System.currentTimeMillis());
        } finally {
            SensitiveBytes.wipe(secret);
        }
    }

    public byte[] encode() {
        ensureOpen();
        ByteBuffer buf = ByteBuffer.allocate(FILE_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC);
        buf.putShort(keyFileVersion);
        buf.putShort(vaultFormatVersion);
        buf.put(containerId);
        buf.put(recoverySecret);
        buf.putLong(createdAtEpochMillis);
        byte[] body = new byte[68];
        buf.position(0);
        buf.get(body);
        byte[] checksum = SensitiveBytes.sha256(body);
        SensitiveBytes.wipe(body);
        buf.position(68);
        buf.put(checksum);
        return buf.array();
    }

    public void writeTo(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        byte[] encoded = encode();
        try {
            Files.write(path, encoded);
            setOwnerOnlyPermissions(path);
        } finally {
            SensitiveBytes.wipe(encoded);
        }
    }

    public static RecoveryKeyFile readFrom(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        try {
            return decode(bytes);
        } finally {
            SensitiveBytes.wipe(bytes);
        }
    }

    public static RecoveryKeyFile decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length != FILE_SIZE) {
            throw new IOException("Geçersiz kurtarma anahtarı dosya boyutu");
        }
        byte[] magic = Arrays.copyOfRange(bytes, 0, 8);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Geçersiz kurtarma anahtarı dosyası (başlık uyumsuz)");
        }
        byte[] body = Arrays.copyOfRange(bytes, 0, 68);
        byte[] expectedChecksum = SensitiveBytes.sha256(body);
        SensitiveBytes.wipe(body);
        byte[] actualChecksum = Arrays.copyOfRange(bytes, 68, FILE_SIZE);
        if (!SensitiveBytes.constantTimeEquals(expectedChecksum, actualChecksum)) {
            throw new IOException("Kurtarma anahtarı dosyası bozulmuş (SHA-256 doğrulaması başarısız)");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buf.position(8);
        short keyVer = buf.getShort();
        short vaultVer = buf.getShort();
        if (keyVer != KEY_FILE_VERSION) {
            throw new IOException("Desteklenmeyen kurtarma anahtarı sürümü: " + keyVer);
        }
        if (vaultVer != VAULT_FORMAT_VERSION) {
            throw new IOException("Desteklenmeyen kasa format sürümü: " + vaultVer);
        }
        byte[] cid = new byte[16];
        buf.get(cid);
        byte[] secret = new byte[SECRET_BYTES];
        buf.get(secret);
        long created = buf.getLong();
        try {
            return new RecoveryKeyFile(keyVer, vaultVer, cid, secret, created);
        } finally {
            SensitiveBytes.wipe(secret);
        }
    }

    private static void setOwnerOnlyPermissions(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems (e.g., Windows NTFS without POSIX view) fall back cleanly.
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Kurtarma anahtarı belleği temizlendi");
        }
    }

    public short keyFileVersion() {
        return keyFileVersion;
    }

    public short vaultFormatVersion() {
        return vaultFormatVersion;
    }

    public byte[] containerId() {
        return Arrays.copyOf(containerId, containerId.length);
    }

    public byte[] recoverySecretCopy() {
        ensureOpen();
        return Arrays.copyOf(recoverySecret, recoverySecret.length);
    }

    public long createdAtEpochMillis() {
        return createdAtEpochMillis;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            SensitiveBytes.wipe(recoverySecret);
        }
    }
}
