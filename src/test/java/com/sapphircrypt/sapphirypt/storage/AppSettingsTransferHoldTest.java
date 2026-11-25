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
}
