package com.sapphircrypt.sapphirypt.storage;

import com.sapphircrypt.sapphirypt.crypto.AadBuilder;
import com.sapphircrypt.sapphirypt.crypto.AesGcmCipher;
import com.sapphircrypt.sapphirypt.crypto.Argon2Params;
import com.sapphircrypt.sapphirypt.crypto.RecoveryKeyFile;
import com.sapphircrypt.sapphirypt.crypto.SensitiveBytes;
import com.sapphircrypt.sapphirypt.crypto.VaultKeys;
import com.sapphircrypt.sapphirypt.format.KeySlot;
import com.sapphircrypt.sapphirypt.format.NonceLeaseManager;
import com.sapphircrypt.sapphirypt.format.RecordFrame;
import com.sapphircrypt.sapphirypt.format.VaultConstants;
import com.sapphircrypt.sapphirypt.format.VaultHeader;
import com.sapphircrypt.sapphirypt.format.VaultMetadata;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.BlockRef;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.EntryType;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.StorageStats;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.VaultEntry;

import javax.crypto.AEADBadTagException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Core single-file encrypted vault session managing locking, cryptographic records,
 * streaming block I/O, parallel worker pool, caching, key slot rotation, and compaction.
 */
public final class VaultSession implements AutoCloseable {

    public record VaultInfo(Path vaultPath, byte[] containerId, short formatVersion, long createdAtEpochMillis, Argon2Params argon2Params, boolean hasRecoverySlot) { }

    public record VaultCreationResult(VaultSession session, Path recoveryKeyPath) { }

    private sealed interface PendingImportChunk permits PendingReusedChunk, PendingEncryptedChunk {
        int blockIndex();
        int plaintextLength();
    }

    private record PendingReusedChunk(int blockIndex, int plaintextLength, BlockRef ref) implements PendingImportChunk {}

    private record PreparedBlock(UUID blockId, int blockIndex, int plaintextLength, long nonceCounter, long recordSequence, byte[] plaintextSha256, byte[] framedRecordBytes) { }

    private record PendingEncryptedChunk(int blockIndex, int plaintextLength, Future<PreparedBlock> future) implements PendingImportChunk { }
    private record CommitValidationResult(VaultMetadata metadata, long commitOffset, byte[] commitHash, long commitSequence, int metadataRecordLength) { }

    private volatile Path vaultPath;
    private final VaultHeader header;
    private final VaultKeys keys;
    private final NonceLeaseManager nonceLeaseManager;
    private final CryptoWorkerPool workerPool;
    private final BlockCache blockCache;
    private final TempPreviewManager tempPreviewManager;
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock(true);
    private final Set<CancellationToken> activeTokens = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile FileChannel channel;
    private FileLock fileLock;
    private volatile KeySlot activePasswordSlot;
    private KeySlot activeRecoverySlot;
    private volatile VaultMetadata metadata;
    private long lastCommitOffset;
    private byte[] lastCommitHash;
    private volatile long lastCommitSequence;
    private int lastMetadataRecordLength;
    private volatile StorageStats cachedStorageStats;

    private VaultSession(Path vaultPath, FileChannel channel, FileLock fileLock, VaultHeader header, VaultKeys keys, NonceLeaseManager nonceLeaseManager, KeySlot activePasswordSlot, KeySlot activeRecoverySlot, VaultMetadata metadata, long lastCommitOffset, byte[] lastCommitHash, long lastCommitSequence, int lastMetadataRecordLength, CpuProfile cpuProfile, int maxWorkersOverride) throws IOException {
        this.vaultPath = vaultPath.toAbsolutePath().normalize();
        this.channel = channel;
        this.fileLock = fileLock;
        this.header = header;
        this.keys = keys;
        this.nonceLeaseManager = nonceLeaseManager;
        this.activePasswordSlot = activePasswordSlot;
        this.activeRecoverySlot = activeRecoverySlot;
        this.metadata = metadata;
        this.lastCommitOffset = lastCommitOffset;
        this.lastCommitHash = lastCommitHash;
        this.lastCommitSequence = lastCommitSequence;
        this.lastMetadataRecordLength = lastMetadataRecordLength;
        this.workerPool = new CryptoWorkerPool(cpuProfile, maxWorkersOverride);
        this.blockCache = new BlockCache();
        this.tempPreviewManager = new TempPreviewManager();
        this.cachedStorageStats = metadata.computeStats(channel.size(), lastMetadataRecordLength);
    }

