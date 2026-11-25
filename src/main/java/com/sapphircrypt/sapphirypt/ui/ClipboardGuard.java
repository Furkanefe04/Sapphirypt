package com.sapphircrypt.sapphirypt.ui;

import javafx.application.Platform;
import javafx.scene.input.Clipboard;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Automatically clears sensitive clipboard content after a configured delay,
 * only removing the clipboard payload if it still matches the tracked sensitive copy.
 */
public final class ClipboardGuard implements AutoCloseable {
    private static final int MIN_SECONDS = 10;
    private static final int MAX_SECONDS = 300;

    private final ScheduledExecutorService scheduler;
    private final Supplier<String> clipboardReader;
    private final Runnable clipboardClearer;
    private final Consumer<Runnable> uiDispatcher;
    private final Object lock = new Object();

    private boolean enabled;
    private long clearAfterMillis;
    private ScheduledFuture<?> pendingTask;
    private String trackedText;
    private long generation;
    private volatile boolean closed;

    public ClipboardGuard(boolean enabled, int clearAfterSeconds) {
        this(
                enabled,
                clampSeconds(clearAfterSeconds) * 1000L,
                () -> {
                    Clipboard cb = Clipboard.getSystemClipboard();
                    return cb.hasString() ? cb.getString() : null;
                },
                () -> Clipboard.getSystemClipboard().clear(),
                Platform::runLater);
    }

    ClipboardGuard(
            boolean enabled,
            long clearAfterMillis,
            Supplier<String> clipboardReader,
            Runnable clipboardClearer,
            Consumer<Runnable> uiDispatcher) {
        this.enabled = enabled;
        this.clearAfterMillis = Math.max(10L, clearAfterMillis);
        this.clipboardReader = Objects.requireNonNull(clipboardReader, "clipboardReader");
        this.clipboardClearer = Objects.requireNonNull(clipboardClearer, "clipboardClearer");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sapphirypt-clipboard-guard");
            t.setDaemon(true);
            return t;
        });
    }

    public static int clampSeconds(int seconds) {
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
    }

    public void updateSettings(boolean enabled, int clearAfterSeconds) {
        synchronized (lock) {
            this.enabled = enabled;
            this.clearAfterMillis = clampSeconds(clearAfterSeconds) * 1000L;
            if (!enabled) {
                cancelPendingLocked();
                this.trackedText = null;
            }
        }
    }

    public void recordSensitiveCopy(String copiedText) {
        if (closed || copiedText == null || copiedText.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (!enabled || closed) {
                return;
            }
            cancelPendingLocked();
            long seq = ++generation;
            trackedText = copiedText;
            pendingTask = scheduler.schedule(() -> onTimerElapsed(seq), clearAfterMillis, TimeUnit.MILLISECONDS);
        }
    }

    public void clearPendingNow() {
        String expected;
        synchronized (lock) {
            cancelPendingLocked();
            generation++;
            expected = trackedText;
            trackedText = null;
        }
        if (expected != null && !closed) {
            uiDispatcher.accept(() -> {
                try {
                    String current = clipboardReader.get();
                    if (expected.equals(current)) {
                        clipboardClearer.run();
                    }
                } catch (Exception ignored) {
                }
            });
        }
    }

    private void onTimerElapsed(long seq) {
        String expected;
        synchronized (lock) {
            if (closed || !enabled || seq != generation || trackedText == null) {
                return;
            }
            expected = trackedText;
            pendingTask = null;
        }
        uiDispatcher.accept(() -> {
            synchronized (lock) {
                if (closed || seq != generation) {
                    return;
                }
                trackedText = null;
            }
            try {
                String current = clipboardReader.get();
                if (expected.equals(current)) {
                    clipboardClearer.run();
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void cancelPendingLocked() {
        if (pendingTask != null) {
            pendingTask.cancel(false);
            pendingTask = null;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            cancelPendingLocked();
            trackedText = null;
            generation++;
        }
        scheduler.shutdownNow();
    }
}
