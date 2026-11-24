package com.sapphircrypt.sapphirypt.storage;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation token checked across I/O and worker boundaries.
 */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public static final CancellationToken NONE = new CancellationToken();

    public void cancel() {
        if (this != NONE) cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() throws VaultCancelledException {
        if (cancelled.get()) {
            throw new VaultCancelledException("İşlem kullanıcı tarafından iptal edildi");
        }
    }
}
