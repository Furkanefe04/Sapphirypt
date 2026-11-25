package com.sapphircrypt.sapphirypt.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
    }
}
