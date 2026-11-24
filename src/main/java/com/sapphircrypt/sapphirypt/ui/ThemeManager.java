package com.sapphircrypt.sapphirypt.ui;

import com.sapphircrypt.sapphirypt.storage.AppSettings;
import com.sapphircrypt.sapphirypt.storage.ThemeMode;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Parent;
import javafx.util.Duration;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates dark/light soft-gradient theme classes across registered JavaFX roots
 * and refreshes automatic day/night switching every 60 seconds.
 */
public final class ThemeManager implements AutoCloseable {
    public static final String CLASS_DARK = "theme-dark";
    public static final String CLASS_LIGHT = "theme-light";

    private final AppSettings settings;
    private final List<Parent> roots = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final Timeline autoSwitchTicker;

    private boolean currentDark;

    public ThemeManager(AppSettings settings) {
        this.settings = settings;
        this.currentDark = settings.themeMode().isDarkAt(LocalTime.now());
        this.autoSwitchTicker = new Timeline(new KeyFrame(Duration.seconds(60), e -> evaluateAutoTick()));
        this.autoSwitchTicker.setCycleCount(Timeline.INDEFINITE);
        updateTickerState();
    }

    public ThemeMode mode() {
        return settings.themeMode();
    }

    public boolean isDarkNow() {
        return currentDark;
    }

    public void setMode(ThemeMode mode) {
        ThemeMode effective = mode == null ? ThemeMode.DARK : mode;
        settings.setThemeMode(effective);
        settings.save();
        updateTickerState();
        applyAndNotify(true);
    }

    public void cycleMode() {
        setMode(settings.themeMode().next());
    }

    public void registerRoot(Parent root) {
        if (root != null && !roots.contains(root)) {
            roots.add(root);
            applyClasses(root, currentDark);
        }
    }

    public void unregisterRoot(Parent root) {
        roots.remove(root);
    }

    public void addListener(Runnable listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public static void applyClasses(Parent root, boolean dark) {
        if (root == null) {
            return;
        }
        var styleClasses = root.getStyleClass();
        styleClasses.removeAll(CLASS_DARK, CLASS_LIGHT);
        styleClasses.add(dark ? CLASS_DARK : CLASS_LIGHT);
    }

    private void evaluateAutoTick() {
        if (settings.themeMode() == ThemeMode.AUTO) {
            applyAndNotify(false);
        }
    }

    private void applyAndNotify(boolean forceNotify) {
        boolean nextDark = settings.themeMode().isDarkAt(LocalTime.now());
        boolean changed = nextDark != currentDark;
        currentDark = nextDark;
        for (Parent root : roots) {
            applyClasses(root, currentDark);
        }
        if (changed || forceNotify) {
            for (Runnable listener : List.copyOf(listeners)) {
                listener.run();
            }
        }
    }

    private void updateTickerState() {
        if (settings.themeMode() == ThemeMode.AUTO) {
            autoSwitchTicker.play();
        } else {
            autoSwitchTicker.stop();
        }
    }

    @Override
    public void close() {
        autoSwitchTicker.stop();
        listeners.clear();
        roots.clear();
    }
}
