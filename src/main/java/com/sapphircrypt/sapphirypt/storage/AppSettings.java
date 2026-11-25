package com.sapphircrypt.sapphirypt.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Non-sensitive user preferences persisted locally under {@code ~/.sapphirypt/settings.properties}.
 */
public final class AppSettings {
    private static final int MAX_RECENT_VAULTS = 8;

    private CpuProfile cpuProfile = CpuProfile.BALANCED;
    private int maxWorkersOverride = 0; // 0 = automatic
    private int autoLockMinutes = 10;   // 0 = disabled
    private boolean keepUnlockedDuringTransfers = true;
    private boolean autoCompactOnClose = false;
    private String defaultViewMode = "GRID";
    private boolean previewEnabledByDefault = true;
    private boolean clipboardAutoClearEnabled = true;
    private int clipboardClearSeconds = 30;
    private boolean minimizeToTray = false;
    private boolean shredSourceAfterImport = false;
    private String language = "tr";
    private ThemeMode themeMode = ThemeMode.DARK;
    private final List<Path> recentVaults = new ArrayList<>();

    public static Path settingsPath() {
        return Path.of(System.getProperty("user.home"), ".sapphirypt", "settings.properties");
    }

    public static AppSettings load() {
        return loadFrom(settingsPath());
    }

    static AppSettings loadFrom(Path path) {
        AppSettings settings = new AppSettings();
        if (path == null || !Files.isRegularFile(path)) {
            return settings;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            String profileStr = props.getProperty("cpuProfile", CpuProfile.BALANCED.name());
            try {
                settings.cpuProfile = CpuProfile.valueOf(profileStr);
            } catch (IllegalArgumentException ignored) {
                settings.cpuProfile = CpuProfile.BALANCED;
            }
            settings.maxWorkersOverride = parseInt(props.getProperty("maxWorkersOverride"), 0, 0, 16);
            settings.autoLockMinutes = parseInt(props.getProperty("autoLockMinutes"), 10, 0, 240);
            settings.keepUnlockedDuringTransfers = Boolean.parseBoolean(props.getProperty("keepUnlockedDuringTransfers", "true"));
            settings.autoCompactOnClose = Boolean.parseBoolean(props.getProperty("autoCompactOnClose", "false"));
            settings.setDefaultViewMode(props.getProperty("defaultViewMode", "GRID"));
            settings.previewEnabledByDefault = Boolean.parseBoolean(props.getProperty("previewEnabledByDefault", "true"));
            settings.clipboardAutoClearEnabled = Boolean.parseBoolean(props.getProperty("clipboardAutoClearEnabled", "true"));
            settings.clipboardClearSeconds = parseInt(props.getProperty("clipboardClearSeconds"), 30, 10, 300);
            settings.minimizeToTray = Boolean.parseBoolean(props.getProperty("minimizeToTray", "false"));
            settings.shredSourceAfterImport = Boolean.parseBoolean(props.getProperty("shredSourceAfterImport", "false"));
            settings.setLanguage(props.getProperty("language", "tr"));
            String themeStr = props.getProperty("themeMode", ThemeMode.DARK.name());
            try {
                settings.themeMode = ThemeMode.valueOf(themeStr);
            } catch (IllegalArgumentException ignored) {
                settings.themeMode = ThemeMode.DARK;
            }
            int recentCount = parseInt(props.getProperty("recentCount"), 0, 0, MAX_RECENT_VAULTS);
            for (int i = 0; i < recentCount; i++) {
                String p = props.getProperty("recent." + i);
                if (p != null && !p.isBlank()) settings.recentVaults.add(Path.of(p));
            }
        } catch (IOException ignored) {
        }
        return settings;
    }

    public void save() {
        saveTo(settingsPath());
    }

