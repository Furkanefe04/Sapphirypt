package com.sapphircrypt.sapphirypt.storage;

import com.sapphircrypt.sapphirypt.crypto.SensitiveBytes;
import com.sapphircrypt.sapphirypt.format.VaultConstants;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Managed worker pool for parallel block encryption/decryption with reusable per-worker buffers,
 * bounded queue backpressure, live CPU-profile reconfiguration, and buffer wiping on shutdown.
 */
public final class CryptoWorkerPool implements AutoCloseable {

    public static final class WorkerBuffers {
        public final byte[] plaintext = new byte[VaultConstants.MAX_BLOCK_PLAINTEXT_SIZE];
        public final byte[] ciphertext = new byte[VaultConstants.MAX_BLOCK_PLAINTEXT_SIZE + 16];

        public void wipePlaintext() {
            SensitiveBytes.wipe(plaintext);
        }

        public void wipeAll() {
            SensitiveBytes.wipe(plaintext);
            SensitiveBytes.wipe(ciphertext);
        }
    }

    @FunctionalInterface
    public interface BufferTask<T> {
        T run(WorkerBuffers buffers) throws Exception;
    }

    private final ThreadPoolExecutor executor;
    private final Set<WorkerBuffers> allBuffers = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<WorkerBuffers> localBuffers = ThreadLocal.withInitial(() -> {
        WorkerBuffers wb = new WorkerBuffers();
        allBuffers.add(wb);
        return wb;
    });
    private final Object backpressureLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private CpuProfile profile;
    private int maxWorkersOverride;
    private int workerCount;
    private int maxInFlight;
    private int inFlight;

    public CryptoWorkerPool(CpuProfile initialProfile, int maxWorkersOverride) {
        this.profile = initialProfile == null ? CpuProfile.BALANCED : initialProfile;
        this.maxWorkersOverride = maxWorkersOverride;
        this.workerCount = this.profile.computeWorkerCount(maxWorkersOverride);
        this.maxInFlight = this.profile.computeQueueCapacity(this.workerCount);

        AtomicInteger threadId = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "sapphirypt-crypto-" + threadId.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        this.executor = new ThreadPoolExecutor(
                workerCount, workerCount, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), factory);
        this.executor.allowCoreThreadTimeOut(true);
    }

    public <T> Future<T> submit(CancellationToken cancel, BufferTask<T> task)
            throws VaultCancelledException, VaultLockedException {
        acquireSlot(cancel);
        try {
            return executor.submit(() -> {
                WorkerBuffers buffers = localBuffers.get();
                try {
                    return task.run(buffers);
                } finally {
                    buffers.wipePlaintext();
                    releaseSlot();
                }
            });
        } catch (RuntimeException e) {
            releaseSlot();
            throw e;
        }
    }

    private void acquireSlot(CancellationToken cancel)
            throws VaultCancelledException, VaultLockedException {
        synchronized (backpressureLock) {
            while (!closed.get() && inFlight >= maxInFlight) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new VaultCancelledException("İşlem kullanıcı tarafından iptal edildi");
                }
                try {
                    backpressureLock.wait(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new VaultCancelledException("İşlem kesintiye uğradı");
                }
            }
            if (closed.get()) {
                throw new VaultLockedException("Kasa kapalı veya kilitli");
            }
            if (cancel != null && cancel.isCancelled()) {
                throw new VaultCancelledException("İşlem kullanıcı tarafından iptal edildi");
            }
            inFlight++;
        }
    }

    private void releaseSlot() {
        synchronized (backpressureLock) {
            if (inFlight > 0) inFlight--;

            backpressureLock.notifyAll();
        }
    }

    public void reconfigure(CpuProfile newProfile, int newMaxWorkersOverride) {
        if (closed.get()) return;

        CpuProfile effectiveProfile = newProfile == null ? CpuProfile.BALANCED : newProfile;
        int newWorkers = effectiveProfile.computeWorkerCount(newMaxWorkersOverride);
        int newMaxInFlight = effectiveProfile.computeQueueCapacity(newWorkers);
        synchronized (backpressureLock) {
            this.profile = effectiveProfile;
            this.maxWorkersOverride = newMaxWorkersOverride;
            this.workerCount = newWorkers;
            this.maxInFlight = newMaxInFlight;
            if (newWorkers > executor.getMaximumPoolSize()) {
                executor.setMaximumPoolSize(newWorkers);
                executor.setCorePoolSize(newWorkers);
            } else {
                executor.setCorePoolSize(newWorkers);
                executor.setMaximumPoolSize(newWorkers);
            }
            backpressureLock.notifyAll();
        }
    }

    public CpuProfile profile() {
        synchronized (backpressureLock) {
            return profile;
        }
    }

    public int workerCount() {
        synchronized (backpressureLock) {
            return workerCount;
        }
    }

    public int maxInFlight() {
        synchronized (backpressureLock) {
            return maxInFlight;
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            synchronized (backpressureLock) {
                backpressureLock.notifyAll();
            }
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (WorkerBuffers wb : allBuffers) {
                wb.wipeAll();
            }
            allBuffers.clear();
        }
    }
}
