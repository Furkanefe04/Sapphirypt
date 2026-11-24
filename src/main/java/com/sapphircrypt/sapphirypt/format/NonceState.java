package com.sapphircrypt.sapphirypt.format;

import com.sapphircrypt.sapphirypt.crypto.AadBuilder;
import com.sapphircrypt.sapphirypt.crypto.AesGcmCipher;
import com.sapphircrypt.sapphirypt.crypto.SensitiveBytes;

import javax.crypto.AEADBadTagException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * 128-byte encrypted persistent nonce-lease slot (A/B dual slot at offsets 768 and 896).
 */
public record NonceState(byte slotSide, long generation, long dataNextAvailable, long metadataNextAvailable, long commitNextAvailable) {

    private static final int PLAINTEXT_LEN = 24;
    private static final int CIPHERTEXT_LEN = PLAINTEXT_LEN + AesGcmCipher.TAG_BYTES; // 40B

    public byte[] encode(short formatVersion, byte[] containerId, byte[] nonceStateKey) {
        byte[] slotNonce = SensitiveBytes.randomBytes(AesGcmCipher.NONCE_BYTES);
        ByteBuffer pt = ByteBuffer.allocate(PLAINTEXT_LEN).order(ByteOrder.BIG_ENDIAN);
        pt.putLong(dataNextAvailable);
        pt.putLong(metadataNextAvailable);
        pt.putLong(commitNextAvailable);
        byte[] plaintext = pt.array();
        byte[] aad = AadBuilder.forNonceStateSlot(formatVersion, containerId, slotSide, generation);
        byte[] ciphertext = AesGcmCipher.encrypt(nonceStateKey, slotNonce, plaintext, aad);
        SensitiveBytes.wipe(plaintext);

        ByteBuffer out = ByteBuffer.allocate(VaultConstants.NONCE_STATE_SIZE).order(ByteOrder.BIG_ENDIAN);
        out.put(VaultConstants.NONCE_STATE_MAGIC); // 0..3
        out.put(slotSide);                         // 4
        out.put((byte) 0);                         // 5
        out.putShort((short) 0);                   // 6..7
        out.putLong(generation);                   // 8..15
        out.put(slotNonce);                        // 16..27
        out.put(ciphertext);                       // 28..67
        byte[] body = new byte[68];
        out.position(0);
        out.get(body);
        byte[] checksum = SensitiveBytes.sha256(body);
        out.position(68);
        out.put(checksum);                         // 68..99
        return out.array();
    }

    public static NonceState decodeOrNull(byte[] bytes, byte expectedSide, short formatVersion, byte[] containerId, byte[] nonceStateKey) {
        if (bytes == null || bytes.length != VaultConstants.NONCE_STATE_SIZE) return null;

        byte[] magic = Arrays.copyOfRange(bytes, 0, 4);
        if (!Arrays.equals(magic, VaultConstants.NONCE_STATE_MAGIC)) return null;

        byte[] body = Arrays.copyOfRange(bytes, 0, 68);
        byte[] expectedChecksum = SensitiveBytes.sha256(body);
        byte[] actualChecksum = Arrays.copyOfRange(bytes, 68, 100);
        if (!SensitiveBytes.constantTimeEquals(expectedChecksum, actualChecksum)) return null;

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buf.position(4);
        byte side = buf.get();
        buf.position(8);
        long generation = buf.getLong();
        if (side != expectedSide || generation < 1) return null;

        byte[] slotNonce = new byte[AesGcmCipher.NONCE_BYTES];
        buf.get(slotNonce);
        byte[] ciphertext = new byte[CIPHERTEXT_LEN];
        buf.get(ciphertext);
        byte[] aad = AadBuilder.forNonceStateSlot(formatVersion, containerId, side, generation);
        byte[] plaintext = null;
        try {
            plaintext = AesGcmCipher.decrypt(nonceStateKey, slotNonce, ciphertext, aad);
            ByteBuffer pt = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN);
            long dataNext = pt.getLong();
            long metaNext = pt.getLong();
            long commitNext = pt.getLong();
            if (dataNext < 1 || metaNext < 1 || commitNext < 1) {
                return null;
            }
            return new NonceState(side, generation, dataNext, metaNext, commitNext);
        } catch (AEADBadTagException e) {
            return null;
        } finally {
            SensitiveBytes.wipe(plaintext);
        }
    }

    public static long slotOffset(byte side) {
        return side == KeySlot.SIDE_A ? VaultConstants.NONCE_STATE_A_OFFSET : VaultConstants.NONCE_STATE_B_OFFSET;
    }
}
