package com.sapphircrypt.sapphirypt.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * RFC 5869 HKDF using HMAC-SHA-256.
 */
public final class HkdfSha256 {
    public static final int HASH_LEN = 32;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private HkdfSha256() {
    }

    public static byte[] extract(byte[] salt, byte[] ikm) {
        if (ikm == null) throw new IllegalArgumentException("IKM must not be null");

        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(effectiveSalt, HMAC_ALGORITHM));
            return mac.doFinal(ikm);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 failure", e);
        }
    }

    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (prk == null || prk.length < HASH_LEN) {
            throw new IllegalArgumentException("PRK length must be at least " + HASH_LEN);
        }
        if (length <= 0 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("Invalid HKDF output length: " + length);
        }
        byte[] effectiveInfo = info == null ? new byte[0] : info;
        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(prk, HMAC_ALGORITHM));
            int blocks = (length + HASH_LEN - 1) / HASH_LEN;
            int offset = 0;
            for (int i = 1; i <= blocks; i++) {
                mac.reset();
                if (t.length > 0) {
                    mac.update(t);
                }
                if (effectiveInfo.length > 0) {
                    mac.update(effectiveInfo);
                }
                mac.update((byte) i);
                byte[] nextT = mac.doFinal();
                SensitiveBytes.wipe(t);
                t = nextT;
                int copyLen = Math.min(HASH_LEN, length - offset);
                System.arraycopy(t, 0, okm, offset, copyLen);
                offset += copyLen;
            }
            return okm;
        } catch (GeneralSecurityException e) {
            SensitiveBytes.wipe(okm);
            throw new IllegalStateException("HMAC-SHA256 expand failure", e);
        } finally {
            SensitiveBytes.wipe(t);
        }
    }

    public static byte[] deriveKey(byte[] ikm, byte[] salt, String infoUtf8, int length) {
        byte[] info = infoUtf8 == null ? new byte[0] : infoUtf8.getBytes(StandardCharsets.UTF_8);
        return deriveKey(ikm, salt, info, length);
    }

    public static byte[] deriveKey(byte[] ikm, byte[] salt, byte[] info, int length) {
        byte[] prk = extract(salt, ikm);
        try {
            return expand(prk, info, length);
        } finally {
            SensitiveBytes.wipe(prk);
        }
    }
}