    void saveTo(Path path) {
        if (path == null) {
            return;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Properties props = new Properties();
            props.setProperty("cpuProfile", cpuProfile.name());
            props.setProperty("maxWorkersOverride", Integer.toString(maxWorkersOverride));
            props.setProperty("autoLockMinutes", Integer.toString(autoLockMinutes));
            props.setProperty("keepUnlockedDuringTransfers", Boolean.toString(keepUnlockedDuringTransfers));
            props.setProperty("autoCompactOnClose", Boolean.toString(autoCompactOnClose));
            props.setProperty("defaultViewMode", defaultViewMode);
            props.setProperty("previewEnabledByDefault", Boolean.toString(previewEnabledByDefault));
            props.setProperty("clipboardAutoClearEnabled", Boolean.toString(clipboardAutoClearEnabled));
            props.setProperty("clipboardClearSeconds", Integer.toString(clipboardClearSeconds));
            props.setProperty("minimizeToTray", Boolean.toString(minimizeToTray));
            props.setProperty("shredSourceAfterImport", Boolean.toString(shredSourceAfterImport));
            props.setProperty("language", language);
            props.setProperty("themeMode", themeMode.name());
            props.setProperty("recentCount", Integer.toString(recentVaults.size()));
            for (int i = 0; i < recentVaults.size(); i++) {
                props.setProperty("recent." + i, recentVaults.get(i).toAbsolutePath().toString());
            }
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Sapphirypt User Preferences");
            }
        } catch (IOException ignored) {
        }
    }

    public CpuProfile cpuProfile() {
        return cpuProfile;
    }

    public void setCpuProfile(CpuProfile cpuProfile) {
        this.cpuProfile = cpuProfile == null ? CpuProfile.BALANCED : cpuProfile;
    }

    public int maxWorkersOverride() {
        return maxWorkersOverride;
    }

    public void setMaxWorkersOverride(int maxWorkersOverride) {
        this.maxWorkersOverride = Math.max(0, Math.min(16, maxWorkersOverride));
    }

    public int autoLockMinutes() {
        return autoLockMinutes;
    }

    public void setAutoLockMinutes(int autoLockMinutes) {
        this.autoLockMinutes = Math.max(0, Math.min(240, autoLockMinutes));
    }

    public boolean keepUnlockedDuringTransfers() {
        return keepUnlockedDuringTransfers;
    }

    public void setKeepUnlockedDuringTransfers(boolean keepUnlockedDuringTransfers) {
        this.keepUnlockedDuringTransfers = keepUnlockedDuringTransfers;
    }

    public boolean autoCompactOnClose() {
        return autoCompactOnClose;
    }

    public void setAutoCompactOnClose(boolean autoCompactOnClose) {
        this.autoCompactOnClose = autoCompactOnClose;
    }

    public String defaultViewMode() {
        return defaultViewMode;
    }

    public boolean defaultGridView() {
        return !"LIST".equalsIgnoreCase(defaultViewMode);
    }

    public void setDefaultViewMode(String mode) {
        this.defaultViewMode = "LIST".equalsIgnoreCase(mode) ? "LIST" : "GRID";
    }

    public boolean previewEnabledByDefault() {
        return previewEnabledByDefault;
    }

    public void setPreviewEnabledByDefault(boolean previewEnabledByDefault) {
        this.previewEnabledByDefault = previewEnabledByDefault;
    }

    public boolean clipboardAutoClearEnabled() {
        return clipboardAutoClearEnabled;
    }

    public void setClipboardAutoClearEnabled(boolean clipboardAutoClearEnabled) {
        this.clipboardAutoClearEnabled = clipboardAutoClearEnabled;
    }

    public int clipboardClearSeconds() {
        return clipboardClearSeconds;
    }

    public void setClipboardClearSeconds(int clipboardClearSeconds) {
        this.clipboardClearSeconds = Math.max(10, Math.min(300, clipboardClearSeconds));
    }

    public boolean minimizeToTray() {
        return minimizeToTray;
    }

    public void setMinimizeToTray(boolean minimizeToTray) {
        this.minimizeToTray = minimizeToTray;
    }

    public boolean shredSourceAfterImport() {
        return shredSourceAfterImport;
    }

    public void setShredSourceAfterImport(boolean shredSourceAfterImport) {
        this.shredSourceAfterImport = shredSourceAfterImport;
    }

    public String language() {
        return language;
    }

    public boolean isEnglish() {
        return "en".equals(language);
    }

    public void setLanguage(String language) {
        this.language = (language != null && "en".equalsIgnoreCase(language.trim())) ? "en" : "tr";
    }

    public ThemeMode themeMode() {
        return themeMode;
    }

    public void setThemeMode(ThemeMode themeMode) {
        this.themeMode = themeMode == null ? ThemeMode.DARK : themeMode;
    }

    public List<Path> recentVaults() {
        return List.copyOf(recentVaults);
    }

    public void addRecentVault(Path vaultPath) {
        if (vaultPath == null) return;

        Path normalized = vaultPath.toAbsolutePath().normalize();
        recentVaults.remove(normalized);
        recentVaults.add(0, normalized);
        while (recentVaults.size() > MAX_RECENT_VAULTS) {
            recentVaults.remove(recentVaults.size() - 1);
        }
    }

    public void removeRecentVault(Path vaultPath) {
        if (vaultPath != null) {
            recentVaults.remove(vaultPath.toAbsolutePath().normalize());
        }
    }

    private static int parseInt(String raw, int fallback, int min, int max) {
        if (raw == null) return fallback;

        try {
            int v = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
