package com.sapphircrypt.sapphirypt.storage;

import com.sapphircrypt.sapphirypt.crypto.SensitiveBytes;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded two-tier block cache:
 * 1) Encrypted block LRU cache (default max 32 MiB)
 * 2) Short-lived plaintext block TTL + LRU cache (default max 8 MiB, 15s TTL), wiped on eviction/clear.
 */
public final class BlockCache implements AutoCloseable {
    public static final long DEFAULT_MAX_ENCRYPTED_BYTES = 32L * 1024L * 1024L;
    public static final long DEFAULT_MAX_PLAINTEXT_BYTES = 8L * 1024L * 1024L;
    public static final long DEFAULT_PLAINTEXT_TTL_MILLIS = 15_000L;

    public record CacheStats(
            long encryptedBytes,
            int encryptedEntries,
            long plaintextBytes,
            int plaintextEntries) {
    }

    private record PlaintextEntry(byte[] data, long expiresAtMillis) { }

    private final long maxEncryptedBytes;
    private final long maxPlaintextBytes;
    private final long plaintextTtlMillis;

    private final LinkedHashMap<UUID, byte[]> encryptedLru = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<UUID, PlaintextEntry> plaintextLru = new LinkedHashMap<>(16, 0.75f, true);
    private long encryptedBytes;
    private long plaintextBytes;

    public BlockCache() {
        this(DEFAULT_MAX_ENCRYPTED_BYTES, DEFAULT_MAX_PLAINTEXT_BYTES, DEFAULT_PLAINTEXT_TTL_MILLIS);
    }

    public BlockCache(long maxEncryptedBytes, long maxPlaintextBytes, long plaintextTtlMillis) {
        this.maxEncryptedBytes = Math.max(0L, maxEncryptedBytes);
        this.maxPlaintextBytes = Math.max(0L, maxPlaintextBytes);
        this.plaintextTtlMillis = Math.max(0L, plaintextTtlMillis);
    }

    public synchronized byte[] getEncrypted(UUID blockId) {
        byte[] data = encryptedLru.get(blockId);
        return data == null ? null : Arrays.copyOf(data, data.length);
    }

    public synchronized void putEncrypted(UUID blockId, byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length > maxEncryptedBytes) {
            return;
        }
        byte[] existing = encryptedLru.remove(blockId);
        if (existing != null) encryptedBytes -= existing.length;

        byte[] copy = Arrays.copyOf(ciphertext, ciphertext.length);
        encryptedLru.put(blockId, copy);
        encryptedBytes += copy.length;
        evictEncryptedIfNeeded();
    }

    public synchronized byte[] getPlaintext(UUID blockId) {
        long now = System.currentTimeMillis();
        evictExpiredPlaintext(now);
        PlaintextEntry entry = plaintextLru.get(blockId);
        if (entry == null) return null;

        if (now >= entry.expiresAtMillis) {
            plaintextLru.remove(blockId);
            plaintextBytes -= entry.data.length;
            SensitiveBytes.wipe(entry.data);
            return null;
        }
        return Arrays.copyOf(entry.data, entry.data.length);
    }

    public synchronized void putPlaintext(UUID blockId, byte[] plaintext) {
        if (plaintext == null || plaintext.length > maxPlaintextBytes || plaintextTtlMillis == 0) return;

        long now = System.currentTimeMillis();
        evictExpiredPlaintext(now);
        PlaintextEntry existing = plaintextLru.remove(blockId);
        if (existing != null) {
            plaintextBytes -= existing.data.length;
            SensitiveBytes.wipe(existing.data);
        }
        byte[] copy = Arrays.copyOf(plaintext, plaintext.length);
        plaintextLru.put(blockId, new PlaintextEntry(copy, now + plaintextTtlMillis));
        plaintextBytes += copy.length;
        evictPlaintextIfNeeded();
    }

    public synchronized CacheStats stats() {
        evictExpiredPlaintext(System.currentTimeMillis());
        return new CacheStats(encryptedBytes, encryptedLru.size(), plaintextBytes, plaintextLru.size());
    }

    public synchronized void clear() {
        for (PlaintextEntry entry : plaintextLru.values()) SensitiveBytes.wipe(entry.data);

        plaintextLru.clear();
        plaintextBytes = 0L;
        encryptedLru.clear();
        encryptedBytes = 0L;
    }

    @Override
    public void close() {
        clear();
    }

    private void evictEncryptedIfNeeded() {
        Iterator<Map.Entry<UUID, byte[]>> it = encryptedLru.entrySet().iterator();
        while (encryptedBytes > maxEncryptedBytes && it.hasNext()) {
            Map.Entry<UUID, byte[]> eldest = it.next();
            encryptedBytes -= eldest.getValue().length;
            it.remove();
        }
    }

    private void evictExpiredPlaintext(long now) {
        Iterator<Map.Entry<UUID, PlaintextEntry>> it = plaintextLru.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PlaintextEntry> item = it.next();
            if (now >= item.getValue().expiresAtMillis) {
                plaintextBytes -= item.getValue().data.length;
                SensitiveBytes.wipe(item.getValue().data);
                it.remove();
            }
        }
    }

    private void evictPlaintextIfNeeded() {
        Iterator<Map.Entry<UUID, PlaintextEntry>> it = plaintextLru.entrySet().iterator();
        while (plaintextBytes > maxPlaintextBytes && it.hasNext()) {
            Map.Entry<UUID, PlaintextEntry> eldest = it.next();
            plaintextBytes -= eldest.getValue().data.length;
            SensitiveBytes.wipe(eldest.getValue().data);
            it.remove();
        }
    }
}
