package com.sapphircrypt.sapphirypt.ui;

import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.input.InputEvent;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks user inactivity and monotonic-vs-wall-clock jumps (OS sleep/hibernate detection)
 * to automatically lock an open vault on the JavaFX Application Thread.
 */
public final class AutoLockMonitor implements AutoCloseable {
    @FunctionalInterface
    public interface ActivityHold extends AutoCloseable {
        @Override
        void close();
    }

    private static final long CHECK_PERIOD_SECONDS = 5L;
    private static final long SLEEP_DRIFT_THRESHOLD_MILLIS = 30_000L;

    private final Scene scene;
    private final Runnable onAutoLock;
    private final AtomicInteger timeoutMinutes = new AtomicInteger(10);
    private final AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong lastTickWallMillis = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastTickNanoMillis = new AtomicLong(System.nanoTime() / 1_000_000L);
    private final Set<Object> activeHolds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean keepActiveDuringTransfers = new AtomicBoolean(true);
    private final EventHandler<InputEvent> activityFilter = event -> lastActivityNanos.set(System.nanoTime());
    private final ScheduledExecutorService scheduler;

    public AutoLockMonitor(Scene scene, int initialTimeoutMinutes, Runnable onAutoLock) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.onAutoLock = Objects.requireNonNull(onAutoLock, "onAutoLock");
        this.timeoutMinutes.set(Math.max(0, initialTimeoutMinutes));

        scene.addEventFilter(InputEvent.ANY, activityFilter);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sapphirypt-autolock");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::checkLockConditions, CHECK_PERIOD_SECONDS, CHECK_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    public void updateTimeoutMinutes(int minutes) {
        timeoutMinutes.set(Math.max(0, minutes));
        lastActivityNanos.set(System.nanoTime());
    }

    public void resetActivity() {
        lastActivityNanos.set(System.nanoTime());
        lastTickWallMillis.set(System.currentTimeMillis());
        lastTickNanoMillis.set(System.nanoTime() / 1_000_000L);
    }

    public void setKeepActiveDuringTransfers(boolean enabled) {
        keepActiveDuringTransfers.set(enabled);
        lastActivityNanos.set(System.nanoTime());
    }

    public ActivityHold beginActivityHold() {
        Object token = new Object();
        activeHolds.add(token);
        return () -> {
            if (activeHolds.remove(token)) {
                lastActivityNanos.set(System.nanoTime());
            }
        };
    }

    public void clearActivityHolds() {
        activeHolds.clear();
        lastActivityNanos.set(System.nanoTime());
    }

    static boolean isIdleExpired(long idleNanos, int timeoutMinutes, boolean holdActive) {
        if (timeoutMinutes <= 0 || holdActive) {
            return false;
        }
        return idleNanos >= TimeUnit.MINUTES.toNanos(timeoutMinutes);
    }

    private void checkLockConditions() {
        long nowWall = System.currentTimeMillis();
        long nowMonoMs = System.nanoTime() / 1_000_000L;

        long prevWall = lastTickWallMillis.getAndSet(nowWall);
        long prevMonoMs = lastTickNanoMillis.getAndSet(nowMonoMs);

        long wallDelta = nowWall - prevWall;
        long monoDelta = nowMonoMs - prevMonoMs;
        if (wallDelta - monoDelta > SLEEP_DRIFT_THRESHOLD_MILLIS) {
            Platform.runLater(onAutoLock);
            return;
        }

        boolean holdActive = keepActiveDuringTransfers.get() && !activeHolds.isEmpty();
        if (holdActive) {
            lastActivityNanos.set(System.nanoTime());
            return;
        }

        long idleNanos = System.nanoTime() - lastActivityNanos.get();
        if (isIdleExpired(idleNanos, timeoutMinutes.get(), false)) {
            Platform.runLater(onAutoLock);
        }
    }

    @Override
    public void close() {
        Platform.runLater(() -> scene.removeEventFilter(InputEvent.ANY, activityFilter));
        scheduler.shutdownNow();
    }
}
