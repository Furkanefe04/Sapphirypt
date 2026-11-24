package com.sapphircrypt.sapphirypt.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Builds canonical, unambiguous big-endian AAD buffers for every AES-256-GCM context.
 * All variable context fields have fixed positions and lengths so fields can never collide.
 */
public final class AadBuilder {
    private static final byte[] DOMAIN_PREFIX = "SAPP_AAD".getBytes(StandardCharsets.US_ASCII); // 8 bytes
    private static final int HEADER_BASE_LEN = 8 + 2 + 16 + 1; // 27 bytes

    public static final byte PURPOSE_PASSWORD_SLOT = 0x01;
    public static final byte PURPOSE_RECOVERY_SLOT = 0x02;
    public static final byte PURPOSE_NONCE_STATE = 0x03;
    public static final byte PURPOSE_DATA_BLOCK = 0x04;
    public static final byte PURPOSE_METADATA_SNAPSHOT = 0x05;
    public static final byte PURPOSE_COMMIT_RECORD = 0x06;

    private AadBuilder() {
    }

    private static ByteBuffer allocateBase(short formatVersion, byte[] containerId, byte purpose, int extraBytes) {
        if (containerId == null || containerId.length != 16) {
            throw new IllegalArgumentException("Container ID must be 16 bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_BASE_LEN + extraBytes).order(ByteOrder.BIG_ENDIAN);
        buf.put(DOMAIN_PREFIX);
        buf.putShort(formatVersion);
        buf.put(containerId);
        buf.put(purpose);
        return buf;
    }

    public static byte[] forPasswordSlot(short formatVersion, byte[] containerId, byte slotSide, long slotGeneration, byte kdfType, Argon2Params params, byte[] salt) {
        if (salt == null || salt.length != Argon2Kdf.SALT_BYTES) throw new IllegalArgumentException("Salt must be 16 bytes");

        int extra = 1 + 8 + 1 + 16 + Argon2Kdf.SALT_BYTES;
        ByteBuffer buf = allocateBase(formatVersion, containerId, PURPOSE_PASSWORD_SLOT, extra);
        buf.put(slotSide);
        buf.putLong(slotGeneration);
        buf.put(kdfType);
        buf.putInt(params.version());
        buf.putInt(params.memoryKiB());
        buf.putInt(params.iterations());
        buf.putInt(params.parallelism());
        buf.put(salt);
        return buf.array();
    }

    public static byte[] forRecoverySlot(short formatVersion, byte[] containerId, byte slotSide, long slotGeneration, byte[] salt) {
        if (salt == null || salt.length != Argon2Kdf.SALT_BYTES) throw new IllegalArgumentException("Salt must be 16 bytes");

        int extra = 1 + 8 + Argon2Kdf.SALT_BYTES;
        ByteBuffer buf = allocateBase(formatVersion, containerId, PURPOSE_RECOVERY_SLOT, extra);
        buf.put(slotSide);
        buf.putLong(slotGeneration);
        buf.put(salt);
        return buf.array();
    }

    public static byte[] forNonceStateSlot(short formatVersion, byte[] containerId, byte slotSide, long generation) {
        ByteBuffer buf = allocateBase(formatVersion, containerId, PURPOSE_NONCE_STATE, 1 + 8);
        buf.put(slotSide);
        buf.putLong(generation);
        return buf.array();
    }

    public static byte[] forDataBlock(short formatVersion, byte[] containerId, UUID blockId, int blockIndex, long nonceCounter, int plaintextLength, long recordSequence) {
        if (blockId == null) throw new IllegalArgumentException("Block ID must not be null");

        int extra = 16 + 4 + 8 + 4 + 8;
        ByteBuffer buf = allocateBase(formatVersion, containerId, PURPOSE_DATA_BLOCK, extra);
        buf.putLong(blockId.getMostSignificantBits());
        buf.putLong(blockId.getLeastSignificantBits());
        buf.putInt(blockIndex);
        buf.putLong(nonceCounter);
        buf.putInt(plaintextLength);
        buf.putLong(recordSequence);
        return buf.array();
    }

    public static byte[] forMetadataSnapshot(short formatVersion, byte[] containerId, long snapshotSequence, long nonceCounter, int plaintextLength) {
        ByteBuffer buf = allocateBase(formatVersion, containerId, PURPOSE_METADATA_SNAPSHOT, 8 + 8 + 4);
        buf.putLong(snapshotSequence);
        buf.putLong(nonceCounter);
        buf.putInt(plaintextLength);
        return buf.array();
    }

    public static byte[] forCommitRecord(short formatVersion, byte[] containerId, long commitSequence, long nonceCounter, long prevCommitOffset, byte[] prevCommitHash, long metadataOffset, int metadataLength, byte[] metadataHash) {
        if (prevCommitHash == null || prevCommitHash.length != 32) throw new IllegalArgumentException("prevCommitHash must be 32 bytes");

        if (metadataHash == null || metadataHash.length != 32) throw new IllegalArgumentException("metadataHash must be 32 bytes");

        int extra = 8 + 8 + 8 + 32 + 8 + 4 + 32;
        ByteBuffer buf = allocateBase(formatVersion, containerId, PURPOSE_COMMIT_RECORD, extra);
        buf.putLong(commitSequence);
        buf.putLong(nonceCounter);
        buf.putLong(prevCommitOffset);
        buf.put(prevCommitHash);
        buf.putLong(metadataOffset);
        buf.putInt(metadataLength);
        buf.put(metadataHash);
        return buf.array();
    }
}
