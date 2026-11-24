package com.sapphircrypt.sapphirypt.storage;

import com.sapphircrypt.sapphirypt.crypto.Argon2Params;
import com.sapphircrypt.sapphirypt.crypto.SensitiveBytes;
import com.sapphircrypt.sapphirypt.crypto.VaultKeys;
import com.sapphircrypt.sapphirypt.format.NonceLeaseManager;
import com.sapphircrypt.sapphirypt.format.VaultConstants;
import com.sapphircrypt.sapphirypt.format.VaultMetadata;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.StorageStats;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultLargeImportResponsivenessTest {

    @Test
    void snapshotsDoNotBlockDuringActiveImport(@TempDir Path tempDir) throws Exception {
        Path vault = tempDir.resolve("responsive.sapphirypt");
        Path keyFile = tempDir.resolve("responsive.key");
        Path sourceFile = tempDir.resolve("multi-block.bin");
        byte[] payload = new byte[4 * 1024 * 1024 + 123];
        new Random(4242).nextBytes(payload);
        Files.write(sourceFile, payload);

        try (VaultSession session = VaultSession.createVault(
                vault, "RespPass123!".toCharArray(), Argon2Params.forTesting(), keyFile, CpuProfile.BALANCED, 2).session()) {
            AtomicBoolean verifiedDuringImport = new AtomicBoolean(false);
            AtomicBoolean probeAttempted = new AtomicBoolean(false);

            VaultEntry imported = session.importFile(
                    sourceFile,
                    VaultMetadata.ROOT_ID,
                    "multi-block.bin",
                    false,
                    CancellationToken.NONE,
                    (done, total, msg) -> {
                        if (done > 0 && done < total && probeAttempted.compareAndSet(false, true)) {
                            CompletableFuture<StorageStats> asyncProbe = CompletableFuture.supplyAsync(() -> {
                                try {
                                    VaultMetadata snap = session.getMetadataSnapshot();
                                    StorageStats stats = session.getStorageStats();
                                    long seq = session.currentCommitSequence();
                                    Argon2Params params = session.currentArgon2Params();
                                    assertNotNull(snap);
                                    assertNotNull(stats);
                                    assertNotNull(params);
                                    assertTrue(seq >= 1L);
                                    return stats;
                                } catch (Exception ex) {
                                    throw new RuntimeException(ex);
                                }
                            });
                            try {
                                StorageStats stats = asyncProbe.get(500, TimeUnit.MILLISECONDS);
                                assertNotNull(stats);
                                verifiedDuringImport.set(true);
                            } catch (Exception ex) {
                                throw new RuntimeException("UI read snapshot blocked during active import", ex);
                            }
                        }
                    });

            assertTrue(probeAttempted.get(), "Expected multi-block import to emit mid-stream progress");
            assertTrue(verifiedDuringImport.get(), "Snapshot reads should return immediately without waiting on write lock");
            assertEquals(payload.length, imported.logicalSize());
            assertEquals(5, imported.blocks().size());
            assertEquals(payload.length, session.getStorageStats().logicalSize());
        }

        try (VaultSession reopened = VaultSession.openWithPassword(
                vault, "RespPass123!".toCharArray(), CpuProfile.BALANCED, 2)) {
            VaultEntry entry = reopened.getMetadataSnapshot()
                    .findChildByName(VaultMetadata.ROOT_ID, "multi-block.bin")
                    .orElseThrow();
            Path exported = tempDir.resolve("multi-block-out.bin");
            reopened.exportFile(entry.id(), exported, CancellationToken.NONE, ProgressListener.NO_OP);
            assertArrayEquals(payload, Files.readAllBytes(exported));
        }
    }

    @Test
    void ensureDataLeaseForCoversLargeBlockCountWithoutMidImportPersist(@TempDir Path tempDir) throws Exception {
        Path container = tempDir.resolve("lease-container.bin");
        byte[] dek = SensitiveBytes.randomBytes(VaultKeys.DEK_BYTES);
        byte[] containerId = SensitiveBytes.randomBytes(16);

        try (VaultKeys keys = VaultKeys.fromDek(dek, containerId);
             FileChannel ch = FileChannel.open(
                     container,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.READ,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            SensitiveBytes.wipe(dek);
            ch.write(ByteBuffer.allocate((int) VaultConstants.RECORDS_START_OFFSET), 0L);

            NonceLeaseManager leaseManager = NonceLeaseManager.initializeNew(
                    ch, VaultConstants.FORMAT_VERSION, containerId, keys);

            leaseManager.ensureDataLeaseFor(2500L);
            long prev = 0L;
            for (int i = 0; i < 2500; i++) {
                long next = leaseManager.nextDataCounter();
                assertEquals(prev + 1L, next);
                prev = next;
            }

            NonceLeaseManager reloaded = NonceLeaseManager.loadFromChannel(
                    ch, VaultConstants.FORMAT_VERSION, containerId, keys);
            long afterReload = reloaded.nextDataCounter();
            assertEquals(2501L, afterReload);
        }
    }
}
