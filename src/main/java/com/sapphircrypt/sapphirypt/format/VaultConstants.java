package com.sapphircrypt.sapphirypt.format;

import java.nio.charset.StandardCharsets;

/**
 * Binary format constants, fixed offsets, and safety bounds for {@code .sapphirypt} containers.
 */
public final class VaultConstants {
    public static final String FILE_EXTENSION = ".sapphirypt";
    public static final String RECOVERY_KEY_EXTENSION = ".key";

    public static final byte[] HEADER_MAGIC = "SAPPHIRY".getBytes(StandardCharsets.US_ASCII); // 8B
    public static final byte[] SLOT_MAGIC = "SKSL".getBytes(StandardCharsets.US_ASCII);       // 4B
    public static final byte[] NONCE_STATE_MAGIC = "SNST".getBytes(StandardCharsets.US_ASCII); // 4B
    public static final byte[] RECORD_HEADER_MAGIC = "SREC".getBytes(StandardCharsets.US_ASCII); // 4B
    public static final byte[] RECORD_FOOTER_MAGIC = "SREF".getBytes(StandardCharsets.US_ASCII); // 4B

    public static final short FORMAT_VERSION = 1;

    public static final long HEADER_OFFSET = 0L;
    public static final int HEADER_SIZE = 256;

    public static final int KEY_SLOT_SIZE = 128;
    public static final long PASSWORD_SLOT_A_OFFSET = 256L;
    public static final long PASSWORD_SLOT_B_OFFSET = 384L;
    public static final long RECOVERY_SLOT_A_OFFSET = 512L;
    public static final long RECOVERY_SLOT_B_OFFSET = 640L;

    public static final int NONCE_STATE_SIZE = 128;
    public static final long NONCE_STATE_A_OFFSET = 768L;
    public static final long NONCE_STATE_B_OFFSET = 896L;

    public static final long RECORDS_START_OFFSET = 1024L;

    public static final int MAX_BLOCK_PLAINTEXT_SIZE = 1024 * 1024;           // 1 MiB
    public static final int MAX_METADATA_PLAINTEXT_SIZE = 64 * 1024 * 1024;   // 64 MiB

    public static final int RECORD_HEADER_SIZE = 32;
    public static final int RECORD_FOOTER_SIZE = 16;
    public static final int RECORD_FRAME_OVERHEAD = RECORD_HEADER_SIZE + RECORD_FOOTER_SIZE; // 48B

    public static final byte RECORD_TYPE_DATA_BLOCK = 1;
    public static final byte RECORD_TYPE_METADATA_SNAPSHOT = 2;
    public static final byte RECORD_TYPE_COMMIT = 3;

    public static final long DATA_NONCE_LEASE = 1024L;
    public static final long METADATA_NONCE_LEASE = 64L;
    public static final long COMMIT_NONCE_LEASE = 64L;

    private VaultConstants() {
    }
}
