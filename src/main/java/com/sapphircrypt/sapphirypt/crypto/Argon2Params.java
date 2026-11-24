package com.sapphircrypt.sapphirypt.crypto;

import com.sun.management.OperatingSystemMXBean;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Validated Argon2id KDF parameters with 1 GiB (1024 MiB) maximum memory cap.
 */
public record Argon2Params(int version, int memoryKiB, int iterations, int parallelism) {

    public static final int VERSION_13 = Argon2Parameters.ARGON2_VERSION_13;
    public static final int MIN_MEMORY_KIB = 16 * 1024;       // 16 MiB
    public static final int MAX_MEMORY_KIB = 1024 * 1024;     // 1024 MiB = 1 GiB cap
    public static final int MIN_ITERATIONS = 1;
    public static final int MAX_ITERATIONS = 20;
    public static final int MIN_PARALLELISM = 1;
    public static final int MAX_PARALLELISM = 16;

    /**
     * Selectable UI preset with runtime availability assessment.
     */
    public record Argon2Preset(String label, Argon2Params params, boolean available, String reason) {
        @Override
        public String toString() {
            return available ? label : label + " (" + reason + ")";
        }
    }

    public void validate() {
        if (version != VERSION_13) {
            throw new IllegalArgumentException("Desteklenmeyen Argon2 sürümü: 0x" + Integer.toHexString(version));
        }
        if (memoryKiB < MIN_MEMORY_KIB || memoryKiB > MAX_MEMORY_KIB) {
            throw new IllegalArgumentException("Argon2id bellek parametresi güvenli sınırların dışında (16 MiB - 1024 MiB): " + (memoryKiB / 1024) + " MiB");
        }
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("Argon2id iterasyon parametresi güvenli sınırların dışında (1 - 20): " + iterations);
        }
        if (parallelism < MIN_PARALLELISM || parallelism > MAX_PARALLELISM) {
            throw new IllegalArgumentException("Argon2id paralellik parametresi güvenli sınırların dışında (1 - 16): " + parallelism);
        }
    }

    /**
     * Validates stored KDF parameters against both specification bounds and current JVM heap safety
     * before running Argon2id during vault unlock.
     */
    public void validateForExecution() {
        validate();
        long requiredBytes = (long) memoryKiB * 1024L;
        long maxHeap = Runtime.getRuntime().maxMemory();
        if (maxHeap != Long.MAX_VALUE && requiredBytes > (long) (maxHeap * 0.85)) {
            throw new IllegalArgumentException("Kasa Argon2id belleği (" + (memoryKiB / 1024) + " MiB) mevcut JVM bellek sınırını aşıyor.");
        }
    }

    public int memoryMiB() {
        return memoryKiB / 1024;
    }

    public static Argon2Params recommendForSystem() {
        long safeBudgetBytes = computeSafeMemoryBudgetBytes();
        int[] tiersMiB = {512, 256, 128, 64, 32};
        int chosenMiB = 32;
        for (int tier : tiersMiB) {
            if ((long) tier * 1024L * 1024L <= safeBudgetBytes) {
                chosenMiB = tier;
                break;
            }
        }
        int cpus = Runtime.getRuntime().availableProcessors();
        int lanes = Math.min(4, Math.max(1, cpus / 2));
        return new Argon2Params(VERSION_13, chosenMiB * 1024, 3, lanes);
    }

    public static List<Argon2Preset> availablePresets() {
        Argon2Params recommended = recommendForSystem();
        long safeBudgetBytes = computeSafeMemoryBudgetBytes();
        long maxHeap = Runtime.getRuntime().maxMemory();
        int lanes = recommended.parallelism();

        return List.of(new Argon2Preset("Önerilen (" + recommended.memoryMiB() + " MiB, " + recommended.iterations() + " iter, " + lanes + " iş parçacığı)", recommended, true, ""),
                buildPreset("Düşük Bellek (32 MiB, 4 iter)", 32, 4, lanes, safeBudgetBytes, maxHeap),
                buildPreset("Standart (64 MiB, 3 iter)", 64, 3, lanes, safeBudgetBytes, maxHeap),
                buildPreset("Güçlü (128 MiB, 3 iter)", 128, 3, lanes, safeBudgetBytes, maxHeap),
                buildPreset("Yüksek (256 MiB, 3 iter)", 256, 3, lanes, safeBudgetBytes, maxHeap),
                buildPreset("Çok Yüksek (512 MiB, 3 iter)", 512, 3, lanes, safeBudgetBytes, maxHeap),
                buildPreset("Maksimum (1024 MiB / 1 GiB, 2 iter)", 1024, 2, lanes, safeBudgetBytes, maxHeap));
    }

    private static Argon2Preset buildPreset(String label, int miB, int iterations, int lanes, long safeBudgetBytes, long maxHeap) {
        Argon2Params params = new Argon2Params(VERSION_13, miB * 1024, iterations, lanes);
        long bytes = (long) miB * 1024L * 1024L;
        boolean fitsHeap = maxHeap == Long.MAX_VALUE || bytes <= maxHeap / 2;
        boolean fitsOs = bytes <= Math.max(safeBudgetBytes * 2L, 64L * 1024L * 1024L);
        if (!fitsHeap) {
            return new Argon2Preset(label, params, false, "JVM heap yetersiz");
        }
        if (!fitsOs) {
            return new Argon2Preset(label, params, false, "Kullanılabilir RAM yetersiz");
        }
        return new Argon2Preset(label, params, true, "");
    }

    private static long computeSafeMemoryBudgetBytes() {
        long maxHeap = Runtime.getRuntime().maxMemory();
        long heapCap = (maxHeap == Long.MAX_VALUE) ? 1024L * 1024L * 1024L : maxHeap / 3L;
        long freePhysical = Long.MAX_VALUE;
        try {
            var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean sunBean) {
                long free = sunBean.getFreeMemorySize();
                if (free > 0) {
                    freePhysical = free;
                }
            }
        } catch (Throwable ignored) {
            // Fall back to heap cap if OS MXBean is unavailable.
        }
        long osCap = (freePhysical == Long.MAX_VALUE) ? heapCap : freePhysical / 4L;
        long hardCap = (long) MAX_MEMORY_KIB * 1024L;
        return Math.max((long) MIN_MEMORY_KIB * 1024L, Math.min(hardCap, Math.min(heapCap, osCap)));
    }

    public static Argon2Params forTesting() {
        return new Argon2Params(VERSION_13, MIN_MEMORY_KIB, 1, 1);
    }
}
