package com.sapphircrypt.sapphirypt.ui;

import com.sapphircrypt.sapphirypt.SapphiryptApplication;
import javafx.application.Platform;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the OS notification-area / system-tray icon when supported by the desktop environment.
 */
public final class SystemTrayController implements AutoCloseable {
    private static final String TRAY_ICON_PRIMARY = "/com/sapphircrypt/sapphirypt/icons/sapphirypt-logo-32.png";
    private static final String TRAY_ICON_FALLBACK = "/com/sapphircrypt/sapphirypt/icons/sapphirypt-logo-16.png";

    private final SystemTray tray;
    private final TrayIcon trayIcon;
    private final MenuItem showItem;
    private final MenuItem lockItem;
    private final MenuItem exitItem;
    private final AtomicBoolean minimizedHintShown = new AtomicBoolean(false);

    private boolean vaultUnlocked;
    private volatile boolean active;

    public SystemTrayController(Runnable onShowWindow, Runnable onLockVault, Runnable onExit) {
        Objects.requireNonNull(onShowWindow, "onShowWindow");
        Objects.requireNonNull(onLockVault, "onLockVault");
        Objects.requireNonNull(onExit, "onExit");

        if (!isSupportedOnPlatform()) {
            this.tray = null;
            this.trayIcon = null;
            this.showItem = null;
            this.lockItem = null;
            this.exitItem = null;
            this.active = false;
            return;
        }

        Image iconImage = loadTrayImage();
        if (iconImage == null) {
            this.tray = null;
            this.trayIcon = null;
            this.showItem = null;
            this.lockItem = null;
            this.exitItem = null;
            this.active = false;
            return;
        }

        SystemTray hostTray = SystemTray.getSystemTray();
        PopupMenu popup = new PopupMenu();

        this.showItem = new MenuItem();
        this.lockItem = new MenuItem();
        this.exitItem = new MenuItem();

        this.showItem.addActionListener(e -> Platform.runLater(onShowWindow));
        this.lockItem.addActionListener(e -> Platform.runLater(onLockVault));
        this.exitItem.addActionListener(e -> Platform.runLater(onExit));

        popup.add(showItem);
        popup.add(lockItem);
        popup.addSeparator();
        popup.add(exitItem);

        TrayIcon createdIcon = new TrayIcon(iconImage, "Sapphirypt", popup);
        createdIcon.setImageAutoSize(true);
        createdIcon.addActionListener(e -> Platform.runLater(onShowWindow));

        boolean added = false;
        try {
            hostTray.add(createdIcon);
            added = true;
        } catch (Exception ignored) {
        }

        if (added) {
            this.tray = hostTray;
            this.trayIcon = createdIcon;
            this.active = true;
            setVaultUnlocked(false);
            refreshLabels();
        } else {
            this.tray = null;
            this.trayIcon = null;
            this.active = false;
        }
    }

    public static boolean isSupportedOnPlatform() {
        try {
            return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isActive() {
        return active;
    }

    public void setVaultUnlocked(boolean unlocked) {
        this.vaultUnlocked = unlocked;
        if (!active) {
            return;
        }
        lockItem.setEnabled(unlocked);
        updateTooltip();
    }

    public void refreshLabels() {
        if (!active) {
            return;
        }
        showItem.setLabel(I18n.t("Sapphirypt'i Göster", "Show Sapphirypt"));
        lockItem.setLabel(I18n.t("Kasayı Kilitle", "Lock Vault"));
        exitItem.setLabel(I18n.t("Çıkış", "Exit"));
        updateTooltip();
    }

    public void showMinimizedNotification() {
        if (!active || !minimizedHintShown.compareAndSet(false, true)) {
            return;
        }
        try {
            trayIcon.displayMessage(
                    "Sapphirypt",
                    I18n.t(
                            "Uygulama sistem tepsisinde çalışmaya devam ediyor.",
                            "Application continues running in the system tray."),
                    TrayIcon.MessageType.NONE);
        } catch (Exception ignored) {
        }
    }

    private void updateTooltip() {
        if (!active) {
            return;
        }
        trayIcon.setToolTip(vaultUnlocked
                ? I18n.t("Sapphirypt — Kasa Açık", "Sapphirypt — Vault Unlocked")
                : "Sapphirypt");
    }

    private static Image loadTrayImage() {
        Image primary = readImageResource(TRAY_ICON_PRIMARY);
        if (primary != null) {
            return primary;
        }
        return readImageResource(TRAY_ICON_FALLBACK);
    }

    private static Image readImageResource(String resourcePath) {
        try (InputStream in = SapphiryptApplication.class.getResourceAsStream(resourcePath)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException ignored) {
            return null;
        }
    }

    @Override
    public void close() {
        active = false;
        if (tray != null && trayIcon != null) {
            try {
                tray.remove(trayIcon);
            } catch (Exception ignored) {
            }
        }
    }
}
