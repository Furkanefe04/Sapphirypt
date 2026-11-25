package com.sapphircrypt.sapphirypt.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoLockMonitorTest {

    @Test
    void zeroMinutesNeverExpires() {
        assertFalse(AutoLockMonitor.isIdleExpired(TimeUnit.HOURS.toNanos(2), 0, false));
    }

    @Test
    void activeHoldPreventsExpirationEvenPastTimeout() {
        assertFalse(AutoLockMonitor.isIdleExpired(TimeUnit.MINUTES.toNanos(10), 5, true));
    }

    @Test
    void exactTimeoutBoundaryExpires() {
        assertTrue(AutoLockMonitor.isIdleExpired(TimeUnit.MINUTES.toNanos(5), 5, false));
    }

    @Test
    void justBeforeTimeoutDoesNotExpire() {
        long fourMin59Sec = TimeUnit.MINUTES.toNanos(5) - TimeUnit.SECONDS.toNanos(1);
        assertFalse(AutoLockMonitor.isIdleExpired(fourMin59Sec, 5, false));
    }
}
