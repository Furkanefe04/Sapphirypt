package com.sapphircrypt.sapphirypt.format;

import com.sapphircrypt.sapphirypt.crypto.VaultKeys;
import com.sapphircrypt.sapphirypt.storage.VaultCorruptedException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Thread-safe persistent counter-lease manager using A/B nonce state slots.
 * Leases counter ranges to disk with {@code FileChannel.force(true)} before any counter in the
 * range is handed out, guaranteeing that AES-GCM nonces are never reused even after a crash.
 */
public final class NonceLeaseManager {
    private final short formatVersion;
    private final byte[] containerId;
    private final VaultKeys keys;
    private FileChannel channel;

    private byte activeSide;
    private long generation;
    private long dataNext;
    private long dataLimit;
    private long metadataNext;
    private long metadataLimit;
    private long commitNext;
    private long commitLimit;

    private NonceLeaseManager(short formatVersion, byte[] containerId, VaultKeys keys, FileChannel channel, byte activeSide, long generation,
                              long dataNext, long dataLimit, long metadataNext, long metadataLimit, long commitNext, long commitLimit) {
        this.formatVersion = formatVersion;
        this.containerId = containerId;
        this.keys = keys;
        this.channel = channel;
        this.activeSide = activeSide;
        this.generation = generation;
        this.dataNext = dataNext;
        this.dataLimit = dataLimit;
        this.metadataNext = metadataNext;
        this.metadataLimit = metadataLimit;
        this.commitNext = commitNext;
        this.commitLimit = commitLimit;
    }

    public static NonceLeaseManager initializeNew(FileChannel channel, short formatVersion, byte[] containerId, VaultKeys keys) throws IOException {
        long dataStart = 1L;
        long metaStart = 1L;
        long commitStart = 1L;
        long dataLimit = dataStart + VaultConstants.DATA_NONCE_LEASE;
        long metaLimit = metaStart + VaultConstants.METADATA_NONCE_LEASE;
        long commitLimit = commitStart + VaultConstants.COMMIT_NONCE_LEASE;

        NonceState initial = new NonceState(KeySlot.SIDE_A, 1L, dataLimit, metaLimit, commitLimit);
        byte[] encoded = initial.encode(formatVersion, containerId, keys.nonceStateKey());
        RecordFrame.writeFully(channel, NonceState.slotOffset(KeySlot.SIDE_A), ByteBuffer.wrap(encoded));
        RecordFrame.writeFully(channel, NonceState.slotOffset(KeySlot.SIDE_B), ByteBuffer.wrap(new byte[VaultConstants.NONCE_STATE_SIZE]));
        channel.force(true);

        return new NonceLeaseManager(formatVersion, containerId, keys, channel, KeySlot.SIDE_A, 1L, dataStart, dataLimit, metaStart, metaLimit, commitStart, commitLimit);
    }

    public static NonceLeaseManager loadFromChannel(FileChannel channel, short formatVersion, byte[] containerId, VaultKeys keys) throws IOException, VaultCorruptedException {
        byte[] bufA = new byte[VaultConstants.NONCE_STATE_SIZE];
        byte[] bufB = new byte[VaultConstants.NONCE_STATE_SIZE];
        RecordFrame.readFully(channel, VaultConstants.NONCE_STATE_A_OFFSET, ByteBuffer.wrap(bufA));
        RecordFrame.readFully(channel, VaultConstants.NONCE_STATE_B_OFFSET, ByteBuffer.wrap(bufB));

        NonceState stateA = NonceState.decodeOrNull(bufA, KeySlot.SIDE_A, formatVersion, containerId, keys.nonceStateKey());
        NonceState stateB = NonceState.decodeOrNull(bufB, KeySlot.SIDE_B, formatVersion, containerId, keys.nonceStateKey());

        NonceState active;
        if (stateA == null && stateB == null) {
            throw new VaultCorruptedException("Nonce sayaç durumu doğrulanamadı (A ve B slotları geçersiz)");
        } else if (stateA == null) {
            active = stateB;
        } else if (stateB == null) {
            active = stateA;
        } else {
            active = stateB.generation() > stateA.generation() ? stateB : stateA;
        }

        // Start cleanly at the persisted upper bound so any unused counters from an earlier crash are skipped.
        return new NonceLeaseManager(formatVersion, containerId, keys, channel, active.slotSide(), active.generation(), active.dataNextAvailable(),
                active.dataNextAvailable(), active.metadataNextAvailable(), active.metadataNextAvailable(), active.commitNextAvailable(), active.commitNextAvailable());
    }

