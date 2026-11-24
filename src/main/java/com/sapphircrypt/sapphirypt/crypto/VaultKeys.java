package com.sapphircrypt.sapphirypt.crypto;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the unlocked 256-bit DEK and purpose-separated HKDF-SHA-256 subkeys.
 * Every AES key is strictly bound to one cryptographic purpose and wiped on close.
 */
public final class VaultKeys implements AutoCloseable {
    public static final int DEK_BYTES = 32;

    private static final String INFO_PASSWORD_WRAP = "sapphirypt:v1:slot:password-wrap";
    private static final String INFO_RECOVERY_WRAP = "sapphirypt:v1:slot:recovery-wrap";
    private static final String INFO_DATA_KEY = "sapphirypt:v1:key:data";
    private static final String INFO_METADATA_KEY = "sapphirypt:v1:key:metadata";
    private static final String INFO_COMMIT_KEY = "sapphirypt:v1:key:commit";
    private static final String INFO_NONCE_STATE_KEY = "sapphirypt:v1:key:nonce-state";

    private final byte[] dek;
    private final byte[] dataKey;
    private final byte[] metadataKey;
    private final byte[] commitKey;
    private final byte[] nonceStateKey;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private VaultKeys(byte[] dek, byte[] dataKey, byte[] metadataKey, byte[] commitKey, byte[] nonceStateKey) {
        this.dek = dek;
        this.dataKey = dataKey;
        this.metadataKey = metadataKey;
        this.commitKey = commitKey;
        this.nonceStateKey = nonceStateKey;
    }

    public static VaultKeys fromDek(byte[] dekInput, byte[] containerId) {
        if (dekInput == null || dekInput.length != DEK_BYTES) {
            throw new IllegalArgumentException("DEK must be 32 bytes");
        }
        if (containerId == null || containerId.length != 16) {
            throw new IllegalArgumentException("Container ID must be 16 bytes");
        }
        byte[] dekCopy = Arrays.copyOf(dekInput, DEK_BYTES);
        byte[] dataKey = null;
        byte[] metadataKey = null;
        byte[] commitKey = null;
        byte[] nonceStateKey = null;
        try {
            dataKey = HkdfSha256.deriveKey(dekCopy, containerId, INFO_DATA_KEY, DEK_BYTES);
            metadataKey = HkdfSha256.deriveKey(dekCopy, containerId, INFO_METADATA_KEY, DEK_BYTES);
            commitKey = HkdfSha256.deriveKey(dekCopy, containerId, INFO_COMMIT_KEY, DEK_BYTES);
            nonceStateKey = HkdfSha256.deriveKey(dekCopy, containerId, INFO_NONCE_STATE_KEY, DEK_BYTES);
            return new VaultKeys(dekCopy, dataKey, metadataKey, commitKey, nonceStateKey);
        } catch (RuntimeException e) {
            SensitiveBytes.wipe(dekCopy);
            SensitiveBytes.wipe(dataKey);
            SensitiveBytes.wipe(metadataKey);
            SensitiveBytes.wipe(commitKey);
            SensitiveBytes.wipe(nonceStateKey);
            throw e;
        }
    }

    public static byte[] derivePasswordWrapKey(char[] password, byte[] passwordSalt, Argon2Params params, byte[] containerId) {
        byte[] argonOut = Argon2Kdf.derive(password, passwordSalt, params);
        try {
            return HkdfSha256.deriveKey(argonOut, containerId, INFO_PASSWORD_WRAP, DEK_BYTES);
        } finally {
            SensitiveBytes.wipe(argonOut);
        }
    }

    public static byte[] deriveRecoveryWrapKey(byte[] recoverySecret, byte[] recoverySalt, byte[] containerId) {
        if (recoverySecret == null || recoverySecret.length != DEK_BYTES) {
            throw new IllegalArgumentException("Recovery secret must be 32 bytes");
        }
        if (recoverySalt == null || recoverySalt.length != Argon2Kdf.SALT_BYTES) {
            throw new IllegalArgumentException("Recovery salt must be 16 bytes");
        }
        byte[] combinedSalt = new byte[containerId.length + recoverySalt.length];
        System.arraycopy(containerId, 0, combinedSalt, 0, containerId.length);
        System.arraycopy(recoverySalt, 0, combinedSalt, containerId.length, recoverySalt.length);
        try {
            return HkdfSha256.deriveKey(recoverySecret, combinedSalt, INFO_RECOVERY_WRAP, DEK_BYTES);
        } finally {
            SensitiveBytes.wipe(combinedSalt);
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Kasa anahtarları temizlendi (kasa kilitli)");
    }

    public byte[] dekCopy() {
        ensureOpen();
        return Arrays.copyOf(dek, dek.length);
    }

    public byte[] dataKey() {
        ensureOpen();
        return dataKey;
    }

    public byte[] metadataKey() {
        ensureOpen();
        return metadataKey;
    }

    public byte[] commitKey() {
        ensureOpen();
        return commitKey;
    }

    public byte[] nonceStateKey() {
        ensureOpen();
        return nonceStateKey;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            SensitiveBytes.wipe(dek);
            SensitiveBytes.wipe(dataKey);
            SensitiveBytes.wipe(metadataKey);
            SensitiveBytes.wipe(commitKey);
            SensitiveBytes.wipe(nonceStateKey);
        }
    }
}
