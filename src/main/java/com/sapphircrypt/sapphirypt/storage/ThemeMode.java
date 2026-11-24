package com.sapphircrypt.sapphirypt.storage;

import java.time.LocalTime;

/**
 * UI theme preference supporting manual dark/light modes and fixed day/night auto-switching.
 */
public enum ThemeMode {
    AUTO("Otomatik (Gece/Gündüz)"),
    DARK("Koyu"),
    LIGHT("Açık");

    private final String label;

    ThemeMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Returns true if dark theme should be active at the supplied local time.
     * In {@link #AUTO} mode, light theme is active from 07:00 (inclusive) to 19:00 (exclusive).
     */
    public boolean isDarkAt(LocalTime time) {
        return switch (this) {
            case DARK -> true;
            case LIGHT -> false;
            case AUTO -> {
                if (time == null) {
                    yield true;
                }
                int hour = time.getHour();
                yield hour < 7 || hour >= 19;
            }
        };
    }

    /**
     * Cycles through DARK -> LIGHT -> AUTO -> DARK.
     */
    public ThemeMode next() {
        return switch (this) {
            case DARK -> LIGHT;
            case LIGHT -> AUTO;
            case AUTO -> DARK;
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
