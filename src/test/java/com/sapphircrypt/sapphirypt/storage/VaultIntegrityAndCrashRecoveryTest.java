package com.sapphircrypt.sapphirypt.storage;

import com.sapphircrypt.sapphirypt.crypto.Argon2Params;
import com.sapphircrypt.sapphirypt.format.RecordFrame;
import com.sapphircrypt.sapphirypt.format.VaultMetadata;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.BlockRef;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.StorageStats;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class VaultIntegrityAndCrashRecoveryTest {

    @Test
    void tamperedDataBlockFailsGcmAndDeletesTempExportFile(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("tamper.sapphirypt");
        Path keyFile = tempDir.resolve("tamper.key");
        Path src = tempDir.resolve("secret.bin");
        byte[] data = new byte[64 * 1024];
        new Random(42).nextBytes(data);
        Files.write(src, data);

        BlockRef blockRef;
        VaultEntry entry;
        try (VaultSession session = VaultSession.createVault(
                vault, "TamperPass!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            entry = session.importFile(
                    src, VaultMetadata.ROOT_ID, "secret.bin", false, CancellationToken.NONE, ProgressListener.NO_OP);
            blockRef = entry.blocks().get(0);
        }

        // Flip 1 byte inside encrypted ciphertext payload of the data block
        try (FileChannel ch = FileChannel.open(vault, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long flipPos = blockRef.recordOffset() + 32 + RecordFrame.DATA_PAYLOAD_HEADER_BYTES + 10;
            ByteBuffer one = ByteBuffer.allocate(1);
            ch.read(one, flipPos);
            one.flip();
            byte flipped = (byte) (one.get(0) ^ 0xFF);
            ch.write(ByteBuffer.wrap(new byte[]{flipped}), flipPos);
            ch.force(true);
        }

        Path exportDest = tempDir.resolve("exported.bin");
        Path exportPart = tempDir.resolve("exported.bin.sapphirypt.part");
        try (VaultSession reopened = VaultSession.openWithPassword(
                vault, "TamperPass!".toCharArray(), CpuProfile.BALANCED, 2)) {
            assertThrows(VaultIntegrityException.class,
                    () -> reopened.exportFile(entry.id(), exportDest, CancellationToken.NONE, ProgressListener.NO_OP));
            assertFalse(Files.exists(exportDest));
            assertFalse(Files.exists(exportPart));
        }
    }

    @Test
    void tornUnframedTailAfterCommitRecoversCleanlyAndAllowsNewCommits(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("torn.sapphirypt");
        Path keyFile = tempDir.resolve("torn.key");
        Path fileA = tempDir.resolve("a.txt");
        Path fileB = tempDir.resolve("b.txt");
        Files.writeString(fileA, "Dosya A içeriği");
        Files.writeString(fileB, "Dosya B içeriği");

        try (VaultSession session = VaultSession.createVault(
                vault, "TornPass!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            session.importFile(fileA, VaultMetadata.ROOT_ID, "a.txt", false, CancellationToken.NONE, ProgressListener.NO_OP);
        }

        // Append torn/partial garbage bytes simulating crash mid-write before commit
        try (FileChannel ch = FileChannel.open(vault, StandardOpenOption.WRITE)) {
            byte[] garbage = new byte[512];
            new Random(55).nextBytes(garbage);
            ch.write(ByteBuffer.wrap(garbage), ch.size());
            ch.force(true);
        }

        try (VaultSession reopened = VaultSession.openWithPassword(
                vault, "TornPass!".toCharArray(), CpuProfile.BALANCED, 2)) {
            VaultMetadata meta = reopened.getMetadataSnapshot();
            assertTrue(meta.findChildByName(VaultMetadata.ROOT_ID, "a.txt").isPresent());
            reopened.importFile(fileB, VaultMetadata.ROOT_ID, "b.txt", false, CancellationToken.NONE, ProgressListener.NO_OP);
        }

        try (VaultSession afterNewCommit = VaultSession.openWithPassword(
                vault, "TornPass!".toCharArray(), CpuProfile.BALANCED, 2)) {
            VaultMetadata meta = afterNewCommit.getMetadataSnapshot();
            assertTrue(meta.findChildByName(VaultMetadata.ROOT_ID, "a.txt").isPresent());
            assertTrue(meta.findChildByName(VaultMetadata.ROOT_ID, "b.txt").isPresent());
        }
    }

    @Test
    void corruptLatestCommitFallsBackToPreviousValidCommit(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("rollback.sapphirypt");
        Path keyFile = tempDir.resolve("rollback.key");
        Path fileA = tempDir.resolve("a.txt");
        Path fileB = tempDir.resolve("b.txt");
        Files.writeString(fileA, "Birinci commit verisi");
        Files.writeString(fileB, "İkinci commit verisi");

        try (VaultSession session = VaultSession.createVault(
                vault, "Rollback1!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            session.importFile(fileA, VaultMetadata.ROOT_ID, "a.txt", false, CancellationToken.NONE, ProgressListener.NO_OP);
            session.importFile(fileB, VaultMetadata.ROOT_ID, "b.txt", false, CancellationToken.NONE, ProgressListener.NO_OP);
            assertEquals(3L, session.currentCommitSequence());
        }

        // Corrupt the GCM-encrypted commit body inside the latest COMMIT_RECORD (leaving SREC/SREF framing valid)
        try (FileChannel ch = FileChannel.open(vault, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long lastCommitBodyPos = ch.size() - 16 - 10; // inside 24B encryptedCommitBodyAndTag before 16B footer
            ByteBuffer one = ByteBuffer.allocate(1);
            ch.read(one, lastCommitBodyPos);
            ch.write(ByteBuffer.wrap(new byte[]{(byte) (one.get(0) ^ 0x55)}), lastCommitBodyPos);
            ch.force(true);
        }

        try (VaultSession reopened = VaultSession.openWithPassword(
                vault, "Rollback1!".toCharArray(), CpuProfile.BALANCED, 2)) {
            assertEquals(2L, reopened.currentCommitSequence());
            VaultMetadata meta = reopened.getMetadataSnapshot();
            assertTrue(meta.findChildByName(VaultMetadata.ROOT_ID, "a.txt").isPresent());
            assertTrue(meta.findChildByName(VaultMetadata.ROOT_ID, "b.txt").isEmpty());
        }
    }

    @Test
    void nonceCountersNeverReuseAcrossReopen(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("nonce.sapphirypt");
        Path keyFile = tempDir.resolve("nonce.key");
        Path f1 = tempDir.resolve("f1.bin");
        Path f2 = tempDir.resolve("f2.bin");
        Files.write(f1, new byte[]{1, 2, 3, 4});
        Files.write(f2, new byte[]{5, 6, 7, 8});

        long firstCounter;
        try (VaultSession s1 = VaultSession.createVault(
                vault, "NoncePass!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            VaultEntry e1 = s1.importFile(
                    f1, VaultMetadata.ROOT_ID, "f1.bin", false, CancellationToken.NONE, ProgressListener.NO_OP);
            firstCounter = e1.blocks().get(0).nonceCounter();
        }

        try (VaultSession s2 = VaultSession.openWithPassword(
                vault, "NoncePass!".toCharArray(), CpuProfile.BALANCED, 2)) {
            VaultEntry e2 = s2.importFile(
                    f2, VaultMetadata.ROOT_ID, "f2.bin", false, CancellationToken.NONE, ProgressListener.NO_OP);
            long secondCounter = e2.blocks().get(0).nonceCounter();
            assertTrue(secondCounter > firstCounter + 100L,
                    "Reopened vault must skip unused counters in persisted lease range");
        }
    }

    @Test
    void compactionReclaimsOrphanBytesAndPreservesActiveFiles(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("compact.sapphirypt");
        Path keyFile = tempDir.resolve("compact.key");
        Path f1 = tempDir.resolve("keep.bin");
        Path f2 = tempDir.resolve("drop.bin");

        byte[] keepData = new byte[150_000];
        byte[] dropData = new byte[400_000];
        new Random(11).nextBytes(keepData);
        new Random(22).nextBytes(dropData);
        Files.write(f1, keepData);
        Files.write(f2, dropData);

        VaultEntry keepEntry;
        try (VaultSession session = VaultSession.createVault(
                vault, "Compact1!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            keepEntry = session.importFile(
                    f1, VaultMetadata.ROOT_ID, "keep.bin", false, CancellationToken.NONE, ProgressListener.NO_OP);
            VaultEntry dropEntry = session.importFile(
                    f2, VaultMetadata.ROOT_ID, "drop.bin", false, CancellationToken.NONE, ProgressListener.NO_OP);
            session.deleteEntries(List.of(dropEntry.id()));

            StorageStats before = session.getStorageStats();
            assertTrue(before.orphanBytes() > 350_000);

            StorageStats after = session.compact(CancellationToken.NONE, ProgressListener.NO_OP);
            assertTrue(after.physicalFileSize() < before.physicalFileSize());
            assertEquals(0L, after.orphanBytes());
        }

        try (VaultSession reopened = VaultSession.openWithPassword(
                vault, "Compact1!".toCharArray(), CpuProfile.BALANCED, 2)) {
            assertEquals(0L, reopened.getStorageStats().orphanBytes());
            Path out = tempDir.resolve("keep_out.bin");
            reopened.exportFile(keepEntry.id(), out, CancellationToken.NONE, ProgressListener.NO_OP);
            assertArrayEquals(keepData, Files.readAllBytes(out));
        }
    }

    @Test
    void cooperativeCancellationAbortsImportWithoutCorruptingCommit(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("cancel.sapphirypt");
        Path keyFile = tempDir.resolve("cancel.key");
        Path f1 = tempDir.resolve("ok.txt");
        Path fBig = tempDir.resolve("big.bin");
        Files.writeString(f1, "Güvenli veri");
        byte[] big = new byte[4 * 1024 * 1024];
        new Random(33).nextBytes(big);
        Files.write(fBig, big);

        try (VaultSession session = VaultSession.createVault(
                vault, "Cancel1!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            session.importFile(f1, VaultMetadata.ROOT_ID, "ok.txt", false, CancellationToken.NONE, ProgressListener.NO_OP);
            long commitBefore = session.currentCommitSequence();

            CancellationToken token = new CancellationToken();
            assertThrows(VaultCancelledException.class, () -> session.importFile(
                    fBig,
                    VaultMetadata.ROOT_ID,
                    "big.bin",
                    false,
                    token,
                    (done, total, msg) -> {
                        if (done > 0) {
                            token.cancel();
                        }
                    }));

            assertEquals(commitBefore, session.currentCommitSequence());
            VaultMetadata meta = session.getMetadataSnapshot();
            assertTrue(meta.findChildByName(VaultMetadata.ROOT_ID, "ok.txt").isPresent());
            assertTrue(meta.findChildByName(VaultMetadata.ROOT_ID, "big.bin").isEmpty());
        }

        try (VaultSession reopened = VaultSession.openWithPassword(
                vault, "Cancel1!".toCharArray(), CpuProfile.BALANCED, 2)) {
            assertTrue(reopened.getMetadataSnapshot().findChildByName(VaultMetadata.ROOT_ID, "ok.txt").isPresent());
            assertTrue(reopened.getMetadataSnapshot().findChildByName(VaultMetadata.ROOT_ID, "big.bin").isEmpty());
        }
    }
}
