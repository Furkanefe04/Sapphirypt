package com.sapphircrypt.sapphirypt.storage;

import com.sapphircrypt.sapphirypt.crypto.Argon2Params;
import com.sapphircrypt.sapphirypt.format.VaultMetadata;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultAutoCompactPolicyTest {

    @Test
    void autoCompactPolicyDetectsReclaimableSpaceAfterDeleteAndClearsOnCompact(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("autocompact.sapphirypt");
        Path keyFile = tempDir.resolve("autocompact.key");
        Path sourceFile = tempDir.resolve("payload-128k.bin");
        byte[] payload = new byte[128 * 1024];
        new Random(9001).nextBytes(payload);
        Files.write(sourceFile, payload);

        try (VaultSession session = VaultSession.createVault(
                vault, "CompactPass123!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            assertFalse(session.hasReclaimableSpaceForAutoCompact(),
                    "Freshly created vault should not trigger auto-compact");

            VaultEntry entry = session.importFile(
                    sourceFile, VaultMetadata.ROOT_ID, "payload-128k.bin", false, CancellationToken.NONE, ProgressListener.NO_OP);
            session.deleteEntries(List.of(entry.id()));

            assertTrue(session.hasReclaimableSpaceForAutoCompact(),
                    "Deleting a 128 KiB file should leave enough reclaimable space to trigger auto-compact");

            long sizeBeforeCompact = session.getStorageStats().physicalFileSize();
            var statsAfter = session.compact(CancellationToken.NONE, ProgressListener.NO_OP);

            assertFalse(session.hasReclaimableSpaceForAutoCompact(),
                    "Compacted vault should no longer trigger auto-compact");
            assertTrue(statsAfter.physicalFileSize() < sizeBeforeCompact,
                    "Physical vault file size should shrink after compaction");
        }
    }
}
