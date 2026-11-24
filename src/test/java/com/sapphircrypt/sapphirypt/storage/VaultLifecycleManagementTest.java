package com.sapphircrypt.sapphirypt.storage;

import com.sapphircrypt.sapphirypt.crypto.Argon2Params;
import com.sapphircrypt.sapphirypt.format.VaultMetadata;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VaultLifecycleManagementTest {

    @Test
    void renameClosedVaultRequiresBothPasswordAndRecoveryKey(@TempDir Path tempDir) throws Exception {
        Path alphaVault = tempDir.resolve("alpha.sapphirypt");
        Path alphaKey = tempDir.resolve("alpha.key");
        Path otherVault = tempDir.resolve("other.sapphirypt");
        Path otherKey = tempDir.resolve("other.key");
        Path tamperedKey = tempDir.resolve("tampered.key");
        Path missingKey = tempDir.resolve("missing.key");

        Path sampleSrc = tempDir.resolve("sample.txt");
        Files.writeString(sampleSrc, "Kasa yeniden adlandırma bütünlük testi");

        try (VaultSession s = VaultSession.createVault(
                alphaVault, "AlphaPass!1".toCharArray(), Argon2Params.forTesting(), alphaKey, CpuProfile.BALANCED, 2).session()) {
            s.importFile(sampleSrc, VaultMetadata.ROOT_ID, "sample.txt", false, CancellationToken.NONE, ProgressListener.NO_OP);
        }
        try (VaultSession s2 = VaultSession.createVault(
                otherVault, "OtherPass!1".toCharArray(), Argon2Params.forTesting(), otherKey, CpuProfile.BALANCED, 2).session()) {
            assertNotNull(s2);
        }

        byte[] tamperedBytes = Files.readAllBytes(alphaKey);
        tamperedBytes[35] ^= 0x3C;
        Files.write(tamperedKey, tamperedBytes);

        assertThrows(VaultAuthenticationException.class,
                () -> VaultSession.renameVault(alphaVault, "beta", "WrongPass!1".toCharArray(), alphaKey));
        assertThrows(VaultAuthenticationException.class,
                () -> VaultSession.renameVault(alphaVault, "beta", "AlphaPass!1".toCharArray(), otherKey));
        assertThrows(VaultAuthenticationException.class,
                () -> VaultSession.renameVault(alphaVault, "beta", "AlphaPass!1".toCharArray(), tamperedKey));
        assertThrows(VaultAuthenticationException.class,
                () -> VaultSession.renameVault(alphaVault, "beta", "AlphaPass!1".toCharArray(), missingKey));

        assertThrows(VaultException.class,
                () -> VaultSession.renameVault(alphaVault, "../bad", "AlphaPass!1".toCharArray(), alphaKey));
        assertThrows(VaultException.class,
                () -> VaultSession.renameVault(alphaVault, "a/b", "AlphaPass!1".toCharArray(), alphaKey));
        assertThrows(VaultException.class,
                () -> VaultSession.renameVault(alphaVault, ".sapphirypt", "AlphaPass!1".toCharArray(), alphaKey));
        assertThrows(VaultException.class,
                () -> VaultSession.renameVault(alphaVault, "other", "AlphaPass!1".toCharArray(), alphaKey));

        assertTrue(Files.exists(alphaVault));

        Path renamed = VaultSession.renameVault(alphaVault, "beta-vault", "AlphaPass!1".toCharArray(), alphaKey);
        assertEquals(tempDir.resolve("beta-vault.sapphirypt").toAbsolutePath().normalize(), renamed);
        assertFalse(Files.exists(alphaVault));
        assertTrue(Files.isRegularFile(renamed));

        try (VaultSession reopenedPw = VaultSession.openWithPassword(
                renamed, "AlphaPass!1".toCharArray(), CpuProfile.BALANCED, 2)) {
            VaultEntry entry = reopenedPw.getMetadataSnapshot()
                    .findChildByName(VaultMetadata.ROOT_ID, "sample.txt")
                    .orElseThrow();
            Path exported = tempDir.resolve("exported_sample.txt");
            reopenedPw.exportFile(entry.id(), exported, CancellationToken.NONE, ProgressListener.NO_OP);
            assertEquals("Kasa yeniden adlandırma bütünlük testi", Files.readString(exported));
        }
        try (VaultSession reopenedKey = VaultSession.openWithRecoveryKey(
                renamed, alphaKey, CpuProfile.BALANCED, 2)) {
            assertTrue(reopenedKey.getMetadataSnapshot().findChildByName(VaultMetadata.ROOT_ID, "sample.txt").isPresent());
        }
    }

    @Test
    void renameOpenVaultUpdatesSessionPathAndContinuesWorking(@TempDir Path tempDir) throws Exception {
        Path origVault = tempDir.resolve("orig.sapphirypt");
        Path origKey = tempDir.resolve("orig.key");
        Path otherVault = tempDir.resolve("other.sapphirypt");
        Path otherKey = tempDir.resolve("other.key");

        try (VaultSession other = VaultSession.createVault(
                otherVault, "Other123!".toCharArray(), Argon2Params.forTesting(), otherKey, CpuProfile.BALANCED, 2).session()) {
            assertNotNull(other);
        }

        Path fileA = tempDir.resolve("a.txt");
        Path fileB = tempDir.resolve("b.txt");
        Files.writeString(fileA, "Önceki veri");
        Files.writeString(fileB, "Yeniden adlandırma sonrası veri");

        Path renamedPath;
        try (VaultSession session = VaultSession.createVault(
                origVault, "OpenPass!1".toCharArray(), Argon2Params.forTesting(), origKey, CpuProfile.BALANCED, 2).session()) {
            session.importFile(fileA, VaultMetadata.ROOT_ID, "a.txt", false, CancellationToken.NONE, ProgressListener.NO_OP);

            assertThrows(VaultAuthenticationException.class,
                    () -> session.renameVault("renamed-open", "WrongPass!".toCharArray(), origKey));
            assertThrows(VaultAuthenticationException.class,
                    () -> session.renameVault("renamed-open", "OpenPass!1".toCharArray(), otherKey));
            assertFalse(session.isClosed());
            assertEquals(origVault.toAbsolutePath().normalize(), session.vaultPath());

            renamedPath = session.renameVault("renamed-open", "OpenPass!1".toCharArray(), origKey);
            assertEquals(tempDir.resolve("renamed-open.sapphirypt").toAbsolutePath().normalize(), renamedPath);
            assertEquals(renamedPath, session.vaultPath());
            assertFalse(Files.exists(origVault));
            assertTrue(Files.isRegularFile(renamedPath));

            VaultEntry addedAfter = session.importFile(
                    fileB, VaultMetadata.ROOT_ID, "b.txt", false, CancellationToken.NONE, ProgressListener.NO_OP);
            Path outB = tempDir.resolve("b_out.txt");
            session.exportFile(addedAfter.id(), outB, CancellationToken.NONE, ProgressListener.NO_OP);
            assertEquals("Yeniden adlandırma sonrası veri", Files.readString(outB));
        }

        try (VaultSession reopened = VaultSession.openWithPassword(
                renamedPath, "OpenPass!1".toCharArray(), CpuProfile.BALANCED, 2)) {
            assertTrue(reopened.getMetadataSnapshot().findChildByName(VaultMetadata.ROOT_ID, "a.txt").isPresent());
            assertTrue(reopened.getMetadataSnapshot().findChildByName(VaultMetadata.ROOT_ID, "b.txt").isPresent());
        }
    }

    @Test
    void deleteClosedVaultRequiresBothPasswordAndRecoveryKey(@TempDir Path tempDir) throws Exception {
        Path del1 = tempDir.resolve("del1.sapphirypt");
        Path del1Key = tempDir.resolve("del1.key");
        Path del2 = tempDir.resolve("del2.sapphirypt");
        Path del2Key = tempDir.resolve("del2.key");
        Path otherVault = tempDir.resolve("other.sapphirypt");
        Path otherKey = tempDir.resolve("other.key");

        try (VaultSession other = VaultSession.createVault(
                otherVault, "Other123!".toCharArray(), Argon2Params.forTesting(), otherKey, CpuProfile.BALANCED, 2).session()) {
            assertNotNull(other);
        }

        try (VaultSession s1 = VaultSession.createVault(
                del1, "DelPass!1".toCharArray(), Argon2Params.forTesting(), del1Key, CpuProfile.BALANCED, 2).session()) {
            // While open in another session, closed delete must fail with VaultLockedException
            assertThrows(VaultLockedException.class,
                    () -> VaultSession.deleteVault(del1, "DelPass!1".toCharArray(), del1Key, false));
            assertTrue(Files.exists(del1));
        }

        assertThrows(VaultAuthenticationException.class,
                () -> VaultSession.deleteVault(del1, "WrongPass!".toCharArray(), del1Key, false));
        assertThrows(VaultAuthenticationException.class,
                () -> VaultSession.deleteVault(del1, "DelPass!1".toCharArray(), otherKey, false));
        assertTrue(Files.exists(del1));

        VaultSession.deleteVault(del1, "DelPass!1".toCharArray(), del1Key, false);
        assertFalse(Files.exists(del1));
        assertTrue(Files.exists(del1Key));

        try (VaultSession s2 = VaultSession.createVault(
                del2, "DelPass!2".toCharArray(), Argon2Params.forTesting(), del2Key, CpuProfile.BALANCED, 2).session()) {
            assertNotNull(s2);
        }
        VaultSession.deleteVault(del2, "DelPass!2".toCharArray(), del2Key, true);
        assertFalse(Files.exists(del2));
        assertFalse(Files.exists(del2Key));
    }

    @Test
    void deleteOpenVaultRequiresBothFactorsClosesSessionAndDeletesFile(@TempDir Path tempDir) throws Exception {
        Path liveVault = tempDir.resolve("live.sapphirypt");
        Path liveKey = tempDir.resolve("live.key");
        Path otherVault = tempDir.resolve("other.sapphirypt");
        Path otherKey = tempDir.resolve("other.key");

        try (VaultSession other = VaultSession.createVault(
                otherVault, "Other123!".toCharArray(), Argon2Params.forTesting(), otherKey, CpuProfile.BALANCED, 2).session()) {
            assertNotNull(other);
        }

        VaultSession session = VaultSession.createVault(
                liveVault, "LivePass!1".toCharArray(), Argon2Params.forTesting(), liveKey, CpuProfile.BALANCED, 2).session();
        try {
            assertThrows(VaultAuthenticationException.class,
                    () -> session.deleteVault("WrongPass!".toCharArray(), liveKey, false));
            assertThrows(VaultAuthenticationException.class,
                    () -> session.deleteVault("LivePass!1".toCharArray(), otherKey, false));
            assertFalse(session.isClosed());
            assertTrue(Files.exists(liveVault));

            session.deleteVault("LivePass!1".toCharArray(), liveKey, false);
            assertFalse(Files.exists(liveVault));
            assertTrue(Files.exists(liveKey));
            assertTrue(session.isClosed());
            assertThrows(VaultLockedException.class, session::getMetadataSnapshot);
        } finally {
            session.close();
        }
    }
}
