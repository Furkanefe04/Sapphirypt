package com.sapphircrypt.sapphirypt.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Utility methods for sensitive byte/char arrays, random generation, and constant-time hashing/comparison.
 * Note: Per Section 3.8 of the design specification, {@code System.gc()} is never called for memory erasure.
 */
public final class SensitiveBytes {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private SensitiveBytes() {
    }

    public static void wipe(byte[] data) {
        if (data != null) Arrays.fill(data, (byte) 0);
    }

    public static void wipe(char[] data) {
        if (data != null) Arrays.fill(data, '\0');
    }

    public static byte[] randomBytes(int length) {
        if (length < 0) throw new IllegalArgumentException("Length must be non-negative: " + length);

        byte[] bytes = new byte[length];
        if (length > 0) SECURE_RANDOM.nextBytes(bytes);

        return bytes;
    }

    public static int randomInt() {
        return SECURE_RANDOM.nextInt();
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                if (part != null && part.length > 0) md.update(part);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
