package com.sapphircrypt.sapphirypt.ui;

import com.sapphircrypt.sapphirypt.storage.CpuProfile;
import com.sapphircrypt.sapphirypt.storage.ThemeMode;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight bilingual UI localization helper supporting Turkish ({@code "tr"}) and English ({@code "en"}).
 */
public final class I18n {
    private static volatile String currentLanguage = "tr";
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private I18n() {
    }

    public static String normalize(String code) {
        if (code != null && "en".equalsIgnoreCase(code.trim())) {
            return "en";
        }
        return "tr";
    }

    public static String language() {
        return currentLanguage;
    }

    public static boolean isEnglish() {
        return "en".equals(currentLanguage);
    }

    public static void setLanguage(String code) {
        String normalized = normalize(code);
        String previous = currentLanguage;
        if (!normalized.equals(previous)) {
            currentLanguage = normalized;
            for (Runnable listener : LISTENERS) {
                listener.run();
            }
        }
    }

    public static String t(String tr, String en) {
        return isEnglish() ? en : tr;
    }

    public static String themeLabel(ThemeMode mode) {
        if (mode == null) {
            return t("Koyu", "Dark");
        }
        return switch (mode) {
            case AUTO -> t("Otomatik (Gece/Gündüz)", "Auto (Day/Night)");
            case DARK -> t("Koyu", "Dark");
            case LIGHT -> t("Açık", "Light");
        };
    }

    public static String cpuProfileLabel(CpuProfile profile) {
        if (profile == null) {
            return t("Dengeli", "Balanced");
        }
        return switch (profile) {
            case POWER_SAVER -> t("Tasarruf", "Power Saver");
            case BALANCED -> t("Dengeli", "Balanced");
            case PERFORMANCE -> t("Performans", "Performance");
        };
    }

    public static void addListener(Runnable listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }
}
