package com.sapphircrypt.sapphirypt.format;

import com.sapphircrypt.sapphirypt.crypto.AesGcmCipher;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Framed append-only container records (32B header + payload + 16B footer) and I/O helpers.
 */
public final class RecordFrame {
    public static final int DATA_PAYLOAD_HEADER_BYTES = 32;
    public static final int METADATA_PAYLOAD_HEADER_BYTES = 20;
    public static final int COMMIT_ENCRYPTED_BODY_BYTES = 8 + AesGcmCipher.TAG_BYTES; // 24B
    public static final int COMMIT_PAYLOAD_BYTES = 132;
    public static final int COMMIT_TOTAL_RECORD_BYTES = VaultConstants.RECORD_FRAME_OVERHEAD + COMMIT_PAYLOAD_BYTES; // 180B

    private static final int MAX_DATA_PAYLOAD = VaultConstants.MAX_BLOCK_PLAINTEXT_SIZE + DATA_PAYLOAD_HEADER_BYTES + AesGcmCipher.TAG_BYTES;
    private static final int MAX_METADATA_PAYLOAD = VaultConstants.MAX_METADATA_PLAINTEXT_SIZE + METADATA_PAYLOAD_HEADER_BYTES + AesGcmCipher.TAG_BYTES;

    private RecordFrame() {
    }

    public record RecordHeader(byte recordType, byte recordFlags, short formatVersion, long recordSequence, int payloadLength) {

        public int totalRecordLength() {
            return VaultConstants.RECORD_FRAME_OVERHEAD + payloadLength;
        }
    }

    public record DataBlockRecord(long recordSequence, UUID blockId, int blockIndex, int plaintextLength, long nonceCounter, byte[] ciphertextAndTag) { }

    public record MetadataSnapshotRecord(long recordSequence, long snapshotSequence, long nonceCounter, int plaintextLength, byte[] ciphertextAndTag) { }

    public record CommitRecord(long recordOffset, long recordSequence, long commitSequence, long nonceCounter, long prevCommitOffset, byte[] prevCommitHash, long metadataOffset, int metadataLength, byte[] metadataHash, long commitTimestampEpochMillis, byte[] encryptedBodyAndTag, byte[] rawRecordBytes) { }

    public static byte[] encodeDataBlock(long recordSequence, UUID blockId, int blockIndex, int plaintextLength, long nonceCounter, byte[] ciphertextAndTag, int ctOffset, int ctLength) {
        int payloadLength = DATA_PAYLOAD_HEADER_BYTES + ctLength;
        ByteBuffer payload = ByteBuffer.allocate(payloadLength).order(ByteOrder.BIG_ENDIAN);
        payload.putLong(blockId.getMostSignificantBits());
        payload.putLong(blockId.getLeastSignificantBits());
        payload.putInt(blockIndex);
        payload.putInt(plaintextLength);
        payload.putLong(nonceCounter);
        payload.put(ciphertextAndTag, ctOffset, ctLength);
        return frameRecord(VaultConstants.RECORD_TYPE_DATA_BLOCK, recordSequence, payload.array());
    }

    public static byte[] encodeMetadataSnapshot(long recordSequence, long snapshotSequence, long nonceCounter, int plaintextLength, byte[] ciphertextAndTag) {
        int payloadLength = METADATA_PAYLOAD_HEADER_BYTES + ciphertextAndTag.length;
        ByteBuffer payload = ByteBuffer.allocate(payloadLength).order(ByteOrder.BIG_ENDIAN);
        payload.putLong(snapshotSequence);
        payload.putLong(nonceCounter);
        payload.putInt(plaintextLength);
        payload.put(ciphertextAndTag);
        return frameRecord(VaultConstants.RECORD_TYPE_METADATA_SNAPSHOT, recordSequence, payload.array());
    }

    public static byte[] encodeCommit(long recordSequence, long commitSequence, long nonceCounter, long prevCommitOffset, byte[] prevCommitHash, long metadataOffset, int metadataLength, byte[] metadataHash, long commitTimestampEpochMillis, byte[] encryptedBodyAndTag) {
        ByteBuffer payload = ByteBuffer.allocate(COMMIT_PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN);
        payload.putLong(commitSequence);
        payload.putLong(nonceCounter);
        payload.putLong(prevCommitOffset);
        payload.put(prevCommitHash);
        payload.putLong(metadataOffset);
        payload.putInt(metadataLength);
        payload.put(metadataHash);
        payload.putLong(commitTimestampEpochMillis);
        payload.put(encryptedBodyAndTag);
        return frameRecord(VaultConstants.RECORD_TYPE_COMMIT, recordSequence, payload.array());
    }

