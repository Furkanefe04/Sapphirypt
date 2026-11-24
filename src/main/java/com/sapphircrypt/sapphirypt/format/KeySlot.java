package com.sapphircrypt.sapphirypt.format;

import com.sapphircrypt.sapphirypt.crypto.AadBuilder;
import com.sapphircrypt.sapphirypt.crypto.AesGcmCipher;
import com.sapphircrypt.sapphirypt.crypto.Argon2Kdf;
import com.sapphircrypt.sapphirypt.crypto.Argon2Params;
import com.sapphircrypt.sapphirypt.crypto.SensitiveBytes;
import com.sapphircrypt.sapphirypt.crypto.VaultKeys;
import com.sapphircrypt.sapphirypt.storage.VaultAuthenticationException;

import javax.crypto.AEADBadTagException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * 128-byte A/B dual-slot key wrapping entry for password or recovery key access to the DEK.
 */
public final class KeySlot {
    public static final byte TYPE_PASSWORD = 1;
    public static final byte TYPE_RECOVERY = 2;

    public static final byte SIDE_A = 0;
    public static final byte SIDE_B = 1;

    public static final byte STATE_EMPTY = 0;
    public static final byte STATE_ACTIVE = 1;

    public static final byte KDF_ARGON2ID = 1;
    public static final byte KDF_HKDF_RECOVERY = 2;

    private static final int WRAPPED_DEK_BYTES = VaultKeys.DEK_BYTES + AesGcmCipher.TAG_BYTES; // 48B

    private final byte slotType;
    private final byte slotSide;
    private final byte slotState;
    private final byte kdfType;
    private final long slotGeneration;
    private final Argon2Params argon2Params; // null for recovery slot
    private final byte[] salt;
    private final byte[] wrapNonce;
    private final byte[] wrappedDekAndTag;

    private KeySlot(byte slotType, byte slotSide, byte slotState, byte kdfType, long slotGeneration, Argon2Params argon2Params, byte[] salt, byte[] wrapNonce, byte[] wrappedDekAndTag) {
        this.slotType = slotType;
        this.slotSide = slotSide;
        this.slotState = slotState;
        this.kdfType = kdfType;
        this.slotGeneration = slotGeneration;
        this.argon2Params = argon2Params;
        this.salt = Arrays.copyOf(salt, Argon2Kdf.SALT_BYTES);
        this.wrapNonce = Arrays.copyOf(wrapNonce, AesGcmCipher.NONCE_BYTES);
        this.wrappedDekAndTag = Arrays.copyOf(wrappedDekAndTag, WRAPPED_DEK_BYTES);
    }

    public static KeySlot createPasswordSlot(short formatVersion, byte[] containerId, byte side, long generation, char[] password, Argon2Params params, byte[] dek) {
        if (params == null) throw new IllegalArgumentException("Argon2 parameters are required");

        params.validateForExecution();
        byte[] salt = SensitiveBytes.randomBytes(Argon2Kdf.SALT_BYTES);
        byte[] nonce = SensitiveBytes.randomBytes(AesGcmCipher.NONCE_BYTES);
        byte[] wrapKey = VaultKeys.derivePasswordWrapKey(password, salt, params, containerId);
        try {
            byte[] aad = AadBuilder.forPasswordSlot(formatVersion, containerId, side, generation, KDF_ARGON2ID, params, salt);
            byte[] wrapped = AesGcmCipher.encrypt(wrapKey, nonce, dek, aad);
            return new KeySlot(TYPE_PASSWORD, side, STATE_ACTIVE, KDF_ARGON2ID, generation, params, salt, nonce, wrapped);
        } finally {
            SensitiveBytes.wipe(wrapKey);
        }
    }

    public static KeySlot createRecoverySlot(short formatVersion, byte[] containerId, byte side, long generation, byte[] recoverySecret, byte[] dek) {
        byte[] salt = SensitiveBytes.randomBytes(Argon2Kdf.SALT_BYTES);
        byte[] nonce = SensitiveBytes.randomBytes(AesGcmCipher.NONCE_BYTES);
        byte[] wrapKey = VaultKeys.deriveRecoveryWrapKey(recoverySecret, salt, containerId);
        try {
            byte[] aad = AadBuilder.forRecoverySlot(formatVersion, containerId, side, generation, salt);
            byte[] wrapped = AesGcmCipher.encrypt(wrapKey, nonce, dek, aad);
            return new KeySlot(TYPE_RECOVERY, side, STATE_ACTIVE, KDF_HKDF_RECOVERY, generation, null, salt, nonce, wrapped);
        } finally {
            SensitiveBytes.wipe(wrapKey);
        }
    }

    public byte[] unwrapWithPassword(short formatVersion, byte[] containerId, char[] password) throws VaultAuthenticationException {
        if (slotType != TYPE_PASSWORD || slotState != STATE_ACTIVE || argon2Params == null) throw new VaultAuthenticationException("Parola slotu etkin değil");

        try {
            argon2Params.validateForExecution();
        } catch (IllegalArgumentException e) {
            throw new VaultAuthenticationException("Geçersiz KDF parametreleri: " + e.getMessage(), e);
        }
        byte[] wrapKey = VaultKeys.derivePasswordWrapKey(password, salt, argon2Params, containerId);
        try {
            byte[] aad = AadBuilder.forPasswordSlot(formatVersion, containerId, slotSide, slotGeneration, kdfType, argon2Params, salt);
            return AesGcmCipher.decrypt(wrapKey, wrapNonce, wrappedDekAndTag, aad);
        } catch (AEADBadTagException e) {
            throw new VaultAuthenticationException("Parola hatalı veya kasa parola slotu doğrulaması başarısız", e);
        } finally {
            SensitiveBytes.wipe(wrapKey);
        }
    }