    public static VaultCreationResult createVault(Path vaultPath, char[] password, Argon2Params argon2Params, Path recoveryKeyOutputPath, CpuProfile cpuProfile, int maxWorkersOverride) throws VaultException, IOException {
        Objects.requireNonNull(vaultPath, "vaultPath");
        Objects.requireNonNull(recoveryKeyOutputPath, "recoveryKeyOutputPath");
        if (password == null || password.length == 0) {
            throw new VaultException("Parola boş olamaz");
        }
        Argon2Params params = argon2Params == null ? Argon2Params.recommendForSystem() : argon2Params;
        params.validateForExecution();

        Path normalizedVault = vaultPath.toAbsolutePath().normalize();
        Path parent = normalizedVault.getParent();
        if (parent != null) Files.createDirectories(parent);

        VaultHeader header = VaultHeader.createNew();
        byte[] dek = SensitiveBytes.randomBytes(VaultKeys.DEK_BYTES);
        VaultKeys keys = null;
        RecoveryKeyFile recoveryKeyFile = null;
        byte[] recoverySecret = null;
        FileChannel channel = null;
        FileLock lock = null;
        boolean createdSuccess = false;

        try {
            keys = VaultKeys.fromDek(dek, header.containerId());
            recoveryKeyFile = RecoveryKeyFile.generate(header.containerId());
            recoverySecret = recoveryKeyFile.recoverySecretCopy();

            KeySlot pwSlotA = KeySlot.createPasswordSlot(header.formatVersion(), header.containerId(), KeySlot.SIDE_A, 1L, password, params, dek);
            KeySlot recSlotA = KeySlot.createRecoverySlot(header.formatVersion(), header.containerId(), KeySlot.SIDE_A, 1L, recoverySecret, dek);

            channel = FileChannel.open(normalizedVault, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ, StandardOpenOption.WRITE);
            lock = acquireExclusiveLock(channel);

            RecordFrame.writeFully(channel, VaultConstants.HEADER_OFFSET, ByteBuffer.wrap(header.encode()));
            RecordFrame.writeFully(channel, VaultConstants.PASSWORD_SLOT_A_OFFSET, ByteBuffer.wrap(pwSlotA.encode()));
            RecordFrame.writeFully(channel, VaultConstants.PASSWORD_SLOT_B_OFFSET, ByteBuffer.wrap(KeySlot.emptyEncodedSlot()));
            RecordFrame.writeFully(channel, VaultConstants.RECOVERY_SLOT_A_OFFSET, ByteBuffer.wrap(recSlotA.encode()));
            RecordFrame.writeFully(channel, VaultConstants.RECOVERY_SLOT_B_OFFSET, ByteBuffer.wrap(KeySlot.emptyEncodedSlot()));

            NonceLeaseManager leaseManager = NonceLeaseManager.initializeNew(channel, header.formatVersion(), header.containerId(), keys);

            VaultMetadata initialMeta = VaultMetadata.createEmpty();
            byte[] metaPlain = initialMeta.serialize();
            long metaOffset = VaultConstants.RECORDS_START_OFFSET;
            long metaSeq = initialMeta.metadataSequence();
            long metaRecSeq = initialMeta.allocateRecordSequence();
            long metaNonceCtr = leaseManager.nextMetadataCounter();
            byte[] metaNonce = AesGcmCipher.buildCounterNonce(header.metadataNoncePrefix(), metaNonceCtr);
            byte[] metaAad = AadBuilder.forMetadataSnapshot(header.formatVersion(), header.containerId(), metaSeq, metaNonceCtr, metaPlain.length);
            byte[] metaCipher = AesGcmCipher.encrypt(keys.metadataKey(), metaNonce, metaPlain, metaAad);
            byte[] metaFrame = RecordFrame.encodeMetadataSnapshot(metaRecSeq, metaSeq, metaNonceCtr, metaPlain.length, metaCipher);
            byte[] metaHash = SensitiveBytes.sha256(metaFrame);
            RecordFrame.writeFully(channel, metaOffset, ByteBuffer.wrap(metaFrame));
            channel.force(true);

            long commitOffset = metaOffset + metaFrame.length;
            long commitSeq = 1L;
            long commitRecSeq = initialMeta.allocateRecordSequence();
            long commitNonceCtr = leaseManager.nextCommitCounter();
            byte[] zeroHash = new byte[32];
            byte[] commitNonce = AesGcmCipher.buildCounterNonce(header.commitNoncePrefix(), commitNonceCtr);
            byte[] commitAad = AadBuilder.forCommitRecord(header.formatVersion(), header.containerId(), commitSeq,commitNonceCtr, 0L, zeroHash, metaOffset, metaFrame.length,metaHash);
            byte[] commitPlain = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(commitSeq).array();
            byte[] commitCipher = AesGcmCipher.encrypt(keys.commitKey(), commitNonce, commitPlain, commitAad);
            byte[] commitFrame = RecordFrame.encodeCommit(commitRecSeq,commitSeq,commitNonceCtr, 0L,zeroHash,metaOffset,metaFrame.length,metaHash,System.currentTimeMillis(),commitCipher);
            byte[] commitHash = SensitiveBytes.sha256(commitFrame);
            RecordFrame.writeFully(channel, commitOffset, ByteBuffer.wrap(commitFrame));
            channel.force(true);

            recoveryKeyFile.writeTo(recoveryKeyOutputPath);

            VaultSession session = new VaultSession(normalizedVault, channel, lock, header, keys, leaseManager, pwSlotA, recSlotA, initialMeta, commitOffset, commitHash, commitSeq, metaFrame.length, cpuProfile, maxWorkersOverride);
            createdSuccess = true;
            return new VaultCreationResult(session, recoveryKeyOutputPath.toAbsolutePath().normalize());
        } finally {
            SensitiveBytes.wipe(dek);
            SensitiveBytes.wipe(recoverySecret);
            if (recoveryKeyFile != null) recoveryKeyFile.close();

            if (!createdSuccess) {
                if (keys != null) keys.close();

                releaseQuietly(lock, channel);
                try {
                    Files.deleteIfExists(normalizedVault);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static VaultInfo inspectVault(Path vaultPath) throws VaultException, IOException {
        Objects.requireNonNull(vaultPath, "vaultPath");
        if (!Files.isRegularFile(vaultPath)) {
            throw new VaultCorruptedException("Kasa dosyası bulunamadı: " + vaultPath);
        }
        try (FileChannel ch = FileChannel.open(vaultPath, StandardOpenOption.READ)) {
            if (ch.size() < VaultConstants.RECORDS_START_OFFSET) {
                throw new VaultCorruptedException("Kasa dosyası eksik veya bozuk");
            }
            VaultHeader header = readHeader(ch);
            KeySlot activePw = readActiveSlot(ch, KeySlot.TYPE_PASSWORD);
            KeySlot activeRec = readActiveSlot(ch, KeySlot.TYPE_RECOVERY);
            return new VaultInfo(vaultPath.toAbsolutePath().normalize(), header.containerId(), header.formatVersion(), header.createdAtEpochMillis(), activePw != null ? activePw.argon2Params() : null, activeRec != null);
        }
    }

    public static VaultSession openWithPassword(Path vaultPath, char[] password, CpuProfile cpuProfile, int maxWorkersOverride) throws VaultException, IOException {
        if (password == null || password.length == 0) throw new VaultAuthenticationException("Parola boş olamaz");

        return openInternal(vaultPath, (header, pwSlot, recSlot) -> {
            if (pwSlot == null) throw new VaultCorruptedException("Geçerli parola slotu bulunamadı");

            return pwSlot.unwrapWithPassword(header.formatVersion(), header.containerId(), password);
        }, cpuProfile, maxWorkersOverride);
    }

    public static VaultSession openWithRecoveryKey(Path vaultPath, Path recoveryKeyPath, CpuProfile cpuProfile, int maxWorkersOverride) throws VaultException, IOException {
        Objects.requireNonNull(recoveryKeyPath, "recoveryKeyPath");
        if (!Files.isRegularFile(recoveryKeyPath)) {
            throw new VaultAuthenticationException("Kurtarma anahtarı dosyası bulunamadı: " + recoveryKeyPath);
        }
        try (RecoveryKeyFile keyFile = RecoveryKeyFile.readFrom(recoveryKeyPath)) {
            return openInternal(vaultPath, (header, pwSlot, recSlot) -> {
                if (recSlot == null) {
                    throw new VaultCorruptedException("Kasada geçerli kurtarma slotu bulunamadı");
                }
                if (!SensitiveBytes.constantTimeEquals(header.containerId(), keyFile.containerId())) {
                    throw new VaultAuthenticationException("Seçilen kurtarma anahtarı bu kasaya ait değil");
                }
                byte[] secret = keyFile.recoverySecretCopy();
                try {
                    return recSlot.unwrapWithRecovery(header.formatVersion(), header.containerId(), secret);
                } finally {
                    SensitiveBytes.wipe(secret);
                }
            }, cpuProfile, maxWorkersOverride);
        }
    }

    @FunctionalInterface
    private interface DekUnwrapper {
        byte[] unwrap(VaultHeader header, KeySlot pwSlot, KeySlot recSlot) throws VaultException;
    }

    private static VaultSession openInternal(Path vaultPath, DekUnwrapper unwrapper, CpuProfile cpuProfile, int maxWorkersOverride) throws VaultException, IOException {
        Objects.requireNonNull(vaultPath, "vaultPath");
        Path normalized = vaultPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new VaultCorruptedException("Kasa dosyası bulunamadı: " + normalized);
        }

        FileChannel channel = FileChannel.open(normalized, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock = null;
        byte[] dek = null;
        VaultKeys keys = null;
        boolean opened = false;
        try {
            lock = acquireExclusiveLock(channel);
            long fileSize = channel.size();
            if (fileSize < VaultConstants.RECORDS_START_OFFSET) {
                throw new VaultCorruptedException("Kasa dosyası geçersiz boyutta: " + fileSize);
            }

            VaultHeader header = readHeader(channel);
            KeySlot activePw = readActiveSlot(channel, KeySlot.TYPE_PASSWORD);
            KeySlot activeRec = readActiveSlot(channel, KeySlot.TYPE_RECOVERY);
            dek = unwrapper.unwrap(header, activePw, activeRec);
            keys = VaultKeys.fromDek(dek, header.containerId());

            NonceLeaseManager leaseManager = NonceLeaseManager.loadFromChannel(
                    channel, header.formatVersion(), header.containerId(), keys);

            long offset = VaultConstants.RECORDS_START_OFFSET;
            long maxDataCtr = 0L;
            long maxMetaCtr = 0L;
            long maxCommitCtr = 0L;
            long maxRecordSeq = 0L;
            Map<Long, RecordFrame.CommitRecord> framedCommitsByOffset = new LinkedHashMap<>();
            List<RecordFrame.CommitRecord> commitCandidates = new ArrayList<>();

            while (offset + VaultConstants.RECORD_HEADER_SIZE <= fileSize) {
                RecordFrame.RecordHeader rh = RecordFrame.readHeaderOrNull(channel, offset, fileSize);
                if (rh == null) {
                    break;
                }
                maxRecordSeq = Math.max(maxRecordSeq, rh.recordSequence());
                byte[] fullRecord = new byte[rh.totalRecordLength()];
                RecordFrame.readFully(channel, offset, ByteBuffer.wrap(fullRecord));
                if (RecordFrame.verifyFooter(fullRecord, rh)) {
                    switch (rh.recordType()) {
                        case VaultConstants.RECORD_TYPE_DATA_BLOCK -> {
                            RecordFrame.DataBlockRecord dbr = RecordFrame.parseDataBlockRecord(fullRecord, rh);
                            if (dbr != null) {
                                maxDataCtr = Math.max(maxDataCtr, dbr.nonceCounter());
                            }
                        }
                        case VaultConstants.RECORD_TYPE_METADATA_SNAPSHOT -> {
                            RecordFrame.MetadataSnapshotRecord msr = RecordFrame.parseMetadataSnapshotRecord(fullRecord, rh);
                            if (msr != null) {
                                maxMetaCtr = Math.max(maxMetaCtr, msr.nonceCounter());
                            }
                        }
                        case VaultConstants.RECORD_TYPE_COMMIT -> {
                            RecordFrame.CommitRecord cr = RecordFrame.parseCommitRecord(offset, fullRecord, rh);
                            if (cr != null) {
                                maxCommitCtr = Math.max(maxCommitCtr, cr.nonceCounter());
                                framedCommitsByOffset.put(offset, cr);
                                commitCandidates.add(cr);
                            }
                        }
                        default -> {
                        }
                    }
                }
                offset += rh.totalRecordLength();
            }

            long scannedEndOffset = offset;
            leaseManager.ensureAtLeast(maxDataCtr + 1L, maxMetaCtr + 1L, maxCommitCtr + 1L);

            commitCandidates.sort(Comparator.comparingLong(RecordFrame.CommitRecord::commitSequence).reversed().thenComparing(Comparator.comparingLong(RecordFrame.CommitRecord::recordOffset).reversed()));

            CommitValidationResult validCommit = null;
            for (RecordFrame.CommitRecord candidate : commitCandidates) {
                validCommit = validateCommitCandidate(channel, scannedEndOffset, header, keys, candidate, framedCommitsByOffset);
                if (validCommit != null) break;

            }
            if (validCommit == null) throw new VaultCorruptedException("Kasada doğrulanabilir geçerli bir commit kaydı bulunamadı");


            if (scannedEndOffset < fileSize) {
                long commitEnd = validCommit.commitOffset() + RecordFrame.COMMIT_TOTAL_RECORD_BYTES;
                if (commitEnd <= scannedEndOffset) {
                    channel.truncate(scannedEndOffset);
                    channel.force(true);
                }
            }

            validCommit.metadata().ensureNextRecordSequenceAtLeast(maxRecordSeq + 1L);

            VaultSession session = new VaultSession(normalized, channel, lock, header, keys, leaseManager, activePw, activeRec, validCommit.metadata(), validCommit.commitOffset(), validCommit.commitHash(), validCommit.commitSequence(), validCommit.metadataRecordLength(), cpuProfile, maxWorkersOverride);
            opened = true;
            return session;
        } finally {
            SensitiveBytes.wipe(dek);
            if (!opened) {
                if (keys != null) keys.close();

                releaseQuietly(lock, channel);
            }
        }
    }

    private static CommitValidationResult validateCommitCandidate(FileChannel channel, long validAreaLimit, VaultHeader header, VaultKeys keys, RecordFrame.CommitRecord candidate, Map<Long, RecordFrame.CommitRecord> framedCommitsByOffset) {
        try {
            if (candidate.prevCommitOffset() != 0L) {
                RecordFrame.CommitRecord prev = framedCommitsByOffset.get(candidate.prevCommitOffset());
                if (prev == null) return null;

                byte[] actualPrevHash = SensitiveBytes.sha256(prev.rawRecordBytes());
                if (!SensitiveBytes.constantTimeEquals(actualPrevHash, candidate.prevCommitHash())) return null;

            }

            byte[] commitNonce = AesGcmCipher.buildCounterNonce(header.commitNoncePrefix(), candidate.nonceCounter());
            byte[] commitAad = AadBuilder.forCommitRecord(header.formatVersion(), header.containerId(), candidate.commitSequence(), candidate.nonceCounter(),candidate.prevCommitOffset(), candidate.prevCommitHash(), candidate.metadataOffset(), candidate.metadataLength(), candidate.metadataHash());
            byte[] commitPlain = AesGcmCipher.decrypt(keys.commitKey(), commitNonce, candidate.encryptedBodyAndTag(), commitAad);
            long decryptedSeq = ByteBuffer.wrap(commitPlain).order(ByteOrder.BIG_ENDIAN).getLong();
            SensitiveBytes.wipe(commitPlain);
            if (decryptedSeq != candidate.commitSequence()) return null;


            long metaOff = candidate.metadataOffset();
            int metaLen = candidate.metadataLength();
            if (metaOff < VaultConstants.RECORDS_START_OFFSET || metaLen < VaultConstants.RECORD_FRAME_OVERHEAD || metaOff + metaLen > validAreaLimit) return null;

            byte[] metaFull = new byte[metaLen];
            RecordFrame.readFully(channel, metaOff, ByteBuffer.wrap(metaFull));
            byte[] actualMetaHash = SensitiveBytes.sha256(metaFull);
            if (!SensitiveBytes.constantTimeEquals(actualMetaHash, candidate.metadataHash())) return null;

            RecordFrame.RecordHeader metaHeader = RecordFrame.parseHeaderOrNull(metaFull, metaOff, validAreaLimit);
            if (metaHeader == null || metaHeader.totalRecordLength() != metaLen) return null;

            RecordFrame.MetadataSnapshotRecord msr = RecordFrame.parseMetadataSnapshotRecord(metaFull, metaHeader);
            if (msr == null) return null;


            byte[] metaNonce = AesGcmCipher.buildCounterNonce(header.metadataNoncePrefix(), msr.nonceCounter());
            byte[] metaAad = AadBuilder.forMetadataSnapshot(header.formatVersion(), header.containerId(), msr.snapshotSequence(), msr.nonceCounter(), msr.plaintextLength());
            byte[] metaPlain = AesGcmCipher.decrypt(keys.metadataKey(), metaNonce, msr.ciphertextAndTag(), metaAad);
            VaultMetadata parsedMeta;
            try {
                parsedMeta = VaultMetadata.deserialize(metaPlain);
            } finally {
                SensitiveBytes.wipe(metaPlain);
            }
            if (parsedMeta.metadataSequence() != msr.snapshotSequence()) return null;

            for (BlockRef ref : parsedMeta.collectUniqueActiveBlocks().values()) {
                if (ref.recordOffset() < VaultConstants.RECORDS_START_OFFSET || ref.recordOffset() + ref.encryptedRecordLength() > validAreaLimit) {
                    return null;
                }
            }

            byte[] commitHash = SensitiveBytes.sha256(candidate.rawRecordBytes());
            return new CommitValidationResult(parsedMeta, candidate.recordOffset(), commitHash, candidate.commitSequence(), metaLen);
        } catch (AEADBadTagException | VaultCorruptedException | IOException | RuntimeException e) {
            return null;
        }
    }

    public Path vaultPath() {
        return vaultPath;
    }

    public VaultHeader header() {
        return header;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public TempPreviewManager tempPreviewManager() {
        return tempPreviewManager;
    }

    public Argon2Params currentArgon2Params() {
        KeySlot slot = this.activePasswordSlot;
        return slot != null ? slot.argon2Params() : null;
    }

    public long currentCommitSequence() {
        return this.lastCommitSequence;
    }

    public VaultMetadata getMetadataSnapshot() throws VaultLockedException {
        ensureOpen();
        VaultMetadata snap = this.metadata;
        return snap.copyMutable();
    }

    public static final long AUTO_COMPACT_MIN_ORPHAN_BYTES = 64L * 1024L;

    public StorageStats getStorageStats() throws VaultLockedException, IOException {
        ensureOpen();
        return this.cachedStorageStats;
    }

    public boolean hasReclaimableSpaceForAutoCompact() {
        if (isClosed()) {
            return false;
        }
        StorageStats st = this.cachedStorageStats;
        return st != null && st.orphanBytes() >= AUTO_COMPACT_MIN_ORPHAN_BYTES;
    }

    public BlockCache.CacheStats getCacheStats() {
        return blockCache.stats();
    }

    public void clearCache() {
        blockCache.clear();
    }

    public void reconfigureWorkers(CpuProfile profile, int maxWorkersOverride) {
        workerPool.reconfigure(profile, maxWorkersOverride);
    }

    public CpuProfile cpuProfile() {
        return workerPool.profile();
    }

    public int workerCount() {
        return workerPool.workerCount();
    }

    public VaultEntry createFolder(UUID parentId, String name) throws VaultException, IOException {
        String validName = validateEntryName(name);
        stateLock.writeLock().lock();
        try {
            ensureOpen();
            VaultMetadata working = metadata.copyMutable();
            ensureFolderExists(working, parentId);
            if (working.findChildByName(parentId, validName).isPresent()) {
                throw new VaultException("Bu isimde bir öğe zaten var: " + validName);
            }
            long now = System.currentTimeMillis();
            VaultEntry folder = new VaultEntry(UUID.randomUUID(), parentId, EntryType.FOLDER, validName, now, now, 0L, List.of());
            working.putEntry(folder);
            commitMetadataLocked(working);
            return folder;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public VaultEntry renameEntry(UUID entryId, String newName) throws VaultException, IOException {
        if (VaultMetadata.ROOT_ID.equals(entryId)) {
            throw new VaultException("Kök klasör yeniden adlandırılamaz");
        }
        String validName = validateEntryName(newName);
        stateLock.writeLock().lock();
        try {
            ensureOpen();
            VaultMetadata working = metadata.copyMutable();
            VaultEntry existing = working.getEntry(entryId).orElseThrow(() -> new VaultException("Öğe bulunamadı"));
            Optional<VaultEntry> collision = working.findChildByName(existing.parentId(), validName);
            if (collision.isPresent() && !collision.get().id().equals(entryId)) {
                throw new VaultException("Bu isimde başka bir öğe zaten mevcut: " + validName);
            }
            VaultEntry updated = new VaultEntry(existing.id(), existing.parentId(), existing.type(), validName, existing.createdAt(), System.currentTimeMillis(), existing.logicalSize(), existing.blocks());
            working.putEntry(updated);
            commitMetadataLocked(working);
            return updated;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public VaultEntry moveEntry(UUID entryId, UUID targetParentId) throws VaultException, IOException {
        if (VaultMetadata.ROOT_ID.equals(entryId)) {
            throw new VaultException("Kök klasör taşınamaz");
        }
        stateLock.writeLock().lock();
        try {
            ensureOpen();
            VaultMetadata working = metadata.copyMutable();
            VaultEntry existing = working.getEntry(entryId).orElseThrow(() -> new VaultException("Taşınacak öğe bulunamadı"));
            ensureFolderExists(working, targetParentId);
            if (existing.parentId().equals(targetParentId)) return existing;

            if (existing.isFolder() && working.isDescendantOrSelf(targetParentId, entryId)) {
                throw new VaultException("Bir klasör kendisinin veya alt klasörünün içine taşınamaz");
            }
            if (working.findChildByName(targetParentId, existing.name()).isPresent()) {
                throw new VaultException("Hedef klasörde aynı isimde bir öğe zaten var: " + existing.name());
            }
            VaultEntry moved = new VaultEntry(existing.id(), targetParentId, existing.type(), existing.name(), existing.createdAt(), System.currentTimeMillis(), existing.logicalSize(), existing.blocks());
            working.putEntry(moved);
            commitMetadataLocked(working);
            return moved;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public VaultEntry copyEntry(UUID entryId, UUID targetParentId, String targetName) throws VaultException, IOException {
        if (VaultMetadata.ROOT_ID.equals(entryId)) {
            throw new VaultException("Kök klasör kopyalanamaz");
        }
        stateLock.writeLock().lock();
        try {
            ensureOpen();
            VaultMetadata working = metadata.copyMutable();
            VaultEntry source = working.getEntry(entryId).orElseThrow(() -> new VaultException("Kopyalanacak öğe bulunamadı"));
            ensureFolderExists(working, targetParentId);
            if (source.isFolder() && working.isDescendantOrSelf(targetParentId, entryId)) {
                throw new VaultException("Bir klasör kendisinin veya alt klasörünün içine kopyalanamaz");
            }
            String effectiveName = validateEntryName(targetName == null ? source.name() : targetName);
            if (working.findChildByName(targetParentId, effectiveName).isPresent()) {
                throw new VaultException("Hedef klasörde aynı isimde bir öğe zaten var: " + effectiveName);
            }
            VaultEntry copied = copyEntryRecursive(working, source, targetParentId, effectiveName);
            commitMetadataLocked(working);
            return copied;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private VaultEntry copyEntryRecursive(
            VaultMetadata working, VaultEntry source, UUID newParentId, String newName) {
        long now = System.currentTimeMillis();
        UUID newId = UUID.randomUUID();
        VaultEntry copy = new VaultEntry(newId, newParentId, source.type(), newName, now, now, source.logicalSize(), source.blocks());
        working.putEntry(copy);
        if (source.isFolder()) {
            for (VaultEntry child : working.listChildren(source.id())) {
                copyEntryRecursive(working, child, newId, child.name());
            }
        }
        return copy;
    }

    public void deleteEntries(Collection<UUID> entryIds) throws VaultException, IOException {
        if (entryIds == null || entryIds.isEmpty()) {
            return;
        }
        stateLock.writeLock().lock();
        try {
            ensureOpen();
            VaultMetadata working = metadata.copyMutable();
            boolean changed = false;
            for (UUID id : entryIds) {
                if (id == null || VaultMetadata.ROOT_ID.equals(id) || working.getEntry(id).isEmpty()) continue;

                for (UUID subtreeId : working.collectSubtreeIds(id)) {
                    if (!VaultMetadata.ROOT_ID.equals(subtreeId)) {
                        working.removeEntry(subtreeId);
                        changed = true;
                    }
                }
            }
            if (changed) {
                commitMetadataLocked(working);
            }
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public VaultEntry importFile(Path sourceFile, UUID targetFolderId, String targetName, boolean overwrite, CancellationToken cancel, ProgressListener progress) throws VaultException, IOException {
        Objects.requireNonNull(sourceFile, "sourceFile");
        if (!Files.isRegularFile(sourceFile)) {
            throw new VaultException("İçe aktarılacak dosya bulunamadı: " + sourceFile);
        }
        String effectiveName = validateEntryName(targetName == null ? sourceFile.getFileName().toString() : targetName);
        CancellationToken effectiveCancel = cancel == null ? CancellationToken.NONE : cancel;
        ProgressListener effectiveProgress = progress == null ? ProgressListener.NO_OP : progress;

        activeTokens.add(effectiveCancel);
        stateLock.writeLock().lock();
        try {
            ensureOpen();
            effectiveCancel.throwIfCancelled();

            VaultMetadata working = metadata.copyMutable();
            ensureFolderExists(working, targetFolderId);

            Optional<VaultEntry> existingOpt = working.findChildByName(targetFolderId, effectiveName);
            VaultEntry existingFile = null;
            if (existingOpt.isPresent()) {
                if (existingOpt.get().isFolder()) {
                    throw new VaultException("Hedef konumda aynı isimde bir klasör zaten mevcut: " + effectiveName);
                }
                if (!overwrite) {
                    throw new VaultException("Hedef konumda aynı isimde bir dosya zaten mevcut: " + effectiveName);
                }
                existingFile = existingOpt.get();
            }

            long initialSize = Files.size(sourceFile);
            FileTime initialMtime = Files.getLastModifiedTime(sourceFile);
            long estimatedBlocks = (initialSize + VaultConstants.MAX_BLOCK_PLAINTEXT_SIZE - 1L) / VaultConstants.MAX_BLOCK_PLAINTEXT_SIZE;
            nonceLeaseManager.ensureDataLeaseFor(estimatedBlocks);
            Map<Integer, BlockRef> reusableByIndex = new HashMap<>();
            if (existingFile != null) {
                for (BlockRef ref : existingFile.blocks()) {
                    reusableByIndex.put(ref.blockIndex(), ref);
                }
            }

            List<BlockRef> newBlocks = new ArrayList<>();
            Deque<PendingImportChunk> window = new ArrayDeque<>();
            int windowCap = Math.max(2, workerPool.maxInFlight());
            long totalRead = 0L;
            long completedBytes = 0L;

            try (FileChannel src = FileChannel.open(sourceFile, StandardOpenOption.READ)) {
                int blockIndex = 0;
                while (true) {
                    effectiveCancel.throwIfCancelled();
                    byte[] chunkBuf = new byte[VaultConstants.MAX_BLOCK_PLAINTEXT_SIZE];
                    ByteBuffer bb = ByteBuffer.wrap(chunkBuf);
                    int readInChunk = 0;
                    while (bb.hasRemaining()) {
                        int n = src.read(bb);
                        if (n < 0) break;

                        readInChunk += n;
                    }
                    if (readInChunk == 0) {
                        SensitiveBytes.wipe(chunkBuf);
                        break;
                    }

                    byte[] exactChunk = readInChunk == chunkBuf.length ? chunkBuf : Arrays.copyOf(chunkBuf, readInChunk);
                    if (exactChunk != chunkBuf) {
                        SensitiveBytes.wipe(chunkBuf);
                    }
                    totalRead += readInChunk;

                    BlockRef prevRef = reusableByIndex.get(blockIndex);
                    byte[] precomputedSha = (prevRef != null && prevRef.plaintextLength() == readInChunk)
                            ? SensitiveBytes.sha256(exactChunk)
                            : null;
                    if (precomputedSha != null && SensitiveBytes.constantTimeEquals(prevRef.plaintextSha256(), precomputedSha)) {
                        SensitiveBytes.wipe(exactChunk);
                        window.addLast(new PendingReusedChunk(blockIndex, readInChunk, prevRef));
                    } else {
                        UUID blockId = UUID.randomUUID();
                        long nonceCounter = nonceLeaseManager.nextDataCounter();
                        long recordSeq = working.allocateRecordSequence();
                        int currentIdx = blockIndex;
                        int ptLen = readInChunk;
                        Future<PreparedBlock> future = workerPool.submit(effectiveCancel, buffers -> {
                            try {
                                effectiveCancel.throwIfCancelled();
                                byte[] blockSha = precomputedSha != null ? precomputedSha : SensitiveBytes.sha256(exactChunk);
                                byte[] nonce = AesGcmCipher.buildCounterNonce(header.dataNoncePrefix(), nonceCounter);
                                byte[] aad = AadBuilder.forDataBlock(header.formatVersion(), header.containerId(), blockId, currentIdx, nonceCounter, ptLen, recordSeq);
                                int ctLen = AesGcmCipher.encryptInto(keys.dataKey(), nonce, exactChunk, 0, ptLen, aad, buffers.ciphertext, 0);
                                byte[] framed = RecordFrame.encodeDataBlock(recordSeq, blockId, currentIdx, ptLen, nonceCounter, buffers.ciphertext, 0, ctLen);
                                return new PreparedBlock(blockId, currentIdx, ptLen, nonceCounter, recordSeq, blockSha, framed);
                            } finally {
                                SensitiveBytes.wipe(exactChunk);
                            }
                        });
                        window.addLast(new PendingEncryptedChunk(currentIdx, ptLen, future));
                    }

                    while (window.size() >= windowCap) {
                        completedBytes += flushHeadPendingChunk(window.removeFirst(), newBlocks, effectiveCancel);
                        effectiveProgress.onProgress(completedBytes, initialSize, effectiveName + " şifreleniyor...");
                    }
                    blockIndex++;
                }

                // Drain remaining chunks BEFORE verifying source snapshot so encrypted futures always complete/wipe cleanly
                while (!window.isEmpty()) {
                    completedBytes += flushHeadPendingChunk(window.removeFirst(), newBlocks, effectiveCancel);
                    effectiveProgress.onProgress(completedBytes, initialSize, effectiveName + " şifreleniyor...");
                }
            } catch (Exception e) {
                cancelPendingFutures(window);
                throw e;
            }

            long finalSize = Files.size(sourceFile);
            FileTime finalMtime = Files.getLastModifiedTime(sourceFile);
            if (finalSize != initialSize || !finalMtime.equals(initialMtime) || totalRead != initialSize) {
                throw new VaultException("Kaynak dosya içe aktarma sırasında değiştirildi; güvenlik için işlem commit edilmeden iptal edildi: " + effectiveName);
            }
            effectiveCancel.throwIfCancelled();

            long now = System.currentTimeMillis();
            UUID fileId = existingFile != null ? existingFile.id() : UUID.randomUUID();
            long createdAt = existingFile != null ? existingFile.createdAt() : now;
            VaultEntry fileEntry = new VaultEntry(fileId, targetFolderId, EntryType.FILE, effectiveName, createdAt, now, initialSize, newBlocks);
            working.putEntry(fileEntry);
            effectiveProgress.onProgress(initialSize, Math.max(1L, initialSize), effectiveName + " diske kaydediliyor...");
            commitMetadataLocked(working);
            effectiveProgress.onProgress(initialSize, Math.max(1L, initialSize), effectiveName + " tamamlandı");
            return fileEntry;
        } finally {
            stateLock.writeLock().unlock();
            activeTokens.remove(effectiveCancel);
        }
    }

    private int flushHeadPendingChunk(PendingImportChunk pending, List<BlockRef> outRefs, CancellationToken cancel) throws VaultException, IOException {
        cancel.throwIfCancelled();
        if (pending instanceof PendingReusedChunk reused) {
            outRefs.add(reused.ref());
            return reused.plaintextLength();
        }
        PendingEncryptedChunk enc = (PendingEncryptedChunk) pending;
        PreparedBlock prepared = awaitFuture(enc.future());
        long offset = channel.size();
        RecordFrame.writeFully(channel, offset, ByteBuffer.wrap(prepared.framedRecordBytes()));
        outRefs.add(new BlockRef(prepared.blockId(), prepared.blockIndex(), offset, prepared.framedRecordBytes().length, prepared.plaintextLength(), prepared.nonceCounter(), prepared.recordSequence(), prepared.plaintextSha256()));
        return prepared.plaintextLength();
    }

    public void importDirectory(Path sourceDir, UUID targetParentFolderId, CancellationToken cancel, ProgressListener progress) throws VaultException, IOException {
        Objects.requireNonNull(sourceDir, "sourceDir");
        if (!Files.isDirectory(sourceDir)) {
            throw new VaultException("Klasör bulunamadı: " + sourceDir);
        }
        CancellationToken effectiveCancel = cancel == null ? CancellationToken.NONE : cancel;
        String rootFolderName = validateEntryName(sourceDir.getFileName().toString());
        UUID rootTargetId = findOrCreateFolder(targetParentFolderId, rootFolderName);

        Map<Path, UUID> folderMap = new HashMap<>();
        folderMap.put(sourceDir.toAbsolutePath().normalize(), rootTargetId);

        List<Path> filesToImport = new ArrayList<>();
        Files.walkFileTree(sourceDir.toAbsolutePath().normalize(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (effectiveCancel.isCancelled()) return FileVisitResult.TERMINATE;

                if (!dir.equals(sourceDir.toAbsolutePath().normalize())) {
                    UUID parentUuid = folderMap.get(dir.getParent());
                    try {
                        UUID folderUuid = findOrCreateFolder(parentUuid, dir.getFileName().toString());
                        folderMap.put(dir, folderUuid);
                    } catch (VaultException e) {
                        throw new IOException(e);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (effectiveCancel.isCancelled()) return FileVisitResult.TERMINATE;

                if (attrs.isRegularFile()) filesToImport.add(file);

                return FileVisitResult.CONTINUE;
            }
        });
        effectiveCancel.throwIfCancelled();

        long totalBytes = 0L;
        for (Path p : filesToImport) {
            totalBytes += Files.size(p);
        }
        long doneBytes = 0L;
        for (Path file : filesToImport) {
            effectiveCancel.throwIfCancelled();
            UUID targetFolder = folderMap.get(file.getParent());
            long baseDone = doneBytes;
            long grandTotal = Math.max(1L, totalBytes);
            long fSize = Files.size(file);
            importFile(file, targetFolder, file.getFileName().toString(), false, effectiveCancel, (c, t, msg) -> {
                if (progress != null) progress.onProgress(baseDone + c, grandTotal, msg);
            });
            doneBytes += fSize;
        }
    }

    private UUID findOrCreateFolder(UUID parentId, String name) throws VaultException, IOException {
        VaultMetadata snap = getMetadataSnapshot();
        Optional<VaultEntry> existing = snap.findChildByName(parentId, name);
        if (existing.isPresent()) {
            if (!existing.get().isFolder()) {
                throw new VaultException("Aynı isimde bir dosya zaten mevcut: " + name);
            }
            return existing.get().id();
        }
        return createFolder(parentId, name).id();
    }

    public void exportFile(UUID entryId, Path destinationFile, CancellationToken cancel, ProgressListener progress) throws VaultException, IOException {
        Objects.requireNonNull(destinationFile, "destinationFile");
        CancellationToken effectiveCancel = cancel == null ? CancellationToken.NONE : cancel;
        ProgressListener effectiveProgress = progress == null ? ProgressListener.NO_OP : progress;

        activeTokens.add(effectiveCancel);
        stateLock.readLock().lock();
        Path partFile = destinationFile.toAbsolutePath().normalize()
                .resolveSibling(destinationFile.getFileName().toString() + ".sapphirypt.part");
        boolean success = false;
        try {
            ensureOpen();
            effectiveCancel.throwIfCancelled();

            VaultEntry entry = metadata.getEntry(entryId).orElseThrow(() -> new VaultException("Dışa aktarılacak dosya bulunamadı"));
            if (!entry.isFile()) {
                throw new VaultException("Seçilen öğe bir dosya değil: " + entry.name());
            }

            Path parent = destinationFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            List<BlockRef> blocks = new ArrayList<>(entry.blocks());
            blocks.sort(Comparator.comparingInt(BlockRef::blockIndex));
            for (int i = 0; i < blocks.size(); i++) {
                if (blocks.get(i).blockIndex() != i) {
                    throw new VaultIntegrityException("Dosya blok sırası bozuk (eksik Block Index " + i + ")");
                }
            }

            try (FileChannel outChannel = FileChannel.open(partFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                long totalSize = entry.logicalSize();
                long writtenBytes = 0L;

                if (blocks.size() <= 1) {
                    for (BlockRef ref : blocks) {
                        effectiveCancel.throwIfCancelled();
                        byte[] pt = readAndVerifyBlock(ref);
                        try {
                            RecordFrame.writeFully(outChannel, writtenBytes, ByteBuffer.wrap(pt));
                            writtenBytes += pt.length;
                            effectiveProgress.onProgress(writtenBytes, Math.max(1L, totalSize), entry.name() + " çözülüyor...");
                        } finally {
                            SensitiveBytes.wipe(pt);
                        }
                    }
                } else {
                    Deque<Future<byte[]>> window = new ArrayDeque<>();
                    int windowCap = Math.max(2, workerPool.maxInFlight());
                    try {
                        for (BlockRef ref : blocks) {
                            effectiveCancel.throwIfCancelled();
                            Future<byte[]> fut = workerPool.submit(effectiveCancel, buffers -> {
                                effectiveCancel.throwIfCancelled();
                                return readAndVerifyBlock(ref);
                            });
                            window.addLast(fut);
                            while (window.size() >= windowCap) {
                                byte[] pt = awaitFuture(window.removeFirst());
                                try {
                                    RecordFrame.writeFully(outChannel, writtenBytes, ByteBuffer.wrap(pt));
                                    writtenBytes += pt.length;
                                    effectiveProgress.onProgress(writtenBytes, totalSize, entry.name() + " çözülüyor...");
                                } finally {
                                    SensitiveBytes.wipe(pt);
                                }
                            }
                        }
                        while (!window.isEmpty()) {
                            effectiveCancel.throwIfCancelled();
                            byte[] pt = awaitFuture(window.removeFirst());
                            try {
                                RecordFrame.writeFully(outChannel, writtenBytes, ByteBuffer.wrap(pt));
                                writtenBytes += pt.length;
                                effectiveProgress.onProgress(writtenBytes, totalSize, entry.name() + " çözülüyor...");
                            } finally {
                                SensitiveBytes.wipe(pt);
                            }
                        }
                    } catch (Exception e) {
                        cancelPlainFutures(window);
                        throw e;
                    }
                }
                outChannel.force(true);
            }

            Files.move(partFile, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            success = true;
            effectiveProgress.onProgress(entry.logicalSize(), Math.max(1L, entry.logicalSize()), entry.name() + " dışa aktarıldı");
        } finally {
            stateLock.readLock().unlock();
            activeTokens.remove(effectiveCancel);
            if (!success) {
                try {
                    Files.deleteIfExists(partFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public byte[] readFileBytes(UUID entryId, long maxAllowedBytes, CancellationToken cancel) throws VaultException, IOException {
        CancellationToken effectiveCancel = cancel == null ? CancellationToken.NONE : cancel;
        activeTokens.add(effectiveCancel);
        stateLock.readLock().lock();
        try {
            ensureOpen();
            effectiveCancel.throwIfCancelled();
            VaultEntry entry = metadata.getEntry(entryId).orElseThrow(() -> new VaultException("Dosya bulunamadı"));
            if (!entry.isFile()) {
                throw new VaultException("Seçilen öğe klasördür");
            }
            if (entry.logicalSize() > maxAllowedBytes) {
                throw new VaultException("Dosya önizleme sınırından büyük (" + entry.logicalSize() + " byte)");
            }
            byte[] full = new byte[(int) entry.logicalSize()];
            List<BlockRef> blocks = new ArrayList<>(entry.blocks());
            blocks.sort(Comparator.comparingInt(BlockRef::blockIndex));
            int pos = 0;
            for (BlockRef ref : blocks) {
                effectiveCancel.throwIfCancelled();
                byte[] pt = readAndVerifyBlock(ref);
                try {
                    System.arraycopy(pt, 0, full, pos, pt.length);
                    pos += pt.length;
                } finally {
                    SensitiveBytes.wipe(pt);
                }
            }
            return full;
        } finally {
            stateLock.readLock().unlock();
            activeTokens.remove(effectiveCancel);
        }
    }

    public void exportFolder(UUID folderId, Path destinationParentDir, CancellationToken cancel, ProgressListener progress) throws VaultException, IOException {
        Objects.requireNonNull(destinationParentDir, "destinationParentDir");
        CancellationToken effectiveCancel = cancel == null ? CancellationToken.NONE : cancel;
        VaultMetadata snap = getMetadataSnapshot();
        VaultEntry folder = snap.getEntry(folderId).orElseThrow(() -> new VaultException("Klasör bulunamadı"));
        if (!folder.isFolder()) {
            throw new VaultException("Seçilen öğe bir klasör değil");
        }
        Path targetFolder = VaultMetadata.ROOT_ID.equals(folderId) ? destinationParentDir : destinationParentDir.resolve(folder.name());
        Files.createDirectories(targetFolder);

        long totalBytes = snap.computeFolderLogicalSize(folderId);
        long[] doneBytes = {0L};
        exportFolderRecursive(snap, folderId, targetFolder, effectiveCancel, progress, doneBytes, Math.max(1L, totalBytes));
    }

    private void exportFolderRecursive(VaultMetadata snap, UUID folderId, Path diskDir, CancellationToken cancel, ProgressListener progress, long[] doneBytes, long totalBytes) throws VaultException, IOException {
        Files.createDirectories(diskDir);
        for (VaultEntry child : snap.listChildren(folderId)) {
            cancel.throwIfCancelled();
            if (child.isFolder()) {
                exportFolderRecursive(snap, child.id(), diskDir.resolve(child.name()), cancel, progress, doneBytes, totalBytes);
            } else {
                long baseDone = doneBytes[0];
                exportFile(child.id(), diskDir.resolve(child.name()), cancel, (c, t, msg) -> {
                    if (progress != null) progress.onProgress(baseDone + c, totalBytes, msg);

                });
                doneBytes[0] += child.logicalSize();
            }
        }
    }

    private byte[] readAndVerifyBlock(BlockRef ref) throws VaultException, IOException {
        byte[] cachedPlain = blockCache.getPlaintext(ref.blockId());
        if (cachedPlain != null) return cachedPlain;

        byte[] ciphertextWithTag = blockCache.getEncrypted(ref.blockId());
        if (ciphertextWithTag == null) {
            long fileSize = channel.size();
            if (ref.recordOffset() < VaultConstants.RECORDS_START_OFFSET || ref.recordOffset() + ref.encryptedRecordLength() > fileSize) {
                throw new VaultIntegrityException("Veri bloğu dosya sınırları dışında: " + ref.blockId());
            }
            byte[] fullRecord = new byte[ref.encryptedRecordLength()];
            RecordFrame.readFully(channel, ref.recordOffset(), ByteBuffer.wrap(fullRecord));
            RecordFrame.RecordHeader rh = RecordFrame.parseHeaderOrNull(fullRecord, ref.recordOffset(), fileSize);
            if (rh == null || rh.totalRecordLength() != ref.encryptedRecordLength()) {
                throw new VaultIntegrityException("Veri bloğu çerçeve başlığı bozuk: " + ref.blockId());
            }
            RecordFrame.DataBlockRecord dbr = RecordFrame.parseDataBlockRecord(fullRecord, rh);
            if (dbr == null) {
                throw new VaultIntegrityException("Veri bloğu altbilgi/CRC doğrulaması başarısız: " + ref.blockId());
            }
            if (!dbr.blockId().equals(ref.blockId()) || dbr.blockIndex() != ref.blockIndex() || dbr.plaintextLength() != ref.plaintextLength()
                    || dbr.nonceCounter() != ref.nonceCounter() || dbr.recordSequence() != ref.recordSequence()) {
                throw new VaultIntegrityException("Veri bloğu metaveri alanlarıyla uyuşmuyor: " + ref.blockId());
            }
            ciphertextWithTag = dbr.ciphertextAndTag();
            blockCache.putEncrypted(ref.blockId(), ciphertextWithTag);
        }

        byte[] nonce = AesGcmCipher.buildCounterNonce(header.dataNoncePrefix(), ref.nonceCounter());
        byte[] aad = AadBuilder.forDataBlock(header.formatVersion(), header.containerId(), ref.blockId(), ref.blockIndex(), ref.nonceCounter(), ref.plaintextLength(), ref.recordSequence());
        byte[] plaintext;
        try {
            plaintext = AesGcmCipher.decrypt(keys.dataKey(), nonce, ciphertextWithTag, aad);
        } catch (AEADBadTagException e) {
            throw new VaultIntegrityException("Blok AES-GCM bütünlük doğrulaması başarısız (kurcalama veya bozulma tespit edildi): " + ref.blockId(), e);
        }

        byte[] actualSha = SensitiveBytes.sha256(plaintext);
        if (!SensitiveBytes.constantTimeEquals(actualSha, ref.plaintextSha256())) {
            SensitiveBytes.wipe(plaintext);
            throw new VaultIntegrityException("Blok SHA-256 doğrulaması başarısız: " + ref.blockId());
        }
        blockCache.putPlaintext(ref.blockId(), plaintext);
        return plaintext;
    }

    public void changePassword(char[] currentPassword, char[] newPassword, Argon2Params newParams)
            throws VaultException, IOException {
        if (currentPassword == null || currentPassword.length == 0 || newPassword == null || newPassword.length == 0) {
            throw new VaultException("Mevcut ve yeni parola boş olamaz");
        }
        stateLock.writeLock().lock();
        byte[] verifiedDek = null;
        byte[] dek = null;
        try {
            ensureOpen();
            if (activePasswordSlot == null) throw new VaultCorruptedException("Etkin parola slotu bulunamadı");
            // Verify caller knows current password before rotating A/B slot
            verifiedDek = activePasswordSlot.unwrapWithPassword(header.formatVersion(), header.containerId(), currentPassword);

            Argon2Params effectiveParams = newParams != null ? newParams : activePasswordSlot.argon2Params();
            effectiveParams.validateForExecution();

            dek = keys.dekCopy();
            if (!SensitiveBytes.constantTimeEquals(verifiedDek, dek)) {
                throw new VaultAuthenticationException("Parola doğrulaması tutarsız");
            }

            byte targetSide = KeySlot.oppositeSide(activePasswordSlot.slotSide());
            long newGen = activePasswordSlot.slotGeneration() + 1L;
            KeySlot newSlot = KeySlot.createPasswordSlot(header.formatVersion(), header.containerId(), targetSide, newGen, newPassword, effectiveParams, dek);

            long targetOffset = KeySlot.slotOffset(KeySlot.TYPE_PASSWORD, targetSide);
            RecordFrame.writeFully(channel, targetOffset, ByteBuffer.wrap(newSlot.encode()));
            channel.force(true);

            // Read back and verify new slot before invalidating old slot
            byte[] verifyBuf = new byte[VaultConstants.KEY_SLOT_SIZE];
            RecordFrame.readFully(channel, targetOffset, ByteBuffer.wrap(verifyBuf));
            KeySlot readBack = KeySlot.decodeOrNull(verifyBuf, KeySlot.TYPE_PASSWORD, targetSide);
            if (readBack == null) throw new VaultCorruptedException("Yeni parola slotu doğrulama okuması başarısız");

            byte[] readBackDek = readBack.unwrapWithPassword(header.formatVersion(), header.containerId(), newPassword);
            try {
                if (!SensitiveBytes.constantTimeEquals(readBackDek, dek)) {
                    throw new VaultCorruptedException("Yeni parola slotu DEK doğrulaması başarısız");
                }
            } finally {
                SensitiveBytes.wipe(readBackDek);
            }

            long oldOffset = KeySlot.slotOffset(KeySlot.TYPE_PASSWORD, activePasswordSlot.slotSide());
            RecordFrame.writeFully(channel, oldOffset, ByteBuffer.wrap(KeySlot.emptyEncodedSlot()));
            channel.force(true);
            activePasswordSlot = readBack;
        } finally {
            SensitiveBytes.wipe(verifiedDek);
            SensitiveBytes.wipe(dek);
            stateLock.writeLock().unlock();
        }
    }

    public Path rotateRecoveryKey(Path newRecoveryKeyPath) throws VaultException, IOException {
        Objects.requireNonNull(newRecoveryKeyPath, "newRecoveryKeyPath");
        stateLock.writeLock().lock();
        byte[] dek = null;
        byte[] secret = null;
        RecoveryKeyFile newKeyFile = null;
        try {
            ensureOpen();
            dek = keys.dekCopy();
            newKeyFile = RecoveryKeyFile.generate(header.containerId());
            secret = newKeyFile.recoverySecretCopy();

            byte targetSide = activeRecoverySlot == null ? KeySlot.SIDE_A : KeySlot.oppositeSide(activeRecoverySlot.slotSide());
            long newGen = activeRecoverySlot == null ? 1L : activeRecoverySlot.slotGeneration() + 1L;
            KeySlot newSlot = KeySlot.createRecoverySlot(header.formatVersion(), header.containerId(), targetSide, newGen, secret, dek);

            long targetOffset = KeySlot.slotOffset(KeySlot.TYPE_RECOVERY, targetSide);
            RecordFrame.writeFully(channel, targetOffset, ByteBuffer.wrap(newSlot.encode()));
            channel.force(true);

            byte[] verifyBuf = new byte[VaultConstants.KEY_SLOT_SIZE];
            RecordFrame.readFully(channel, targetOffset, ByteBuffer.wrap(verifyBuf));
            KeySlot readBack = KeySlot.decodeOrNull(verifyBuf, KeySlot.TYPE_RECOVERY, targetSide);
            if (readBack == null) {
                throw new VaultCorruptedException("Yeni kurtarma slotu doğrulama okuması başarısız");
            }
            byte[] readBackDek = readBack.unwrapWithRecovery(header.formatVersion(), header.containerId(), secret);
            try {
                if (!SensitiveBytes.constantTimeEquals(readBackDek, dek)) {
                    throw new VaultCorruptedException("Yeni kurtarma slotu DEK doğrulaması başarısız");
                }
            } finally {
                SensitiveBytes.wipe(readBackDek);
            }

            newKeyFile.writeTo(newRecoveryKeyPath);

            if (activeRecoverySlot != null) {
                long oldOffset = KeySlot.slotOffset(KeySlot.TYPE_RECOVERY, activeRecoverySlot.slotSide());
                RecordFrame.writeFully(channel, oldOffset, ByteBuffer.wrap(KeySlot.emptyEncodedSlot()));
                channel.force(true);
            }
            activeRecoverySlot = readBack;
            return newRecoveryKeyPath.toAbsolutePath().normalize();
        } finally {
            SensitiveBytes.wipe(dek);
            SensitiveBytes.wipe(secret);
            if (newKeyFile != null) newKeyFile.close();

            stateLock.writeLock().unlock();
        }
    }

    public StorageStats compact(CancellationToken cancel, ProgressListener progress) throws VaultException, IOException {
        CancellationToken effectiveCancel = cancel == null ? CancellationToken.NONE : cancel;
        ProgressListener effectiveProgress = progress == null ? ProgressListener.NO_OP : progress;
        activeTokens.add(effectiveCancel);
        stateLock.writeLock().lock();

        Path compactTmp = vaultPath.resolveSibling(vaultPath.getFileName() + ".compact.tmp");
        Path backupPath = vaultPath.resolveSibling(vaultPath.getFileName() + ".compact.bak");
        boolean replaced = false;
        try {
            ensureOpen();
            effectiveCancel.throwIfCancelled();

            Map<UUID, BlockRef> uniqueBlocks = metadata.collectUniqueActiveBlocks();
            long totalUniqueBytes = 0L;
            for (BlockRef ref : uniqueBlocks.values()) {
                totalUniqueBytes += ref.encryptedRecordLength();
            }

            Map<UUID, Long> newOffsets = new HashMap<>(uniqueBlocks.size());
            VaultMetadata compactedMeta = metadata.copyMutable();
            compactedMeta.setMetadataSequence(compactedMeta.metadataSequence() + 1L);

            long newMetaOffset;
            int newMetaLength;
            long newCommitOffset;
            byte[] newCommitHash;
            long newCommitSeq = lastCommitSequence + 1L;

            try (FileChannel tmpCh = FileChannel.open(compactTmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ, StandardOpenOption.WRITE)) {

                RecordFrame.writeFully(tmpCh, VaultConstants.HEADER_OFFSET, ByteBuffer.wrap(header.encode()));

                if (activePasswordSlot != null) {
                    long pwOff = KeySlot.slotOffset(KeySlot.TYPE_PASSWORD, activePasswordSlot.slotSide());
                    long emptyPwOff = KeySlot.slotOffset(KeySlot.TYPE_PASSWORD, KeySlot.oppositeSide(activePasswordSlot.slotSide()));
                    RecordFrame.writeFully(tmpCh, pwOff, ByteBuffer.wrap(activePasswordSlot.encode()));
                    RecordFrame.writeFully(tmpCh, emptyPwOff, ByteBuffer.wrap(KeySlot.emptyEncodedSlot()));
                }
                if (activeRecoverySlot != null) {
                    long recOff = KeySlot.slotOffset(KeySlot.TYPE_RECOVERY, activeRecoverySlot.slotSide());
                    long emptyRecOff = KeySlot.slotOffset(KeySlot.TYPE_RECOVERY, KeySlot.oppositeSide(activeRecoverySlot.slotSide()));
                    RecordFrame.writeFully(tmpCh, recOff, ByteBuffer.wrap(activeRecoverySlot.encode()));
                    RecordFrame.writeFully(tmpCh, emptyRecOff, ByteBuffer.wrap(KeySlot.emptyEncodedSlot()));
                } else {
                    RecordFrame.writeFully(tmpCh, VaultConstants.RECOVERY_SLOT_A_OFFSET, ByteBuffer.wrap(KeySlot.emptyEncodedSlot()));
                    RecordFrame.writeFully(tmpCh, VaultConstants.RECOVERY_SLOT_B_OFFSET, ByteBuffer.wrap(KeySlot.emptyEncodedSlot()));
                }

                long writeCursor = VaultConstants.RECORDS_START_OFFSET;
                long copiedBytes = 0L;
                long srcFileSize = channel.size();

                for (BlockRef ref : uniqueBlocks.values()) {
                    effectiveCancel.throwIfCancelled();
                    byte[] rawRecord = new byte[ref.encryptedRecordLength()];
                    RecordFrame.readFully(channel, ref.recordOffset(), ByteBuffer.wrap(rawRecord));
                    RecordFrame.RecordHeader rh = RecordFrame.parseHeaderOrNull(rawRecord, ref.recordOffset(), srcFileSize);
                    if (rh == null || !RecordFrame.verifyFooter(rawRecord, rh)) {
                        throw new VaultIntegrityException("Sıkıştırma sırasında blok çerçevesi doğrulaması başarısız: " + ref.blockId());
                    }
                    RecordFrame.writeFully(tmpCh, writeCursor, ByteBuffer.wrap(rawRecord));
                    newOffsets.put(ref.blockId(), writeCursor);
                    writeCursor += rawRecord.length;
                    copiedBytes += rawRecord.length;
                    effectiveProgress.onProgress(copiedBytes, Math.max(1L, totalUniqueBytes), "Aktif bloklar taşınıyor...");
                }

                for (VaultEntry entry : compactedMeta.allEntries()) {
                    if (entry.isFile() && !entry.blocks().isEmpty()) {
                        List<BlockRef> updatedRefs = new ArrayList<>(entry.blocks().size());
                        for (BlockRef oldRef : entry.blocks()) {
                            Long updatedOff = newOffsets.get(oldRef.blockId());
                            if (updatedOff == null) {
                                throw new VaultCorruptedException("Sıkıştırma blok eşlemesi eksik: " + oldRef.blockId());
                            }
                            updatedRefs.add(oldRef.withOffsetAndRecordLength(updatedOff, oldRef.encryptedRecordLength()));
                        }
                        compactedMeta.putEntry(new VaultEntry(entry.id(), entry.parentId(), entry.type(), entry.name(), entry.createdAt(), entry.modifiedAt(), entry.logicalSize(),updatedRefs));
                    }
                }

                long metaSeq = compactedMeta.metadataSequence();
                long metaRecSeq = compactedMeta.allocateRecordSequence();
                long metaNonceCtr = nonceLeaseManager.nextMetadataCounter();
                byte[] metaPlain = compactedMeta.serialize();
                byte[] metaNonce = AesGcmCipher.buildCounterNonce(header.metadataNoncePrefix(), metaNonceCtr);
                byte[] metaAad = AadBuilder.forMetadataSnapshot(header.formatVersion(), header.containerId(), metaSeq, metaNonceCtr, metaPlain.length);
                byte[] metaCipher;
                try {
                    metaCipher = AesGcmCipher.encrypt(keys.metadataKey(), metaNonce, metaPlain, metaAad);
                } finally {
                    SensitiveBytes.wipe(metaPlain);
                }
                byte[] metaFrame = RecordFrame.encodeMetadataSnapshot(metaRecSeq, metaSeq, metaNonceCtr, metaPlain.length, metaCipher);
                byte[] metaHash = SensitiveBytes.sha256(metaFrame);

                newMetaOffset = writeCursor;
                newMetaLength = metaFrame.length;
                RecordFrame.writeFully(tmpCh, newMetaOffset, ByteBuffer.wrap(metaFrame));
                writeCursor += metaFrame.length;

                long commitRecSeq = compactedMeta.allocateRecordSequence();
                long commitNonceCtr = nonceLeaseManager.nextCommitCounter();
                byte[] zeroHash = new byte[32];
                byte[] commitNonce = AesGcmCipher.buildCounterNonce(header.commitNoncePrefix(), commitNonceCtr);
                byte[] commitAad = AadBuilder.forCommitRecord(header.formatVersion(), header.containerId(), newCommitSeq, commitNonceCtr, 0L, zeroHash, newMetaOffset, newMetaLength, metaHash);
                byte[] commitPlain = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(newCommitSeq).array();
                byte[] commitCipher = AesGcmCipher.encrypt(keys.commitKey(), commitNonce, commitPlain, commitAad);
                byte[] commitFrame = RecordFrame.encodeCommit(commitRecSeq, newCommitSeq, commitNonceCtr, 0L, zeroHash, newMetaOffset, newMetaLength, metaHash,System.currentTimeMillis(), commitCipher);

                newCommitOffset = writeCursor;
                newCommitHash = SensitiveBytes.sha256(commitFrame);
                RecordFrame.writeFully(tmpCh, newCommitOffset, ByteBuffer.wrap(commitFrame));

                nonceLeaseManager.writeStateToTarget(tmpCh);
                tmpCh.force(true);

                // Validate compacted container before swapping
                verifyCompactedChannel(tmpCh, header, keys, newCommitOffset, newMetaOffset, newMetaLength, metaHash);
            }

            // Swap original file safely (Windows requires closing FileChannel/FileLock before rename)
            releaseQuietly(fileLock, channel);
            fileLock = null;
            channel = null;

            moveWithFallback(vaultPath, backupPath);
            try {
                moveWithFallback(compactTmp, vaultPath);
                replaced = true;
            } catch (IOException moveError) {
                try {
                    moveWithFallback(backupPath, vaultPath);
                } catch (IOException ignored) {
                }
                throw moveError;
            } finally {
                channel = FileChannel.open(vaultPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
                fileLock = acquireExclusiveLock(channel);
                nonceLeaseManager.switchChannel(channel);
            }

            try {
                Files.deleteIfExists(backupPath);
            } catch (IOException ignored) {
            }

            long physical = channel.size();
            StorageStats compactedStats = compactedMeta.computeStats(physical, newMetaLength);
            this.lastCommitOffset = newCommitOffset;
            this.lastCommitHash = newCommitHash;
            this.lastCommitSequence = newCommitSeq;
            this.lastMetadataRecordLength = newMetaLength;
            this.cachedStorageStats = compactedStats;
            this.metadata = compactedMeta;

            effectiveProgress.onProgress(Math.max(1L, totalUniqueBytes), Math.max(1L, totalUniqueBytes), "Sıkıştırma tamamlandı");
            return compactedStats;
        } finally {
            stateLock.writeLock().unlock();
            activeTokens.remove(effectiveCancel);
            if (!replaced) {
                try {
                    Files.deleteIfExists(compactTmp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void verifyCompactedChannel(
            FileChannel tmpCh,
            VaultHeader expectedHeader,
            VaultKeys keys,
            long commitOffset,
            long metaOffset,
            int metaLength,
            byte[] metaHash) throws IOException, VaultException {
        long size = tmpCh.size();
        VaultHeader hdr = readHeader(tmpCh);
        if (!SensitiveBytes.constantTimeEquals(hdr.containerId(), expectedHeader.containerId())) {
            throw new VaultCorruptedException("Sıkıştırılmış kasa başlığı eşleşmiyor");
        }
        NonceLeaseManager.loadFromChannel(tmpCh, hdr.formatVersion(), hdr.containerId(), keys);

        byte[] commitFull = new byte[RecordFrame.COMMIT_TOTAL_RECORD_BYTES];
        RecordFrame.readFully(tmpCh, commitOffset, ByteBuffer.wrap(commitFull));
        RecordFrame.RecordHeader crh = RecordFrame.parseHeaderOrNull(commitFull, commitOffset, size);
        if (crh == null) {
            throw new VaultCorruptedException("Sıkıştırılmış kasa commit çerçevesi bozuk");
        }
        RecordFrame.CommitRecord cr = RecordFrame.parseCommitRecord(commitOffset, commitFull, crh);
        if (cr == null) {
            throw new VaultCorruptedException("Sıkıştırılmış kasa commit altbilgisi bozuk");
        }
        Map<Long, RecordFrame.CommitRecord> map = Map.of(commitOffset, cr);
        CommitValidationResult res = validateCommitCandidate(tmpCh, size, hdr, keys, cr, map);
        if (res == null || cr.metadataOffset() != metaOffset || cr.metadataLength() != metaLength || !SensitiveBytes.constantTimeEquals(cr.metadataHash(), metaHash)) {
            throw new VaultCorruptedException("Sıkıştırılmış kasa commit/metadata doğrulaması başarısız");
        }
    }

    public static String validateVaultFileName(String rawName) throws VaultException {
        if (rawName == null) {
            throw new VaultException("Kasa adı boş olamaz");
        }
        String trimmed = rawName.trim();
        if (trimmed.isEmpty() || ".".equals(trimmed) || "..".equals(trimmed)) {
            throw new VaultException("Geçersiz kasa adı: '" + rawName + "'");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch < 32 || ch == '/' || ch == '\\' || ch == ':' || ch == '*'
                    || ch == '?' || ch == '"' || ch == '<' || ch == '>' || ch == '|') {
                throw new VaultException("Kasa adı geçersiz karakter içeremez (/, \\, :, *, ?, \", <, >, |)");
            }
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        String normalized = lower.endsWith(VaultConstants.FILE_EXTENSION)
                ? trimmed.substring(0, trimmed.length() - VaultConstants.FILE_EXTENSION.length()) + VaultConstants.FILE_EXTENSION
                : trimmed + VaultConstants.FILE_EXTENSION;
        String baseName = normalized.substring(0, normalized.length() - VaultConstants.FILE_EXTENSION.length()).trim();
        if (baseName.isEmpty() || ".".equals(baseName) || "..".equals(baseName)) {
            throw new VaultException("Geçerli bir kasa adı girin");
        }
        return baseName + VaultConstants.FILE_EXTENSION;
    }

    private static void verifyPasswordAndRecoveryKey(
            VaultHeader header,
            KeySlot activePwSlot,
            KeySlot activeRecSlot,
            char[] password,
            Path recoveryKeyPath,
            byte[] expectedDekOrNull) throws VaultException, IOException {
        if (password == null || password.length == 0) {
            throw new VaultAuthenticationException("Parola boş olamaz");
        }
        if (recoveryKeyPath == null || !Files.isRegularFile(recoveryKeyPath)) {
            throw new VaultAuthenticationException("Kurtarma anahtarı (.key) dosyası seçilmelidir");
        }
        if (activePwSlot == null) {
            throw new VaultCorruptedException("Kasada geçerli parola slotu bulunamadı");
        }
        if (activeRecSlot == null) {
            throw new VaultCorruptedException("Kasada geçerli kurtarma anahtarı slotu bulunamadı");
        }

        RecoveryKeyFile keyFile;
        try {
            keyFile = RecoveryKeyFile.readFrom(recoveryKeyPath);
        } catch (IOException e) {
            throw new VaultAuthenticationException(
                    "Geçersiz veya bozuk kurtarma anahtarı (.key) dosyası: " + e.getMessage(), e);
        }

        byte[] recSecret = null;
        byte[] recDek = null;
        byte[] pwDek = null;
        try (keyFile) {
            if (!SensitiveBytes.constantTimeEquals(header.containerId(), keyFile.containerId())) {
                throw new VaultAuthenticationException("Seçilen kurtarma anahtarı (.key) bu kasaya ait değil");
            }
            recSecret = keyFile.recoverySecretCopy();
            recDek = activeRecSlot.unwrapWithRecovery(header.formatVersion(), header.containerId(), recSecret);
            pwDek = activePwSlot.unwrapWithPassword(header.formatVersion(), header.containerId(), password);
            if (!SensitiveBytes.constantTimeEquals(pwDek, recDek)) {
                throw new VaultAuthenticationException("Parola ve kurtarma anahtarı aynı kasayı doğrulayamadı");
            }
            if (expectedDekOrNull != null && !SensitiveBytes.constantTimeEquals(pwDek, expectedDekOrNull)) {
                throw new VaultAuthenticationException("Kimlik doğrulama mevcut kasa anahtarıyla uyuşmuyor");
            }
        } finally {
            SensitiveBytes.wipe(recSecret);
            SensitiveBytes.wipe(recDek);
            SensitiveBytes.wipe(pwDek);
        }
    }

    public static Path renameVault(
            Path vaultPath, String newVaultName, char[] password, Path recoveryKeyPath)
            throws VaultException, IOException {
        Objects.requireNonNull(vaultPath, "vaultPath");
        Path normalized = vaultPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new VaultCorruptedException("Kasa dosyası bulunamadı: " + normalized);
        }

        String validFileName = validateVaultFileName(newVaultName);
        Path targetPath = normalized.resolveSibling(validFileName).toAbsolutePath().normalize();
        if (targetPath.getFileName().toString().equals(normalized.getFileName().toString())) {
            throw new VaultException("Yeni kasa adı mevcut ad ile aynı");
        }
        if (Files.exists(targetPath) && !Files.isSameFile(normalized, targetPath)) {
            throw new VaultException("Hedef konumda bu isimde bir kasa zaten mevcut: " + validFileName);
        }

        FileChannel ch = FileChannel.open(normalized, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            lock = acquireExclusiveLock(ch);
            if (ch.size() < VaultConstants.RECORDS_START_OFFSET) {
                throw new VaultCorruptedException("Kasa dosyası geçersiz boyutta");
            }
            VaultHeader header = readHeader(ch);
            KeySlot activePw = readActiveSlot(ch, KeySlot.TYPE_PASSWORD);
            KeySlot activeRec = readActiveSlot(ch, KeySlot.TYPE_RECOVERY);
            verifyPasswordAndRecoveryKey(header, activePw, activeRec, password, recoveryKeyPath, null);
        } finally {
            releaseQuietly(lock, ch);
        }

        moveWithFallback(normalized, targetPath);
        return targetPath;
    }

    public static void deleteVault(
            Path vaultPath, char[] password, Path recoveryKeyPath, boolean deleteRecoveryKeyFile)
            throws VaultException, IOException {
        Objects.requireNonNull(vaultPath, "vaultPath");
        Path normalized = vaultPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new VaultCorruptedException("Kasa dosyası bulunamadı: " + normalized);
        }

        FileChannel ch = FileChannel.open(normalized, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            lock = acquireExclusiveLock(ch);
            if (ch.size() < VaultConstants.RECORDS_START_OFFSET) {
                throw new VaultCorruptedException("Kasa dosyası geçersiz boyutta");
            }
            VaultHeader header = readHeader(ch);
            KeySlot activePw = readActiveSlot(ch, KeySlot.TYPE_PASSWORD);
            KeySlot activeRec = readActiveSlot(ch, KeySlot.TYPE_RECOVERY);
            verifyPasswordAndRecoveryKey(header, activePw, activeRec, password, recoveryKeyPath, null);
        } finally {
            releaseQuietly(lock, ch);
        }

        Files.delete(normalized);
        if (deleteRecoveryKeyFile && recoveryKeyPath != null) {
            try {
                Files.deleteIfExists(recoveryKeyPath);
            } catch (IOException ignored) {
            }
        }
    }

    public Path renameVault(String newVaultName, char[] password, Path recoveryKeyPath)
            throws VaultException, IOException {
        String validFileName = validateVaultFileName(newVaultName);
        stateLock.writeLock().lock();
        byte[] dek = null;
        try {
            ensureOpen();
            Path current = this.vaultPath;
            Path targetPath = current.resolveSibling(validFileName).toAbsolutePath().normalize();
            if (targetPath.getFileName().toString().equals(current.getFileName().toString())) {
                throw new VaultException("Yeni kasa adı mevcut ad ile aynı");
            }
            if (Files.exists(targetPath) && !Files.isSameFile(current, targetPath)) {
                throw new VaultException("Hedef konumda bu isimde bir kasa zaten mevcut: " + validFileName);
            }

            dek = keys.dekCopy();
            verifyPasswordAndRecoveryKey(header, activePasswordSlot, activeRecoverySlot, password, recoveryKeyPath, dek);

            releaseQuietly(fileLock, channel);
            fileLock = null;
            channel = null;

            boolean moved = false;
            try {
                moveWithFallback(current, targetPath);
                moved = true;
                this.vaultPath = targetPath;
                return targetPath;
            } finally {
                Path pathToReopen = moved ? targetPath : current;
                channel = FileChannel.open(pathToReopen, StandardOpenOption.READ, StandardOpenOption.WRITE);
                fileLock = acquireExclusiveLock(channel);
                nonceLeaseManager.switchChannel(channel);
            }
        } finally {
            SensitiveBytes.wipe(dek);
            stateLock.writeLock().unlock();
        }
    }

    public void deleteVault(char[] password, Path recoveryKeyPath, boolean deleteRecoveryKeyFile)
            throws VaultException, IOException {
        stateLock.writeLock().lock();
        byte[] dek = null;
        try {
            ensureOpen();
            dek = keys.dekCopy();
            verifyPasswordAndRecoveryKey(header, activePasswordSlot, activeRecoverySlot, password, recoveryKeyPath, dek);

            Path targetToDelete = this.vaultPath;
            closed.set(true);
            for (CancellationToken token : activeTokens) {
                token.cancel();
            }
            tempPreviewManager.cleanupAll();
            blockCache.close();
            workerPool.close();
            keys.close();
            releaseQuietly(fileLock, channel);
            fileLock = null;
            channel = null;

            Files.delete(targetToDelete);
            if (deleteRecoveryKeyFile && recoveryKeyPath != null) {
                try {
                    Files.deleteIfExists(recoveryKeyPath);
                } catch (IOException ignored) {
                }
            }
        } finally {
            SensitiveBytes.wipe(dek);
            stateLock.writeLock().unlock();
        }
    }

    private static void moveWithFallback(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void commitMetadataLocked(VaultMetadata working) throws IOException {
        long metaSeq = working.metadataSequence() + 1L;
        working.setMetadataSequence(metaSeq);
        long metaRecSeq = working.allocateRecordSequence();
        long metaNonceCtr = nonceLeaseManager.nextMetadataCounter();

        byte[] metaPlain = working.serialize();
        byte[] metaNonce = AesGcmCipher.buildCounterNonce(header.metadataNoncePrefix(), metaNonceCtr);
        byte[] metaAad = AadBuilder.forMetadataSnapshot(
                header.formatVersion(), header.containerId(), metaSeq, metaNonceCtr, metaPlain.length);
        byte[] metaCipher;
        try {
            metaCipher = AesGcmCipher.encrypt(keys.metadataKey(), metaNonce, metaPlain, metaAad);
        } finally {
            SensitiveBytes.wipe(metaPlain);
        }
        byte[] metaFrame = RecordFrame.encodeMetadataSnapshot(
                metaRecSeq, metaSeq, metaNonceCtr, metaPlain.length, metaCipher);
        byte[] metaHash = SensitiveBytes.sha256(metaFrame);

        long metaOffset = channel.size();
        RecordFrame.writeFully(channel, metaOffset, ByteBuffer.wrap(metaFrame));
        channel.force(true);

        long commitSeq = lastCommitSequence + 1L;
        long commitRecSeq = working.allocateRecordSequence();
        long commitNonceCtr = nonceLeaseManager.nextCommitCounter();
        byte[] commitNonce = AesGcmCipher.buildCounterNonce(header.commitNoncePrefix(), commitNonceCtr);
        byte[] commitAad = AadBuilder.forCommitRecord(header.formatVersion(), header.containerId(), commitSeq, commitNonceCtr, lastCommitOffset, lastCommitHash, metaOffset, metaFrame.length, metaHash);
        byte[] commitPlain = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(commitSeq).array();
        byte[] commitCipher = AesGcmCipher.encrypt(keys.commitKey(), commitNonce, commitPlain, commitAad);
        byte[] commitFrame = RecordFrame.encodeCommit(commitRecSeq, commitSeq, commitNonceCtr, lastCommitOffset, lastCommitHash, metaOffset, metaFrame.length, metaHash, System.currentTimeMillis(), commitCipher);
        byte[] commitHash = SensitiveBytes.sha256(commitFrame);

        long commitOffset = channel.size();
        RecordFrame.writeFully(channel, commitOffset, ByteBuffer.wrap(commitFrame));
        channel.force(true);

        StorageStats updatedStats = working.computeStats(channel.size(), metaFrame.length);
        this.lastCommitOffset = commitOffset;
        this.lastCommitHash = commitHash;
        this.lastCommitSequence = commitSeq;
        this.lastMetadataRecordLength = metaFrame.length;
        this.cachedStorageStats = updatedStats;
        this.metadata = working;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        for (CancellationToken token : activeTokens) {
            token.cancel();
        }
        stateLock.writeLock().lock();
        try {
            tempPreviewManager.cleanupAll();
            blockCache.close();
            workerPool.close();
            keys.close();
            releaseQuietly(fileLock, channel);
            fileLock = null;
            channel = null;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private void ensureOpen() throws VaultLockedException {
        if (closed.get() || channel == null || keys.isClosed()) {
            throw new VaultLockedException("Kasa kilitli veya kapatılmış");
        }
    }

    private static void ensureFolderExists(VaultMetadata working, UUID folderId) throws VaultException {
        VaultEntry entry = working.getEntry(folderId).orElseThrow(() -> new VaultException("Hedef klasör bulunamadı"));
        if (!entry.isFolder()) {
            throw new VaultException("Hedef bir klasör değil: " + entry.name());
        }
    }

    public static String validateEntryName(String rawName) throws VaultException {
        if (rawName == null) {
            throw new VaultException("Ad boş olamaz");
        }
        String trimmed = rawName.trim();
        if (trimmed.isEmpty() || ".".equals(trimmed) || "..".equals(trimmed)) {
            throw new VaultException("Geçersiz dosya/klasör adı: '" + rawName + "'");
        }
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0 || trimmed.indexOf('\0') >= 0) {
            throw new VaultException("Dosya/klasör adı '/', '\\' veya null karakter içeremez");
        }
        return trimmed;
    }

    private static VaultHeader readHeader(FileChannel channel) throws IOException, VaultCorruptedException {
        byte[] hdrBytes = new byte[VaultConstants.HEADER_SIZE];
        RecordFrame.readFully(channel, VaultConstants.HEADER_OFFSET, ByteBuffer.wrap(hdrBytes));
        return VaultHeader.decode(hdrBytes);
    }

    private static KeySlot readActiveSlot(FileChannel channel, byte slotType) throws IOException {
        byte[] bufA = new byte[VaultConstants.KEY_SLOT_SIZE];
        byte[] bufB = new byte[VaultConstants.KEY_SLOT_SIZE];
        RecordFrame.readFully(channel, KeySlot.slotOffset(slotType, KeySlot.SIDE_A), ByteBuffer.wrap(bufA));
        RecordFrame.readFully(channel, KeySlot.slotOffset(slotType, KeySlot.SIDE_B), ByteBuffer.wrap(bufB));
        KeySlot slotA = KeySlot.decodeOrNull(bufA, slotType, KeySlot.SIDE_A);
        KeySlot slotB = KeySlot.decodeOrNull(bufB, slotType, KeySlot.SIDE_B);
        return KeySlot.selectActiveSlot(slotA, slotB);
    }

    private static FileLock acquireExclusiveLock(FileChannel channel) throws IOException, VaultLockedException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new VaultLockedException("Kasa dosyası şu anda başka bir uygulama tarafından kullanılıyor");
            }
            return lock;
        } catch (OverlappingFileLockException e) {
            throw new VaultLockedException("Kasa dosyası zaten bu süreç içinde açık", e);
        }
    }

    private static void releaseQuietly(FileLock lock, FileChannel channel) {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
            }
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static <T> T awaitFuture(Future<T> future) throws VaultException, IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VaultCancelledException("İşlem kesintiye uğradı");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof VaultException ve) throw ve;
            if (cause instanceof IOException ioe) throw ioe;

            throw new VaultException("Arka plan iş parçacığı hatası: " + cause.getMessage(), cause);
        }
    }

    private static void cancelPendingFutures(Deque<PendingImportChunk> window) {
        while (!window.isEmpty()) {
            PendingImportChunk item = window.removeFirst();
            if (item instanceof PendingEncryptedChunk enc) {
                enc.future().cancel(true);
            }
        }
    }

    private static void cancelPlainFutures(Deque<Future<byte[]>> window) {
        while (!window.isEmpty()) {
            window.removeFirst().cancel(true);
        }
    }
}