    private static byte[] frameRecord(byte recordType, long recordSequence, byte[] payload) {
        int totalLen = VaultConstants.RECORD_FRAME_OVERHEAD + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN);
        buf.put(VaultConstants.RECORD_HEADER_MAGIC); // 0..3
        buf.put(recordType);                         // 4
        buf.put((byte) 0);                           // 5
        buf.putShort(VaultConstants.FORMAT_VERSION); // 6..7
        buf.putLong(recordSequence);                 // 8..15
        buf.putInt(payload.length);                  // 16..19
        buf.putLong(0L);                             // 20..27
        int headerCrc = crc32(buf.array(), 0, 28);
        buf.putInt(headerCrc);                       // 28..31

        buf.put(payload);

        buf.put(VaultConstants.RECORD_FOOTER_MAGIC);
        buf.put(recordType);
        buf.put((byte) 0);
        buf.putShort((short) 0);
        buf.putInt(payload.length);
        buf.putInt(crc32(payload, 0, payload.length));
        return buf.array();
    }

    public static RecordHeader readHeaderOrNull(FileChannel channel, long offset, long fileSize) throws IOException {
        if (offset < VaultConstants.RECORDS_START_OFFSET || offset + VaultConstants.RECORD_HEADER_SIZE > fileSize) {
            return null;
        }
        byte[] hdr = new byte[VaultConstants.RECORD_HEADER_SIZE];
        readFully(channel, offset, ByteBuffer.wrap(hdr));
        return parseHeaderOrNull(hdr, offset, fileSize);
    }

    public static RecordHeader parseHeaderOrNull(byte[] hdr, long offset, long fileSize) {
        if (hdr == null || hdr.length < VaultConstants.RECORD_HEADER_SIZE) return null;

        byte[] magic = Arrays.copyOfRange(hdr, 0, 4);
        if (!Arrays.equals(magic, VaultConstants.RECORD_HEADER_MAGIC)) return null;

        ByteBuffer buf = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN);
        buf.position(4);
        byte type = buf.get();
        byte flags = buf.get();
        short version = buf.getShort();
        long recordSeq = buf.getLong();
        int payloadLen = buf.getInt();
        buf.position(28);
        int expectedCrc = buf.getInt();
        if (crc32(hdr, 0, 28) != expectedCrc) return null;

        if (version != VaultConstants.FORMAT_VERSION) return null;

        if (!isPayloadLengthValid(type, payloadLen)) return null;

        long totalEnd = offset + (long) VaultConstants.RECORD_FRAME_OVERHEAD + (long) payloadLen;
        if (totalEnd > fileSize || totalEnd < offset) return null;

        return new RecordHeader(type, flags, version, recordSeq, payloadLen);
    }

    public static byte[] readVerifiedRecordBytesOrNull(FileChannel channel, long offset, long fileSize) throws IOException {
        RecordHeader header = readHeaderOrNull(channel, offset, fileSize);
        if (header == null) return null;

        int totalLen = header.totalRecordLength();
        byte[] full = new byte[totalLen];
        readFully(channel, offset, ByteBuffer.wrap(full));
        if (!verifyFooter(full, header)) return null;

        return full;
    }

    public static boolean verifyFooter(byte[] fullRecord, RecordHeader header) {
        int payloadLen = header.payloadLength();
        if (fullRecord == null || fullRecord.length != VaultConstants.RECORD_FRAME_OVERHEAD + payloadLen) return false;

        int footerOffset = VaultConstants.RECORD_HEADER_SIZE + payloadLen;
        byte[] magic = Arrays.copyOfRange(fullRecord, footerOffset, footerOffset + 4);
        if (!Arrays.equals(magic, VaultConstants.RECORD_FOOTER_MAGIC)) return false;

        ByteBuffer footer = ByteBuffer.wrap(fullRecord).order(ByteOrder.BIG_ENDIAN);
        footer.position(footerOffset + 4);
        byte footerType = footer.get();
        footer.position(footerOffset + 8);
        int footerLen = footer.getInt();
        int expectedPayloadCrc = footer.getInt();
        if (footerType != header.recordType() || footerLen != payloadLen) return false;

        int actualPayloadCrc = crc32(fullRecord, VaultConstants.RECORD_HEADER_SIZE, payloadLen);
        return actualPayloadCrc == expectedPayloadCrc;
    }

    public static DataBlockRecord parseDataBlockRecord(byte[] fullRecord, RecordHeader header) {
        if (header.recordType() != VaultConstants.RECORD_TYPE_DATA_BLOCK || !verifyFooter(fullRecord, header)) return null;

        if (header.payloadLength() < DATA_PAYLOAD_HEADER_BYTES + AesGcmCipher.TAG_BYTES) return null;

        ByteBuffer buf = ByteBuffer.wrap(fullRecord, VaultConstants.RECORD_HEADER_SIZE, header.payloadLength()).order(ByteOrder.BIG_ENDIAN);
        long msb = buf.getLong();
        long lsb = buf.getLong();
        int blockIndex = buf.getInt();
        int plaintextLen = buf.getInt();
        long nonceCounter = buf.getLong();
        int ctLen = header.payloadLength() - DATA_PAYLOAD_HEADER_BYTES;
        if (plaintextLen < 0 || plaintextLen > VaultConstants.MAX_BLOCK_PLAINTEXT_SIZE || ctLen != plaintextLen + AesGcmCipher.TAG_BYTES) return null;

        byte[] ct = new byte[ctLen];
        buf.get(ct);
        return new DataBlockRecord(header.recordSequence(), new UUID(msb, lsb), blockIndex, plaintextLen, nonceCounter, ct);
    }

    public static MetadataSnapshotRecord parseMetadataSnapshotRecord(byte[] fullRecord, RecordHeader header) {
        if (header.recordType() != VaultConstants.RECORD_TYPE_METADATA_SNAPSHOT || !verifyFooter(fullRecord, header)) return null;

        if (header.payloadLength() < METADATA_PAYLOAD_HEADER_BYTES + AesGcmCipher.TAG_BYTES) return null;

        ByteBuffer buf = ByteBuffer.wrap(fullRecord, VaultConstants.RECORD_HEADER_SIZE, header.payloadLength()).order(ByteOrder.BIG_ENDIAN);
        long snapshotSeq = buf.getLong();
        long nonceCounter = buf.getLong();
        int plaintextLen = buf.getInt();
        int ctLen = header.payloadLength() - METADATA_PAYLOAD_HEADER_BYTES;
        if (plaintextLen <= 0 || plaintextLen > VaultConstants.MAX_METADATA_PLAINTEXT_SIZE || ctLen != plaintextLen + AesGcmCipher.TAG_BYTES) return null;

        byte[] ct = new byte[ctLen];
        buf.get(ct);
        return new MetadataSnapshotRecord(header.recordSequence(), snapshotSeq, nonceCounter, plaintextLen, ct);
    }

    public static CommitRecord parseCommitRecord(long offset, byte[] fullRecord, RecordHeader header) {
        if (header.recordType() != VaultConstants.RECORD_TYPE_COMMIT || !verifyFooter(fullRecord, header)) return null;

        if (header.payloadLength() != COMMIT_PAYLOAD_BYTES) return null;

        ByteBuffer buf = ByteBuffer.wrap(fullRecord, VaultConstants.RECORD_HEADER_SIZE, COMMIT_PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN);
        long commitSeq = buf.getLong();
        long nonceCounter = buf.getLong();
        long prevCommitOffset = buf.getLong();
        byte[] prevCommitHash = new byte[32];
        buf.get(prevCommitHash);
        long metadataOffset = buf.getLong();
        int metadataLength = buf.getInt();
        byte[] metadataHash = new byte[32];
        buf.get(metadataHash);
        long commitTs = buf.getLong();
        byte[] encBody = new byte[COMMIT_ENCRYPTED_BODY_BYTES];
        buf.get(encBody);
        return new CommitRecord(offset, header.recordSequence(), commitSeq, nonceCounter, prevCommitOffset, prevCommitHash,
                metadataOffset, metadataLength, metadataHash, commitTs, encBody, fullRecord);
    }

    private static boolean isPayloadLengthValid(byte type, int payloadLen) {
        if (payloadLen <= 0) return false;

        return switch (type) {
            case VaultConstants.RECORD_TYPE_DATA_BLOCK ->
                    payloadLen >= DATA_PAYLOAD_HEADER_BYTES + AesGcmCipher.TAG_BYTES && payloadLen <= MAX_DATA_PAYLOAD;
            case VaultConstants.RECORD_TYPE_METADATA_SNAPSHOT ->
                    payloadLen >= METADATA_PAYLOAD_HEADER_BYTES + AesGcmCipher.TAG_BYTES && payloadLen <= MAX_METADATA_PAYLOAD;
            case VaultConstants.RECORD_TYPE_COMMIT -> payloadLen == COMMIT_PAYLOAD_BYTES;
            default -> false;
        };
    }

    private static int crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }

    public static void readFully(FileChannel channel, long position, ByteBuffer dst) throws IOException {
        long pos = position;
        while (dst.hasRemaining()) {
            int read = channel.read(dst, pos);
            if (read < 0) {
                throw new EOFException("Beklenmeyen dosya sonu (offset " + pos + ")");
            }
            pos += read;
        }
    }

    public static void writeFully(FileChannel channel, long position, ByteBuffer src) throws IOException {
        long pos = position;
        while (src.hasRemaining()) {
            int written = channel.write(src, pos);
            pos += written;
        }
    }
}