    public byte[] unwrapWithRecovery(short formatVersion, byte[] containerId, byte[] recoverySecret) throws VaultAuthenticationException {
        if (slotType != TYPE_RECOVERY || slotState != STATE_ACTIVE) throw new VaultAuthenticationException("Kurtarma slotu etkin değil");

        byte[] wrapKey = VaultKeys.deriveRecoveryWrapKey(recoverySecret, salt, containerId);
        try {
            byte[] aad = AadBuilder.forRecoverySlot(formatVersion, containerId, slotSide, slotGeneration, salt);
            return AesGcmCipher.decrypt(wrapKey, wrapNonce, wrappedDekAndTag, aad);
        } catch (AEADBadTagException e) {
            throw new VaultAuthenticationException("Kurtarma anahtarı bu kasa için geçersiz", e);
        } finally {
            SensitiveBytes.wipe(wrapKey);
        }
    }

    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(VaultConstants.KEY_SLOT_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.put(VaultConstants.SLOT_MAGIC); // 0..3
        buf.put(slotType);                  // 4
        buf.put(slotSide);                  // 5
        buf.put(slotState);                 // 6
        buf.put(kdfType);                   // 7
        buf.putLong(slotGeneration);        // 8..15
        if (argon2Params != null) {
            buf.putInt(argon2Params.version());     // 16..19
            buf.putInt(argon2Params.memoryKiB());   // 20..23
            buf.putInt(argon2Params.iterations());  // 24..27
            buf.putInt(argon2Params.parallelism()); // 28..31
        } else {
            buf.putInt(0);
            buf.putInt(0);
            buf.putInt(0);
            buf.putInt(0);
        }
        buf.put(salt);                      // 32..47
        buf.put(wrapNonce);                 // 48..59
        buf.put(wrappedDekAndTag);          // 60..107
        byte[] body = new byte[108];
        buf.position(0);
        buf.get(body);
        byte[] digest = SensitiveBytes.sha256(body);
        buf.position(108);
        buf.put(digest, 0, 16);             // 108..123
        buf.putInt(0);                      // 124..127
        return buf.array();
    }

    public static byte[] emptyEncodedSlot() {
        return new byte[VaultConstants.KEY_SLOT_SIZE];
    }

    public static KeySlot decodeOrNull(byte[] bytes, byte expectedType, byte expectedSide) {
        if (bytes == null || bytes.length != VaultConstants.KEY_SLOT_SIZE) return null;

        byte[] magic = Arrays.copyOfRange(bytes, 0, 4);
        if (!Arrays.equals(magic, VaultConstants.SLOT_MAGIC)) return null;

        byte[] body = Arrays.copyOfRange(bytes, 0, 108);
        byte[] expectedDigest = Arrays.copyOf(SensitiveBytes.sha256(body), 16);
        byte[] actualDigest = Arrays.copyOfRange(bytes, 108, 124);
        if (!SensitiveBytes.constantTimeEquals(expectedDigest, actualDigest)) return null;

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buf.position(4);
        byte type = buf.get();
        byte side = buf.get();
        byte state = buf.get();
        byte kdf = buf.get();
        long generation = buf.getLong();
        if (type != expectedType || side != expectedSide || state != STATE_ACTIVE || generation < 1) return null;

        int aVer = buf.getInt();
        int aMem = buf.getInt();
        int aIter = buf.getInt();
        int aPar = buf.getInt();
        Argon2Params params = type == TYPE_PASSWORD ? new Argon2Params(aVer, aMem, aIter, aPar) : null;
        byte[] salt = new byte[Argon2Kdf.SALT_BYTES];
        buf.get(salt);
        byte[] nonce = new byte[AesGcmCipher.NONCE_BYTES];
        buf.get(nonce);
        byte[] wrapped = new byte[WRAPPED_DEK_BYTES];
        buf.get(wrapped);
        return new KeySlot(type, side, state, kdf, generation, params, salt, nonce, wrapped);
    }

    public static KeySlot selectActiveSlot(KeySlot slotA, KeySlot slotB) {
        if (slotA == null) return slotB;
        if (slotB == null) return slotA;
        return slotB.slotGeneration() > slotA.slotGeneration() ? slotB : slotA;
    }

    public static byte oppositeSide(byte side) {
        return side == SIDE_A ? SIDE_B : SIDE_A;
    }

    public static long slotOffset(byte slotType, byte side) {
        if (slotType == TYPE_PASSWORD) return side == SIDE_A ? VaultConstants.PASSWORD_SLOT_A_OFFSET : VaultConstants.PASSWORD_SLOT_B_OFFSET;
        if (slotType == TYPE_RECOVERY) return side == SIDE_A ? VaultConstants.RECOVERY_SLOT_A_OFFSET : VaultConstants.RECOVERY_SLOT_B_OFFSET;

        throw new IllegalArgumentException("Unknown slot type: " + slotType);
    }

    public byte slotType() {
        return slotType;
    }

    public byte slotSide() {
        return slotSide;
    }

    public byte slotState() {
        return slotState;
    }

    public byte kdfType() {
        return kdfType;
    }

    public long slotGeneration() {
        return slotGeneration;
    }

    public Argon2Params argon2Params() {
        return argon2Params;
    }
}
