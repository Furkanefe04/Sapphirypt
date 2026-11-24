package com.sapphircrypt.sapphirypt.storage;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Progress callback for streaming vault operations with built-in throttling for UI updates.
 */
@FunctionalInterface
public interface ProgressListener {
    ProgressListener NO_OP = (completedBytes, totalBytes, message) -> {};

    void onProgress(long completedBytes, long totalBytes, String statusMessage);

    static ProgressListener throttled(long minIntervalMillis, ProgressListener delegate) {
        if (delegate == null) return NO_OP;

        AtomicLong lastEmitNanos = new AtomicLong(0L);
        long intervalNanos = Math.max(1L, minIntervalMillis) * 1_000_000L;
        return (completedBytes, totalBytes, statusMessage) -> {
            long now = System.nanoTime();
            long prev = lastEmitNanos.get();
            boolean isFinal = totalBytes > 0 && completedBytes >= totalBytes;
            if (isFinal || prev == 0L || (now - prev) >= intervalNanos) {
                if (isFinal || lastEmitNanos.compareAndSet(prev, now)) {
                    delegate.onProgress(completedBytes, totalBytes, statusMessage);
                }
            }
        };
    }
}
