package com.sapphircrypt.sapphirypt.storage;

import com.sapphircrypt.sapphirypt.crypto.Argon2Params;
import com.sapphircrypt.sapphirypt.format.VaultMetadata;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.BlockRef;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.EntryType;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.FileVersion;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.StorageStats;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultPhase2FeaturesTest {

    @Test
    void metadataV2PreservesFavoritesTagsVersionsAndReadsV1Snapshots() throws Exception {
        UUID fileId = UUID.randomUUID();
        UUID verId = UUID.randomUUID();
        BlockRef activeRef = new BlockRef(UUID.randomUUID(), 0, 1024L, 128, 64, 1L, 1L, new byte[32]);
        BlockRef oldVerRef = new BlockRef(UUID.randomUUID(), 0, 1200L, 96, 48, 2L, 2L, new byte[32]);

        VaultMetadata meta = VaultMetadata.createEmpty();
        VaultEntry file = new VaultEntry(
                fileId,
                VaultMetadata.ROOT_ID,
                EntryType.FILE,
                "secret.txt",
                1000L,
                2000L,
                64L,
                List.of(activeRef),
                true,
                List.of("  Work ", "work", "Finance", "", "Important"),
                List.of(new FileVersion(verId, 1500L, 48L, List.of(oldVerRef))));
        meta.putEntry(file);

        byte[] serialized = meta.serialize();
        VaultMetadata restored = VaultMetadata.deserialize(serialized);

        VaultEntry restoredFile = restored.getEntry(fileId).orElseThrow();
        assertTrue(restoredFile.favorite());
        assertEquals(List.of("Work", "Finance", "Important"), restoredFile.tags());
        assertEquals(1, restoredFile.versions().size());
        assertEquals(verId, restoredFile.versions().get(0).versionId());
        assertEquals(48L, restoredFile.versions().get(0).logicalSize());
        assertEquals(2, restored.collectUniqueActiveBlocks().size());

        // Construct a minimal schema v1 payload manually to verify backward compatibility
        byte[] v1Name = "Kasa".getBytes(StandardCharsets.UTF_8);
        ByteBuffer v1 = ByteBuffer.allocate(26 + 65 + v1Name.length).order(ByteOrder.BIG_ENDIAN);
        v1.put("SMET".getBytes(StandardCharsets.US_ASCII));
        v1.putShort((short) 1);
        v1.putLong(1L);
        v1.putLong(1L);
        v1.putInt(1);
        v1.putLong(VaultMetadata.ROOT_ID.getMostSignificantBits());
        v1.putLong(VaultMetadata.ROOT_ID.getLeastSignificantBits());
        v1.putLong(-1L);
        v1.putLong(-1L);
        v1.put(EntryType.FOLDER.code());
        v1.putInt(v1Name.length);
        v1.put(v1Name);
        v1.putLong(100L);
        v1.putLong(100L);
        v1.putLong(0L);
        v1.putInt(0);

        VaultMetadata decodedV1 = VaultMetadata.deserialize(v1.array());
        VaultEntry rootV1 = decodedV1.getEntry(VaultMetadata.ROOT_ID).orElseThrow();
        assertFalse(rootV1.favorite());
        assertTrue(rootV1.tags().isEmpty());
        assertTrue(rootV1.versions().isEmpty());
    }

    @Test
    void sessionFavoritesTagsSearchVersionHistoryAndCompactionLifecycle(@TempDir Path tempDir) throws Exception {
        Path vaultPath = tempDir.resolve("phase2.sapphirypt");
        Path keyPath = tempDir.resolve("phase2.key");
        char[] password = "StrongPhase2Password!".toCharArray();

        Path v1Src = tempDir.resolve("note-v1.txt");
        Path v2Src = tempDir.resolve("note-v2.txt");
        Path v3Src = tempDir.resolve("note-v3.txt");
        byte[] v1Bytes = "version one payload".getBytes(StandardCharsets.UTF_8);
        byte[] v2Bytes = "version two updated payload!!".getBytes(StandardCharsets.UTF_8);
        byte[] v3Bytes = "version three latest payload!!!!!!".getBytes(StandardCharsets.UTF_8);
        Files.write(v1Src, v1Bytes);
        Files.write(v2Src, v2Bytes);
        Files.write(v3Src, v3Bytes);

        try (VaultSession session = VaultSession.createVault(
                vaultPath, password, Argon2Params.forTesting(), keyPath, CpuProfile.BALANCED, 0).session()) {
            VaultEntry e1 = session.importFile(v1Src, VaultMetadata.ROOT_ID, "note.txt", false, CancellationToken.NONE, ProgressListener.NO_OP);
            session.setFavorite(e1.id(), true);
            VaultEntry tagged = session.setTags(e1.id(), List.of("Personal", "Notes", "personal"));
            assertTrue(tagged.favorite());
            assertEquals(List.of("Personal", "Notes"), tagged.tags());

            // Search with favorite & tag filters
            VaultMetadata snap1 = session.getMetadataSnapshot();
            assertEquals(Set.of("Personal", "Notes"), snap1.collectDistinctTags());
            assertEquals(1, snap1.search(VaultMetadata.ROOT_ID, true, "", "", true, "notes").size());
            assertEquals(0, snap1.search(VaultMetadata.ROOT_ID, true, "", "", true, "missing").size());

            // Overwrite twice -> preserves favorite/tags and records 2 archived versions (v2, v1)
            session.importFile(v2Src, VaultMetadata.ROOT_ID, "note.txt", true, CancellationToken.NONE, ProgressListener.NO_OP);
            VaultEntry afterV3 = session.importFile(v3Src, VaultMetadata.ROOT_ID, "note.txt", true, CancellationToken.NONE, ProgressListener.NO_OP);

            assertTrue(afterV3.favorite());
            assertEquals(List.of("Personal", "Notes"), afterV3.tags());
            assertEquals(2, afterV3.versions().size());
            UUID v2VerId = afterV3.versions().get(0).versionId();
            UUID v1VerId = afterV3.versions().get(1).versionId();

            // Export archived version v1 directly
            Path exportedV1 = tempDir.resolve("exported-v1.txt");
            session.exportFileVersion(afterV3.id(), v1VerId, exportedV1, CancellationToken.NONE, ProgressListener.NO_OP);
            assertArrayEquals(v1Bytes, Files.readAllBytes(exportedV1));

            // Compact must rewrite and preserve all active + historical version blocks
            StorageStats beforeCompact = session.getStorageStats();
            assertTrue(beforeCompact.uniqueBlockCount() >= 3);
            session.compact(CancellationToken.NONE, ProgressListener.NO_OP);

            // Re-verify exported version after compaction and restore v1
            Path exportedV1AfterCompact = tempDir.resolve("exported-v1-after-compact.txt");
            session.exportFileVersion(afterV3.id(), v1VerId, exportedV1AfterCompact, CancellationToken.NONE, ProgressListener.NO_OP);
            assertArrayEquals(v1Bytes, Files.readAllBytes(exportedV1AfterCompact));

            VaultEntry restored = session.restoreFileVersion(afterV3.id(), v1VerId);
            assertEquals(v1Bytes.length, restored.logicalSize());
            assertEquals(2, restored.versions().size());
            byte[] activeAfterRestore = session.readFileBytes(afterV3.id(), 1024 * 1024, CancellationToken.NONE);
            assertArrayEquals(v1Bytes, activeAfterRestore);

            // Delete a specific version, then clear remaining history -> frees orphan blocks for compaction
            VaultEntry afterDeleteOne = session.deleteFileVersion(afterV3.id(), v2VerId);
            assertEquals(1, afterDeleteOne.versions().size());

            VaultEntry afterClear = session.clearFileVersions(afterV3.id());
            assertTrue(afterClear.versions().isEmpty());

            session.compact(CancellationToken.NONE, ProgressListener.NO_OP);
            assertArrayEquals(v1Bytes, session.readFileBytes(afterV3.id(), 1024 * 1024, CancellationToken.NONE));
        }

        // Verify persistence across reopen
        try (VaultSession reopened = VaultSession.openWithPassword(vaultPath, password, CpuProfile.BALANCED, 0)) {
            VaultEntry entry = reopened.getMetadataSnapshot()
                    .findChildByName(VaultMetadata.ROOT_ID, "note.txt")
                    .orElseThrow();
            assertTrue(entry.favorite());
            assertEquals(List.of("Personal", "Notes"), entry.tags());
            assertArrayEquals(v1Bytes, reopened.readFileBytes(entry.id(), 1024 * 1024, CancellationToken.NONE));
        }
    }

    @Test
    void secureFileShredderDeletesFilesAndDirectoriesAndHandlesMissing(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("secret-source.bin");
        Files.write(file, "sensitive plaintext bytes".getBytes(StandardCharsets.UTF_8));
        assertTrue(SecureFileShredder.shredFile(file));
        assertFalse(Files.exists(file));
        assertFalse(SecureFileShredder.shredFile(file));
        assertFalse(SecureFileShredder.shredFile(null));

        Path folder = tempDir.resolve("folder-to-shred");
        Path sub = folder.resolve("nested");
        Files.createDirectories(sub);
        Files.writeString(folder.resolve("a.txt"), "alpha");
        Files.writeString(sub.resolve("b.txt"), "beta");
        assertNotNull(folder);

        int shredded = SecureFileShredder.shredDirectory(folder);
        assertEquals(2, shredded);
        assertFalse(Files.exists(folder));
    }
}
