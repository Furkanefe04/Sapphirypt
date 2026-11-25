package com.sapphircrypt.sapphirypt.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSettingsTransferHoldTest {

    @Test
    void defaultsToTrueWhenMissingAndPersistsDisabledState(@TempDir Path tempDir) {
        AppSettings fromMissing = AppSettings.loadFrom(tempDir.resolve("missing.properties"));
        assertTrue(fromMissing.keepUnlockedDuringTransfers());

        AppSettings custom = new AppSettings();
        assertTrue(custom.keepUnlockedDuringTransfers());
        custom.setKeepUnlockedDuringTransfers(false);

        Path target = tempDir.resolve("settings.properties");
        custom.saveTo(target);

        AppSettings reloaded = AppSettings.loadFrom(target);
        assertFalse(reloaded.keepUnlockedDuringTransfers());
        assertFalse(reloaded.autoCompactOnClose());
    }

    @Test
    void autoCompactOnCloseDefaultsToFalseAndPersistsTrue(@TempDir Path tempDir) {
        AppSettings fromMissing = AppSettings.loadFrom(tempDir.resolve("missing.properties"));
        assertFalse(fromMissing.autoCompactOnClose());

        AppSettings custom = new AppSettings();
        assertFalse(custom.autoCompactOnClose());
        custom.setAutoCompactOnClose(true);

        Path target = tempDir.resolve("auto-compact-settings.properties");
        custom.saveTo(target);

        AppSettings reloaded = AppSettings.loadFrom(target);
        assertTrue(reloaded.autoCompactOnClose());
    }

    @Test
    void viewModeAndPreviewSettingsDefaultAndPersist(@TempDir Path tempDir) {
        AppSettings fromMissing = AppSettings.loadFrom(tempDir.resolve("missing.properties"));
        assertEquals("GRID", fromMissing.defaultViewMode());
        assertTrue(fromMissing.defaultGridView());
        assertTrue(fromMissing.previewEnabledByDefault());

        AppSettings custom = new AppSettings();
        custom.setDefaultViewMode("LIST");
        custom.setPreviewEnabledByDefault(false);
        assertFalse(custom.defaultGridView());

        Path target = tempDir.resolve("view-settings.properties");
        custom.saveTo(target);

        AppSettings reloaded = AppSettings.loadFrom(target);
        assertEquals("LIST", reloaded.defaultViewMode());
        assertFalse(reloaded.defaultGridView());
        assertFalse(reloaded.previewEnabledByDefault());

        reloaded.setDefaultViewMode("UNKNOWN");
        assertEquals("GRID", reloaded.defaultViewMode());
        assertTrue(reloaded.defaultGridView());
    }

    @Test
    void phase1SecurityTrayAndLanguageSettingsDefaultClampAndPersist(@TempDir Path tempDir) {
        AppSettings fromMissing = AppSettings.loadFrom(tempDir.resolve("missing.properties"));
        assertTrue(fromMissing.clipboardAutoClearEnabled());
        assertEquals(30, fromMissing.clipboardClearSeconds());
        assertFalse(fromMissing.minimizeToTray());
        assertEquals("tr", fromMissing.language());
        assertFalse(fromMissing.isEnglish());

        AppSettings custom = new AppSettings();
        custom.setClipboardAutoClearEnabled(false);
        custom.setClipboardClearSeconds(5); // clamps to 10
        assertEquals(10, custom.clipboardClearSeconds());
        custom.setClipboardClearSeconds(999); // clamps to 300
        assertEquals(300, custom.clipboardClearSeconds());
        custom.setClipboardClearSeconds(60);
        custom.setMinimizeToTray(true);
        custom.setLanguage("EN");
        assertEquals("en", custom.language());
        assertTrue(custom.isEnglish());

        Path target = tempDir.resolve("phase1-settings.properties");
        custom.saveTo(target);

        AppSettings reloaded = AppSettings.loadFrom(target);
        assertFalse(reloaded.clipboardAutoClearEnabled());
        assertEquals(60, reloaded.clipboardClearSeconds());
        assertTrue(reloaded.minimizeToTray());
        assertEquals("en", reloaded.language());
        assertTrue(reloaded.isEnglish());

        reloaded.setLanguage("fr");
        assertEquals("tr", reloaded.language());
        assertFalse(reloaded.isEnglish());
        assertFalse(reloaded.shredSourceAfterImport());

        reloaded.setShredSourceAfterImport(true);
        Path shredTarget = tempDir.resolve("shred-settings.properties");
        reloaded.saveTo(shredTarget);
        AppSettings shredReloaded = AppSettings.loadFrom(shredTarget);
        assertTrue(shredReloaded.shredSourceAfterImport());
    }
}