    public synchronized void ensureAtLeast(long minData, long minMetadata, long minCommit) throws IOException {
        boolean updated = false;
        if (dataLimit < minData) {
            dataNext = minData;
            dataLimit = minData + VaultConstants.DATA_NONCE_LEASE;
            updated = true;
        }
        if (metadataLimit < minMetadata) {
            metadataNext = minMetadata;
            metadataLimit = minMetadata + VaultConstants.METADATA_NONCE_LEASE;
            updated = true;
        }
        if (commitLimit < minCommit) {
            commitNext = minCommit;
            commitLimit = minCommit + VaultConstants.COMMIT_NONCE_LEASE;
            updated = true;
        }
        if (updated) {
            persistNextGenerationLocked();
        }
    }

    public synchronized long nextDataCounter() throws IOException {
        if (dataNext >= dataLimit) {
            dataLimit = safeAdd(dataNext, VaultConstants.DATA_NONCE_LEASE);
            persistNextGenerationLocked();
        }
        return dataNext++;
    }

    public synchronized long nextMetadataCounter() throws IOException {
        if (metadataNext >= metadataLimit) {
            metadataLimit = safeAdd(metadataNext, VaultConstants.METADATA_NONCE_LEASE);
            persistNextGenerationLocked();
        }
        return metadataNext++;
    }

    public synchronized long nextCommitCounter() throws IOException {
        if (commitNext >= commitLimit) {
            commitLimit = safeAdd(commitNext, VaultConstants.COMMIT_NONCE_LEASE);
            persistNextGenerationLocked();
        }
        return commitNext++;
    }

    /**
     * Persists the latest durable upper bounds into a newly compacted container file so counters
     * never regress across compaction.
     */
    public synchronized void writeStateToTarget(FileChannel targetChannel) throws IOException {
        long targetGen = generation + 1L;
        long dLim = Math.max(dataNext, dataLimit);
        long mLim = Math.max(metadataNext, metadataLimit);
        long cLim = Math.max(commitNext, commitLimit);
        NonceState nextState = new NonceState(KeySlot.SIDE_A, targetGen, dLim, mLim, cLim);
        byte[] encoded = nextState.encode(formatVersion, containerId, keys.nonceStateKey());
        RecordFrame.writeFully(targetChannel, NonceState.slotOffset(KeySlot.SIDE_A), ByteBuffer.wrap(encoded));
        RecordFrame.writeFully(targetChannel, NonceState.slotOffset(KeySlot.SIDE_B), ByteBuffer.wrap(new byte[VaultConstants.NONCE_STATE_SIZE]));
        generation = targetGen;
        activeSide = KeySlot.SIDE_A;
        dataNext = dLim;
        dataLimit = dLim;
        metadataNext = mLim;
        metadataLimit = mLim;
        commitNext = cLim;
        commitLimit = cLim;
    }

    public synchronized void switchChannel(FileChannel newChannel) {
        this.channel = newChannel;
    }

    private void persistNextGenerationLocked() throws IOException {
        byte targetSide = KeySlot.oppositeSide(activeSide);
        long nextGen = generation + 1L;
        NonceState state = new NonceState(targetSide, nextGen, dataLimit, metadataLimit, commitLimit);
        byte[] encoded = state.encode(formatVersion, containerId, keys.nonceStateKey());
        RecordFrame.writeFully(channel, NonceState.slotOffset(targetSide), ByteBuffer.wrap(encoded));
        channel.force(true);
        activeSide = targetSide;
        generation = nextGen;
    }

    private static long safeAdd(long current, long delta) {
        if (Long.MAX_VALUE - current < delta) throw new IllegalStateException("Nonce sayacı taşma sınırına ulaştı");
        return current + delta;
    }
}
