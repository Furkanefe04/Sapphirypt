package com.sapphircrypt.sapphirypt.storage;

import com.sapphircrypt.sapphirypt.crypto.Argon2Params;
import com.sapphircrypt.sapphirypt.format.VaultMetadata;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempPreviewManagerTest {

    @Test
    void createsThreeLevelRandomHierarchyWithExtensionOnlyFilename(@TempDir Path tempDir) throws Exception {
        Path baseDir = tempDir.resolve("tmp");
        TempPreviewManager manager = new TempPreviewManager(baseDir);

        Path tempFile = manager.createTempFile("gizli-sozlesme-2026.PDF");
        assertTrue(Files.isRegularFile(tempFile));
        assertTrue(tempFile.getFileName().toString().endsWith(".pdf"));
        assertFalse(tempFile.toString().toLowerCase(Locale.ROOT).contains("gizli-sozlesme"),
                "Original file name must not leak into temp hierarchy");

        Path relative = baseDir.relativize(tempFile);
        assertEquals(4, relative.getNameCount(), "Expected <vault>/<session>/<open>/<random>.<ext>");

        Path openDir = tempFile.getParent();
        Path sessionDir = openDir.getParent();
        Path vaultDir = sessionDir.getParent();
        assertEquals(baseDir, vaultDir.getParent());
        assertTrue(Files.exists(sessionDir.resolve(".session.lock")));

        manager.cleanupAll();

        assertFalse(Files.exists(tempFile));
        assertFalse(Files.exists(openDir));
        assertFalse(Files.exists(sessionDir));
        assertFalse(Files.exists(vaultDir));
    }

    @Test
    void sweepSkipsActiveLockedSessionAndDeletesOrphanSession(@TempDir Path tempDir) throws Exception {
        Path baseDir = tempDir.resolve("tmp");
        TempPreviewManager activeManager = new TempPreviewManager(baseDir);
        Path activeFile = activeManager.createTempFile("rapor.docx");
        Files.writeString(activeFile, "active session payload", StandardCharsets.UTF_8);

        Path orphanVault = baseDir.resolve("deadbeef00001111");
        Path orphanSession = orphanVault.resolve("11112222deadbeef");
        Path orphanOpen = orphanSession.resolve("cafebabe00009999");
        Files.createDirectories(orphanOpen);
        Files.createFile(orphanSession.resolve(".session.lock"));
        Path orphanFile = orphanOpen.resolve("stale1234.txt");
        Files.writeString(orphanFile, "leftover secret", StandardCharsets.UTF_8);

        Path legacyFlat = baseDir.resolve("sapphirypt-old-file.txt");
        Files.writeString(legacyFlat, "old", StandardCharsets.UTF_8);

        TempPreviewManager.sweepOrphanTempFiles(baseDir);

        assertFalse(Files.exists(orphanFile));
        assertFalse(Files.exists(orphanSession));
        assertFalse(Files.exists(orphanVault));
        assertFalse(Files.exists(legacyFlat));
        assertTrue(Files.exists(activeFile));
        assertEquals("active session payload", Files.readString(activeFile, StandardCharsets.UTF_8));

        activeManager.cleanupAll();
    }

    @Test
    void externalEditSyncsBackIntoVaultAndPersistsAcrossReopen(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("ext-sync.sapphirypt");
        Path keyFile = tempDir.resolve("ext-sync.key");
        Path initialSource = tempDir.resolve("notes.txt");
        Files.writeString(initialSource, "ilk icerik", StandardCharsets.UTF_8);

        TempPreviewManager tmp = new TempPreviewManager(tempDir.resolve("ext-tmp"));
        Path extFile;
        try (VaultSession session = VaultSession.createVault(
                vault, "SyncPass123!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            VaultEntry imported = session.importFile(
                    initialSource, VaultMetadata.ROOT_ID, "notes.txt", false, CancellationToken.NONE, ProgressListener.NO_OP);

            extFile = tmp.createTempFile(imported.name());
            session.exportFile(imported.id(), extFile, CancellationToken.NONE, ProgressListener.NO_OP);
            tmp.hardenFile(extFile);
            tmp.watchExternalFile(extFile, imported.id(), session::syncExternalFileEdit);

            Files.writeString(extFile, "guncel icerik — duzenlendi", StandardCharsets.UTF_8);
            tmp.syncPendingEditsNow();

            byte[] inVaultBytes = session.readFileBytes(imported.id(), 1024 * 1024, CancellationToken.NONE);
            assertEquals("guncel icerik — duzenlendi", new String(inVaultBytes, StandardCharsets.UTF_8));
        } finally {
            tmp.cleanupAll();
        }
        assertFalse(Files.exists(extFile));

        try (VaultSession reopened = VaultSession.openWithPassword(
                vault, "SyncPass123!".toCharArray(), CpuProfile.BALANCED, 2)) {
            VaultEntry entry = reopened.getMetadataSnapshot()
                    .findChildByName(VaultMetadata.ROOT_ID, "notes.txt")
                    .orElseThrow();
            byte[] persisted = reopened.readFileBytes(entry.id(), 1024 * 1024, CancellationToken.NONE);
            assertEquals("guncel icerik — duzenlendi", new String(persisted, StandardCharsets.UTF_8));
        }
    }
}
