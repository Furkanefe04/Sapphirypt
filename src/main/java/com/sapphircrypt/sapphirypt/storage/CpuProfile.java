package com.sapphircrypt.sapphirypt.storage;

/**
 * Configurable CPU profiles balancing responsiveness and throughput.
 */
public enum CpuProfile {
    POWER_SAVER("Tasarruf"),
    BALANCED("Dengeli"),
    PERFORMANCE("Performans");

    private final String displayName;

    CpuProfile(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public int computeWorkerCount(int maxWorkersOverride) {
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        int profileTarget = switch (this) {
            case POWER_SAVER -> Math.max(1, cpus / 4);
            case BALANCED -> Math.max(1, Math.min(4, Math.max(2, cpus / 2)));
            case PERFORMANCE -> Math.max(2, cpus);
        };
        long maxHeap = Runtime.getRuntime().maxMemory();
        int heapBounded = maxHeap == Long.MAX_VALUE ? profileTarget : Math.max(1, (int) (maxHeap / (16L * 1024L * 1024L)));
        int workers = Math.min(profileTarget, heapBounded);
        if (maxWorkersOverride > 0) workers = Math.min(workers, maxWorkersOverride);
        return Math.max(1, Math.min(16, workers));
    }

    public int computeQueueCapacity(int workers) {
        return Math.max(4, workers * 2);
    }
}
