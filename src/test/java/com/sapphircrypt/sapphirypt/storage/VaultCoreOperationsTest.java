package com.sapphircrypt.sapphirypt.storage;

import com.sapphircrypt.sapphirypt.crypto.Argon2Params;
import com.sapphircrypt.sapphirypt.crypto.SensitiveBytes;
import com.sapphircrypt.sapphirypt.format.VaultMetadata;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.StorageStats;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VaultCoreOperationsTest {

    @Test
    void createAndAuthenticatePasswordAndRecoveryKey(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("main.sapphirypt");
        Path keyFile = tempDir.resolve("main.key");
        Path otherKeyFile = tempDir.resolve("other.key");
        Path otherVault = tempDir.resolve("other.sapphirypt");

        byte[] expectedCid;
        try (var created = VaultSession.createVault(
                vault, "Pass123!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            expectedCid = created.header().containerId();
        }
        assertArrayEquals(expectedCid, VaultSession.inspectVault(vault).containerId());

        try (var other = VaultSession.createVault(
                otherVault, "Other123!".toCharArray(), Argon2Params.forTesting(), otherKeyFile, CpuProfile.BALANCED, 2).session()) {
            assertNotNull(other);
        }

        assertThrows(VaultAuthenticationException.class,
                () -> VaultSession.openWithPassword(vault, "WrongPass!".toCharArray(), CpuProfile.BALANCED, 2));
        assertThrows(VaultAuthenticationException.class,
                () -> VaultSession.openWithRecoveryKey(vault, otherKeyFile, CpuProfile.BALANCED, 2));

        try (VaultSession s1 = VaultSession.openWithPassword(vault, "Pass123!".toCharArray(), CpuProfile.BALANCED, 2)) {
            assertEquals(1L, s1.currentCommitSequence());
        }
        try (VaultSession s2 = VaultSession.openWithRecoveryKey(vault, keyFile, CpuProfile.BALANCED, 2)) {
            assertEquals(1L, s2.currentCommitSequence());
        }
    }

    @Test
    void smallFileUsesVariableBlockSizeWithoutOneMiBPadding(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("small.sapphirypt");
        Path keyFile = tempDir.resolve("small.key");
        Path smallSrc = tempDir.resolve("note.bin");
        byte[] payload = new byte[10 * 1024];
        new Random(1001).nextBytes(payload);
        Files.write(smallSrc, payload);

        try (VaultSession session = VaultSession.createVault(
                vault, "SmallPass!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            VaultEntry entry = session.importFile(
                    smallSrc, VaultMetadata.ROOT_ID, "note.bin", false, CancellationToken.NONE, ProgressListener.NO_OP);
            assertEquals(1, entry.blocks().size());
            assertEquals(10 * 1024, entry.blocks().get(0).plaintextLength());

            Path out = tempDir.resolve("note_out.bin");
            session.exportFile(entry.id(), out, CancellationToken.NONE, ProgressListener.NO_OP);
            assertArrayEquals(payload, Files.readAllBytes(out));
        }

        assertTrue(Files.size(vault) < 32 * 1024, "Small 10 KiB vault must not pad blocks to 1 MiB");
    }

    @Test
    void multiBlockStreamingAndEmptyFileRoundTrip(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("multi.sapphirypt");
        Path keyFile = tempDir.resolve("multi.key");
        Path bigSrc = tempDir.resolve("big.bin");
        Path emptySrc = tempDir.resolve("empty.txt");

        byte[] bigData = new byte[(2 * 1024 * 1024) + 600_000];
        new Random(2026).nextBytes(bigData);
        Files.write(bigSrc, bigData);
        Files.write(emptySrc, new byte[0]);

        UUID bigId;
        UUID emptyId;
        try (VaultSession session = VaultSession.createVault(
                vault, "StreamPass!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.PERFORMANCE, 4).session()) {
            VaultEntry bigEntry = session.importFile(
                    bigSrc, VaultMetadata.ROOT_ID, "big.bin", false, CancellationToken.NONE, ProgressListener.NO_OP);
            assertEquals(3, bigEntry.blocks().size());
            bigId = bigEntry.id();

            VaultEntry emptyEntry = session.importFile(
                    emptySrc, VaultMetadata.ROOT_ID, "empty.txt", false, CancellationToken.NONE, ProgressListener.NO_OP);
            assertEquals(0, emptyEntry.blocks().size());
            assertEquals(0L, emptyEntry.logicalSize());
            emptyId = emptyEntry.id();
        }

        try (VaultSession reopened = VaultSession.openWithPassword(
                vault, "StreamPass!".toCharArray(), CpuProfile.BALANCED, 2)) {
            Path bigOut = tempDir.resolve("big_out.bin");
            Path emptyOut = tempDir.resolve("empty_out.txt");
            reopened.exportFile(bigId, bigOut, CancellationToken.NONE, ProgressListener.NO_OP);
            reopened.exportFile(emptyId, emptyOut, CancellationToken.NONE, ProgressListener.NO_OP);

            assertArrayEquals(SensitiveBytes.sha256(bigData), SensitiveBytes.sha256(Files.readAllBytes(bigOut)));
            assertEquals(0L, Files.size(emptyOut));
        }
    }

    @Test
    void overwritePreservesFileIdAndReusesUnchangedBlocks(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("overwrite.sapphirypt");
        Path keyFile = tempDir.resolve("overwrite.key");
        Path src = tempDir.resolve("data.bin");

        byte[] original = new byte[(2 * 1024 * 1024) + 200_000]; // 3 blocks
        new Random(77).nextBytes(original);
        Files.write(src, original);

        try (VaultSession session = VaultSession.createVault(
                vault, "Overwrite1!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            VaultEntry first = session.importFile(
                    src, VaultMetadata.ROOT_ID, "data.bin", false, CancellationToken.NONE, ProgressListener.NO_OP);
            UUID originalId = first.id();

            byte[] modified = Arrays.copyOf(original, original.length);
            modified[1024 * 1024 + 50] ^= 0x7F; // mutate only block index 1
            Files.write(src, modified);

            VaultEntry updated = session.importFile(
                    src, VaultMetadata.ROOT_ID, "data.bin", true, CancellationToken.NONE, ProgressListener.NO_OP);

            assertEquals(originalId, updated.id());
            assertEquals(first.blocks().get(0).blockId(), updated.blocks().get(0).blockId());
            assertNotEquals(first.blocks().get(1).blockId(), updated.blocks().get(1).blockId());
            assertEquals(first.blocks().get(2).blockId(), updated.blocks().get(2).blockId());

            Path out = tempDir.resolve("data_out.bin");
            session.exportFile(updated.id(), out, CancellationToken.NONE, ProgressListener.NO_OP);
            assertArrayEquals(modified, Files.readAllBytes(out));
        }
    }

    @Test
    void inVaultCopySharesBlocksAndIsolatesOverwriteAndDelete(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("copy.sapphirypt");
        Path keyFile = tempDir.resolve("copy.key");
        Path src = tempDir.resolve("doc.bin");
        byte[] contentA = new byte[120_000];
        new Random(9).nextBytes(contentA);
        Files.write(src, contentA);

        try (VaultSession session = VaultSession.createVault(
                vault, "CopyPass!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            VaultEntry orig = session.importFile(
                    src, VaultMetadata.ROOT_ID, "doc.bin", false, CancellationToken.NONE, ProgressListener.NO_OP);
            VaultEntry copy = session.copyEntry(orig.id(), VaultMetadata.ROOT_ID, "doc-copy.bin");

            assertNotEquals(orig.id(), copy.id());
            assertEquals(orig.blocks().get(0).blockId(), copy.blocks().get(0).blockId());

            StorageStats statsAfterCopy = session.getStorageStats();
            assertEquals(1, statsAfterCopy.uniqueBlockCount());
            assertEquals(2, statsAfterCopy.totalBlockRefCount());
            assertEquals(2L * contentA.length, statsAfterCopy.logicalSize());

            // Overwrite copy -> original must retain contentA
            byte[] contentB = new byte[90_000];
            new Random(10).nextBytes(contentB);
            Files.write(src, contentB);
            session.importFile(src, VaultMetadata.ROOT_ID, "doc-copy.bin", true, CancellationToken.NONE, ProgressListener.NO_OP);

            Path outOrig = tempDir.resolve("orig.bin");
            Path outCopy = tempDir.resolve("copy.bin");
            session.exportFile(orig.id(), outOrig, CancellationToken.NONE, ProgressListener.NO_OP);
            session.exportFile(copy.id(), outCopy, CancellationToken.NONE, ProgressListener.NO_OP);
            assertArrayEquals(contentA, Files.readAllBytes(outOrig));
            assertArrayEquals(contentB, Files.readAllBytes(outCopy));

            // Create second shared copy, delete original -> shared copy still readable
            VaultEntry copy2 = session.copyEntry(orig.id(), VaultMetadata.ROOT_ID, "doc-shared.bin");
            session.deleteEntries(List.of(orig.id()));
            Path outShared = tempDir.resolve("shared.bin");
            session.exportFile(copy2.id(), outShared, CancellationToken.NONE, ProgressListener.NO_OP);
            assertArrayEquals(contentA, Files.readAllBytes(outShared));
        }
    }

    @Test
    void folderOperationsSearchFilterAndLocking(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("org.sapphirypt");
        Path keyFile = tempDir.resolve("org.key");
        Path note = tempDir.resolve("readme.md");
        Files.writeString(note, "# Merhaba Sapphirypt");

        try (VaultSession session = VaultSession.createVault(
                vault, "OrgPass!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            VaultEntry folderA = session.createFolder(VaultMetadata.ROOT_ID, "Belgeler");
            VaultEntry folderB = session.createFolder(folderA.id(), "AltKlasor");
            VaultEntry file = session.importFile(
                    note, folderB.id(), "readme.md", false, CancellationToken.NONE, ProgressListener.NO_OP);

            assertThrows(VaultException.class, () -> session.moveEntry(folderA.id(), folderB.id()));

            VaultEntry moved = session.moveEntry(file.id(), folderA.id());
            assertEquals(folderA.id(), moved.parentId());
            assertEquals(file.id(), moved.id());

            VaultEntry renamed = session.renameEntry(moved.id(), "notlar.md");
            assertEquals("notlar.md", renamed.name());

            VaultMetadata snap = session.getMetadataSnapshot();
            assertEquals(0, snap.search(VaultMetadata.ROOT_ID, false, "notlar", "").size());
            assertEquals(1, snap.search(VaultMetadata.ROOT_ID, true, "notlar", "md").size());
            assertEquals(0, snap.search(VaultMetadata.ROOT_ID, true, "notlar", "png").size());

            // Exclusive OS FileLock rejects second open while session is active
            assertThrows(VaultLockedException.class,
                    () -> VaultSession.openWithPassword(vault, "OrgPass!".toCharArray(), CpuProfile.BALANCED, 2));

            // Live CPU profile reconfiguration
            session.reconfigureWorkers(CpuProfile.POWER_SAVER, 1);
            assertEquals(1, session.workerCount());
            session.reconfigureWorkers(CpuProfile.PERFORMANCE, 3);
            assertTrue(session.workerCount() >= 1 && session.workerCount() <= 3);
            session.clearCache();
        }
    }

    @Test
    void passwordChangeAndRecoveryKeyRotationABSlots(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("keys.sapphirypt");
        Path key1 = tempDir.resolve("k1.key");
        Path key2 = tempDir.resolve("k2.key");

        try (VaultSession session = VaultSession.createVault(
                vault, "OldPass!".toCharArray(), Argon2Params.forTesting(), key1, CpuProfile.BALANCED, 2).session()) {
            session.changePassword("OldPass!".toCharArray(), "NewPass!".toCharArray(), Argon2Params.forTesting());
            session.rotateRecoveryKey(key2);
        }

        assertThrows(VaultAuthenticationException.class,
                () -> VaultSession.openWithPassword(vault, "OldPass!".toCharArray(), CpuProfile.BALANCED, 2));
        assertThrows(VaultAuthenticationException.class,
                () -> VaultSession.openWithRecoveryKey(vault, key1, CpuProfile.BALANCED, 2));

        try (VaultSession sPw = VaultSession.openWithPassword(vault, "NewPass!".toCharArray(), CpuProfile.BALANCED, 2)) {
            assertEquals(1L, sPw.currentCommitSequence());
        }
        try (VaultSession sRec = VaultSession.openWithRecoveryKey(vault, key2, CpuProfile.BALANCED, 2)) {
            assertEquals(1L, sRec.currentCommitSequence());
        }
    }
}
