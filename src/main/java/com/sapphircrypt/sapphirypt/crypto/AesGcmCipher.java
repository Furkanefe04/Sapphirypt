package com.sapphircrypt.sapphirypt.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

/**
 * AES-256-GCM authenticated encryption primitives with 96-bit nonces and 128-bit tags.
 */
public final class AesGcmCipher {
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BITS = 128;
    public static final int TAG_BYTES = 16;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private AesGcmCipher() {
    }

    public static byte[] buildCounterNonce(int prefix, long counter) {
        ByteBuffer buf = ByteBuffer.allocate(NONCE_BYTES);
        buf.putInt(prefix);
        buf.putLong(counter);
        return buf.array();
    }

    public static byte[] encrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) {
        return encrypt(key, nonce, plaintext, 0, plaintext.length, aad);
    }

    public static byte[] encrypt(byte[] key, byte[] nonce, byte[] plaintext, int offset, int length, byte[] aad) {
        byte[] out = new byte[length + TAG_BYTES];
        int written = encryptInto(key, nonce, plaintext, offset, length, aad, out, 0);
        if (written == out.length) return out;

        byte[] trimmed = new byte[written];
        System.arraycopy(out, 0, trimmed, 0, written);
        return trimmed;
    }

    public static int encryptInto(byte[] key, byte[] nonce, byte[] plaintext, int ptOffset, int ptLength, byte[] aad, byte[] outBuf, int outOffset) {
        validateKeyAndNonce(key, nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null && aad.length > 0) cipher.updateAAD(aad);

            return cipher.doFinal(plaintext, ptOffset, ptLength, outBuf, outOffset);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256-GCM encryption failed", e);
        }
    }

    public static byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertextWithTag, byte[] aad) throws AEADBadTagException {
        return decrypt(key, nonce, ciphertextWithTag, 0, ciphertextWithTag.length, aad);
    }

    public static byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertextWithTag, int offset, int length, byte[] aad) throws AEADBadTagException {
        if (length < TAG_BYTES) throw new AEADBadTagException("Ciphertext shorter than GCM authentication tag");

        byte[] out = new byte[length - TAG_BYTES];
        int written = decryptInto(key, nonce, ciphertextWithTag, offset, length, aad, out, 0);
        if (written == out.length) return out;

        byte[] trimmed = new byte[written];
        System.arraycopy(out, 0, trimmed, 0, written);
        SensitiveBytes.wipe(out);
        return trimmed;
    }

    public static int decryptInto(byte[] key, byte[] nonce, byte[] ciphertextWithTag, int ctOffset, int ctLength, byte[] aad, byte[] outBuf, int outOffset) throws AEADBadTagException {
        validateKeyAndNonce(key, nonce);
        if (ctLength < TAG_BYTES) throw new AEADBadTagException("Ciphertext shorter than GCM authentication tag");

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertextWithTag, ctOffset, ctLength, outBuf, outOffset);
        } catch (AEADBadTagException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256-GCM decryption failed", e);
        }
    }

    private static void validateKeyAndNonce(byte[] key, byte[] nonce) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 key must be 32 bytes");
        }
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("AES-GCM nonce must be 12 bytes");
        }
    }
}
