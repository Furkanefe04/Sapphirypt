package com.sapphircrypt.sapphirypt.crypto;

import org.bouncycastle.crypto.PasswordConverter;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id password-based key derivation powered by Bouncy Castle.
 */
public final class Argon2Kdf {
    public static final int SALT_BYTES = 16;
    public static final int OUTPUT_BYTES = 32;

    private Argon2Kdf() {
    }

    public static byte[] derive(char[] password, byte[] salt, Argon2Params params) {
        return derive(password, salt, params, OUTPUT_BYTES);
    }

    public static byte[] derive(char[] password, byte[] salt, Argon2Params params, int outLen) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Parola boş olamaz");
        }
        if (salt == null || salt.length != SALT_BYTES) {
            throw new IllegalArgumentException("Salt boyutu " + SALT_BYTES + " byte olmalıdır");
        }
        if (params == null) {
            throw new IllegalArgumentException("Argon2 parametreleri null olamaz");
        }
        if (outLen <= 0) {
            throw new IllegalArgumentException("Geçersiz çıktı uzunluğu: " + outLen);
        }
        params.validateForExecution();

        Argon2Parameters bcParams = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(params.version()).withMemoryAsKB(params.memoryKiB()).withIterations(params.iterations())
                .withParallelism(params.parallelism()).withSalt(salt).withCharToByteConverter(PasswordConverter.UTF8).build();
        byte[] output = new byte[outLen];
        try {
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(bcParams);
            generator.generateBytes(password, output);
            return output;
        } finally {
            bcParams.clear();
        }
    }
}
