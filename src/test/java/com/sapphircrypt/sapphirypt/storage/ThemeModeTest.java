package com.sapphircrypt.sapphirypt.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class ThemeModeTest {

    @Test
    void autoModeFollowsDayNightCycleBoundaries() {
        assertTrue(ThemeMode.AUTO.isDarkAt(LocalTime.of(0, 0)));
        assertTrue(ThemeMode.AUTO.isDarkAt(LocalTime.of(6, 59)));
        assertFalse(ThemeMode.AUTO.isDarkAt(LocalTime.of(7, 0)));
        assertFalse(ThemeMode.AUTO.isDarkAt(LocalTime.of(12, 30)));
        assertFalse(ThemeMode.AUTO.isDarkAt(LocalTime.of(18, 59)));
        assertTrue(ThemeMode.AUTO.isDarkAt(LocalTime.of(19, 0)));
        assertTrue(ThemeMode.AUTO.isDarkAt(LocalTime.of(23, 59)));
        assertTrue(ThemeMode.AUTO.isDarkAt(null));
    }

    @Test
    void manualModesAndCycleWorkAsExpected() {
        assertTrue(ThemeMode.DARK.isDarkAt(LocalTime.of(12, 0)));
        assertTrue(ThemeMode.DARK.isDarkAt(LocalTime.of(2, 0)));

        assertFalse(ThemeMode.LIGHT.isDarkAt(LocalTime.of(12, 0)));
        assertFalse(ThemeMode.LIGHT.isDarkAt(LocalTime.of(22, 0)));

        assertEquals(ThemeMode.LIGHT, ThemeMode.DARK.next());
        assertEquals(ThemeMode.AUTO, ThemeMode.LIGHT.next());
        assertEquals(ThemeMode.DARK, ThemeMode.AUTO.next());
    }

    @Test
    void settingsPersistAndFallbackThemeMode(@TempDir Path tempDir) throws Exception {
        AppSettings defaults = new AppSettings();
        assertEquals(ThemeMode.DARK, defaults.themeMode());

        Path cfg = tempDir.resolve("settings.properties");
        defaults.setThemeMode(ThemeMode.AUTO);
        defaults.saveTo(cfg);

        AppSettings reloadedAuto = AppSettings.loadFrom(cfg);
        assertEquals(ThemeMode.AUTO, reloadedAuto.themeMode());

        reloadedAuto.setThemeMode(ThemeMode.LIGHT);
        reloadedAuto.saveTo(cfg);
        assertEquals(ThemeMode.LIGHT, AppSettings.loadFrom(cfg).themeMode());

        Files.writeString(cfg, "themeMode=INVALID_VALUE\n");
        assertEquals(ThemeMode.DARK, AppSettings.loadFrom(cfg).themeMode());
    }
}
