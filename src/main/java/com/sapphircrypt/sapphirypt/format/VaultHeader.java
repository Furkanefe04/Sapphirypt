package com.sapphircrypt.sapphirypt.format;

import com.sapphircrypt.sapphirypt.crypto.SensitiveBytes;
import com.sapphircrypt.sapphirypt.storage.VaultCorruptedException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Fixed 256-byte container header at offset 0 of a {@code .sapphirypt} vault file.
 */
public record VaultHeader(short formatVersion, short headerFlags, int headerAreaSize, byte[] containerId, int maxBlockSize, int dataNoncePrefix, int metadataNoncePrefix, int commitNoncePrefix, long createdAtEpochMillis) {

    public VaultHeader {
        if (containerId == null || containerId.length != 16) {
            throw new IllegalArgumentException("Container ID must be 16 bytes");
        }
        containerId = Arrays.copyOf(containerId, 16);
    }

    @Override
    public byte[] containerId() {
        return Arrays.copyOf(containerId, 16);
    }

    public static VaultHeader createNew() {
        return new VaultHeader(VaultConstants.FORMAT_VERSION, (short) 0, (int) VaultConstants.RECORDS_START_OFFSET, SensitiveBytes.randomBytes(16), VaultConstants.MAX_BLOCK_PLAINTEXT_SIZE, SensitiveBytes.randomInt(), SensitiveBytes.randomInt(), SensitiveBytes.randomInt(), System.currentTimeMillis());
    }

    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(VaultConstants.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.put(VaultConstants.HEADER_MAGIC); // 0..7
        buf.putShort(formatVersion);          // 8..9
        buf.putShort(headerFlags);            // 10..11
        buf.putInt(headerAreaSize);           // 12..15
        buf.put(containerId);                 // 16..31
        buf.putInt(maxBlockSize);             // 32..35
        buf.putInt(dataNoncePrefix);          // 36..39
        buf.putInt(metadataNoncePrefix);      // 40..43
        buf.putInt(commitNoncePrefix);        // 44..47
        buf.putLong(createdAtEpochMillis);    // 48..55
        byte[] body = new byte[224];
        buf.position(0);
        buf.get(body);
        byte[] checksum = SensitiveBytes.sha256(body);
        buf.position(224);
        buf.put(checksum);                    // 224..255
        return buf.array();
    }

    public static VaultHeader decode(byte[] bytes) throws VaultCorruptedException {
        if (bytes == null || bytes.length != VaultConstants.HEADER_SIZE) {
            throw new VaultCorruptedException("Geçersiz kasa başlık boyutu");
        }
        byte[] magic = Arrays.copyOfRange(bytes, 0, 8);
        if (!Arrays.equals(magic, VaultConstants.HEADER_MAGIC)) {
            throw new VaultCorruptedException("Bu dosya geçerli bir Sapphirypt kasası değil");
        }
        byte[] body = Arrays.copyOfRange(bytes, 0, 224);
        byte[] expectedChecksum = SensitiveBytes.sha256(body);
        byte[] actualChecksum = Arrays.copyOfRange(bytes, 224, 256);
        if (!SensitiveBytes.constantTimeEquals(expectedChecksum, actualChecksum)) {
            throw new VaultCorruptedException("Kasa başlığı bütünlük kontrolünden geçemedi");
        }

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buf.position(8);
        short version = buf.getShort();
        if (version != VaultConstants.FORMAT_VERSION) {
            throw new VaultCorruptedException("Desteklenmeyen kasa format sürümü: " + version + " (desteklenen: " + VaultConstants.FORMAT_VERSION + ")");
        }
        short flags = buf.getShort();
        int areaSize = buf.getInt();
        if (areaSize != VaultConstants.RECORDS_START_OFFSET) {
            throw new VaultCorruptedException("Geçersiz kasa başlık alanı boyutu: " + areaSize);
        }
        byte[] cid = new byte[16];
        buf.get(cid);
        int maxBlock = buf.getInt();
        if (maxBlock <= 0 || maxBlock > VaultConstants.MAX_BLOCK_PLAINTEXT_SIZE) {
            throw new VaultCorruptedException("Geçersiz maksimum blok boyutu: " + maxBlock);
        }
        int dataPrefix = buf.getInt();
        int metaPrefix = buf.getInt();
        int commitPrefix = buf.getInt();
        long createdAt = buf.getLong();

        return new VaultHeader(version, flags, areaSize, cid, maxBlock, dataPrefix, metaPrefix, commitPrefix, createdAt);
    }
}
