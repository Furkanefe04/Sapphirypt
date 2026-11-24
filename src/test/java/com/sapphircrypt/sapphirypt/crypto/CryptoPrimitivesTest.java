package com.sapphircrypt.sapphirypt.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.AEADBadTagException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CryptoPrimitivesTest {

    @Test
    void hkdfSha256MatchesRfc5869VectorCase1() {
        HexFormat hex = HexFormat.of();
        byte[] ikm = hex.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = hex.parseHex("000102030405060708090a0b0c");
        byte[] info = hex.parseHex("f0f1f2f3f4f5f6f7f8f9");

        byte[] prk = HkdfSha256.extract(salt, ikm);
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", hex.formatHex(prk));

        byte[] okm = HkdfSha256.expand(prk, info, 42);
        assertEquals(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                hex.formatHex(okm));
    }

    @Test
    void aesGcmRoundTripAndTamperDetection() throws Exception {
        byte[] key = SensitiveBytes.randomBytes(32);
        byte[] nonce = AesGcmCipher.buildCounterNonce(0x12345678, 42L);
        byte[] containerId = SensitiveBytes.randomBytes(16);
        byte[] aad = AadBuilder.forDataBlock((short) 1, containerId, UUID.randomUUID(), 0, 42L, 17, 7L);
        byte[] plaintext = "Sapphirypt-AES256".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = AesGcmCipher.encrypt(key, nonce, plaintext, aad);
        assertArrayEquals(plaintext, AesGcmCipher.decrypt(key, nonce, ciphertext, aad));

        byte[] tamperedCt = ciphertext.clone();
        tamperedCt[0] ^= 0x01;
        assertThrows(AEADBadTagException.class, () -> AesGcmCipher.decrypt(key, nonce, tamperedCt, aad));

        byte[] wrongAad = aad.clone();
        wrongAad[wrongAad.length - 1] ^= 0x01;
        assertThrows(AEADBadTagException.class, () -> AesGcmCipher.decrypt(key, nonce, ciphertext, wrongAad));

        byte[] wrongNonce = AesGcmCipher.buildCounterNonce(0x12345678, 43L);
        assertThrows(AEADBadTagException.class, () -> AesGcmCipher.decrypt(key, wrongNonce, ciphertext, aad));
    }

    @Test
    void argon2BoundsEnforceOneGiBCapAndDerivationDeterminism() {
        assertThrows(IllegalArgumentException.class,
                () -> new Argon2Params(Argon2Params.VERSION_13, 1024 * 1024 + 1024, 3, 1).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new Argon2Params(Argon2Params.VERSION_13, 8 * 1024, 3, 1).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new Argon2Params(0x10, 16 * 1024, 3, 1).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new Argon2Params(Argon2Params.VERSION_13, 16 * 1024, 0, 1).validate());

        Argon2Params params = Argon2Params.forTesting();
        byte[] salt = SensitiveBytes.randomBytes(16);
        byte[] k1 = Argon2Kdf.derive("SecretPass!99".toCharArray(), salt, params);
        byte[] k2 = Argon2Kdf.derive("SecretPass!99".toCharArray(), salt, params);
        byte[] k3 = Argon2Kdf.derive("OtherPass!99".toCharArray(), salt, params);

        assertEquals(32, k1.length);
        assertArrayEquals(k1, k2);
        assertFalse(SensitiveBytes.constantTimeEquals(k1, k3));
    }

    @Test
    void vaultKeysPurposeSeparationAndWipe() {
        byte[] dek = SensitiveBytes.randomBytes(32);
        byte[] cid = SensitiveBytes.randomBytes(16);
        VaultKeys keys = VaultKeys.fromDek(dek, cid);

        assertFalse(SensitiveBytes.constantTimeEquals(keys.dataKey(), keys.metadataKey()));
        assertFalse(SensitiveBytes.constantTimeEquals(keys.metadataKey(), keys.commitKey()));
        assertFalse(SensitiveBytes.constantTimeEquals(keys.commitKey(), keys.nonceStateKey()));

        keys.close();
        assertTrue(keys.isClosed());
        assertThrows(IllegalStateException.class, keys::dataKey);
    }

    @Test
    void recoveryKeyFileRoundTripAndTamperRejection(@TempDir Path tempDir) throws IOException {
        byte[] cid = SensitiveBytes.randomBytes(16);
        Path keyPath = tempDir.resolve("test.key");
        try (RecoveryKeyFile generated = RecoveryKeyFile.generate(cid)) {
            byte[] expectedSecret = generated.recoverySecretCopy();
            generated.writeTo(keyPath);

            try (RecoveryKeyFile loaded = RecoveryKeyFile.readFrom(keyPath)) {
                assertArrayEquals(cid, loaded.containerId());
                assertArrayEquals(expectedSecret, loaded.recoverySecretCopy());
            }

            byte[] raw = Files.readAllBytes(keyPath);
            raw[35] ^= 0x5A;
            Files.write(keyPath, raw);
            assertThrows(IOException.class, () -> RecoveryKeyFile.readFrom(keyPath));
        }
    }

    @Test
    void sensitiveBytesWipeZeroesBuffers() {
        byte[] b = {1, 2, 3, 4};
        char[] c = {'a', 'b', 'c'};
        SensitiveBytes.wipe(b);
        SensitiveBytes.wipe(c);
        assertArrayEquals(new byte[4], b);
        assertArrayEquals(new char[3], c);
    }
}
