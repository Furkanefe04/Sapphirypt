package com.sapphircrypt.sapphirypt;

import com.sapphircrypt.sapphirypt.format.VaultMetadata.StorageStats;
import com.sapphircrypt.sapphirypt.storage.AppSettings;
import com.sapphircrypt.sapphirypt.storage.CancellationToken;
import com.sapphircrypt.sapphirypt.storage.ProgressListener;
import com.sapphircrypt.sapphirypt.storage.SingleInstanceCoordinator;
import com.sapphircrypt.sapphirypt.storage.TempPreviewManager;
import com.sapphircrypt.sapphirypt.storage.VaultSession;
import com.sapphircrypt.sapphirypt.storage.VaultSession.VaultCreationResult;
import com.sapphircrypt.sapphirypt.ui.AutoLockMonitor;
import com.sapphircrypt.sapphirypt.ui.ClipboardGuard;
import com.sapphircrypt.sapphirypt.ui.FormatUtils;
import com.sapphircrypt.sapphirypt.ui.I18n;
import com.sapphircrypt.sapphirypt.ui.SapphireIcons;
import com.sapphircrypt.sapphirypt.ui.SplashScreen;
import com.sapphircrypt.sapphirypt.ui.SystemTrayController;
import com.sapphircrypt.sapphirypt.ui.ThemeManager;
import com.sapphircrypt.sapphirypt.ui.UiShell;
import com.sapphircrypt.sapphirypt.ui.UiShell.ToastType;
import com.sapphircrypt.sapphirypt.ui.VaultDrawers;
import com.sapphircrypt.sapphirypt.ui.VaultView;
import com.sapphircrypt.sapphirypt.ui.WelcomeView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sapphirypt JavaFX desktop application entry point and vault lifecycle coordinator.
 */
public final class SapphiryptApplication extends Application {
    private static final String CSS_PATH = "/com/sapphircrypt/sapphirypt/sapphirypt.css";
    private static final long MIN_SPLASH_DURATION_MS = 1450L;

    private final ExecutorService authExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sapphirypt-auth");
        t.setDaemon(true);
        return t;
    });

    private AppSettings settings;
    private ThemeManager themeManager;
    private Stage primaryStage;
    private Scene scene;
    private UiShell uiShell;
    private SplashScreen splashScreen;
    private WelcomeView welcomeView;
    private VaultView activeVaultView;
    private VaultSession activeSession;
    private Path activeVaultPath;
    private AutoLockMonitor autoLockMonitor;
    private SingleInstanceCoordinator singleInstanceCoordinator;
    private ClipboardGuard clipboardGuard;
    private SystemTrayController systemTrayController;
    private Button securityContextBtn;
    private Button lockContextBtn;
    private volatile Path pendingIncomingVaultPath;
    private volatile boolean shuttingDown;
    private volatile boolean explicitExitRequested;
    private boolean closingVaultInProgress;
    private boolean exitAfterAutoCompact;

    @Override
    public void start(Stage stage) {
        List<String> startupArgs = getParameters() != null ? getParameters().getRaw() : List.of();
        this.singleInstanceCoordinator = SingleInstanceCoordinator.tryAcquireOrForward(
                startupArgs,
                this::handleIncomingFileOpen,
                this::bringMainWindowToFront);
        if (this.singleInstanceCoordinator == null) {
            Platform.exit();
            return;
        }

        this.primaryStage = stage;
        stage.initStyle(StageStyle.TRANSPARENT);
        var appIcons = SapphireIcons.getApplicationIcons();
        if (!appIcons.isEmpty()) {
            stage.getIcons().setAll(appIcons);
        }

        AppSettings preloadedSettings = loadSettingsSafely();
        I18n.setLanguage(preloadedSettings.language());
        boolean initialDark = preloadedSettings.themeMode().isDarkAt(LocalTime.now());
        this.splashScreen = new SplashScreen(initialDark);
        this.splashScreen.show();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (activeSession != null) activeSession.close();
            if (singleInstanceCoordinator != null) singleInstanceCoordinator.close();
        }, "sapphirypt-shutdown"));

        Thread startupThread = new Thread(() -> runStartupSequence(stage, preloadedSettings), "sapphirypt-startup");
        startupThread.setDaemon(true);
        startupThread.start();
    }

    private void runStartupSequence(Stage stage, AppSettings loadedSettings) {
        long startedAtNanos = System.nanoTime();
        try {
            updateSplashProgress(0.28);
            TempPreviewManager.sweepOrphanTempFiles();
            new SecureRandom().nextInt();
            Argon2BytesGenerator.class.getName();
            if (shuttingDown) {
                return;
            }

            updateSplashProgress(0.62);
            CompletableFuture<Void> uiReady = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    if (!shuttingDown) {
                        initializePrimaryUi(stage, loadedSettings);
                    }
                    uiReady.complete(null);
                } catch (Throwable ex) {
                    uiReady.completeExceptionally(ex);
                }
            });
            uiReady.get();
            if (shuttingDown) {
                return;
            }

            updateSplashProgress(1.0);
            long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
            long remainingMs = MIN_SPLASH_DURATION_MS - elapsedMs;
            if (remainingMs > 0) {
                Thread.sleep(remainingMs);
            }
            if (shuttingDown) {
                return;
            }

            Platform.runLater(() -> {
                if (shuttingDown) {
                    return;
                }
                SplashScreen activeSplash = splashScreen;
                if (activeSplash == null) {
                    showMainWindowAndHandleArgs();
                    return;
                }
                activeSplash.transitionTo(() -> {
                    splashScreen = null;
                    showMainWindowAndHandleArgs();
                });
            });
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (shuttingDown) {
                return;
            }
            Platform.runLater(() -> {
                if (splashScreen != null) {
                    splashScreen.closeImmediately();
                    splashScreen = null;
                }
                if (scene == null) {
                    initializePrimaryUi(stage, new AppSettings());
                }
                if (!stage.isShowing()) {
                    stage.show();
                }
                if (uiShell != null) {
                    uiShell.showErrorOverlay(
                            I18n.t("Başlatma Hatası", "Startup Error"),
                            I18n.t("Uygulama başlatılırken bir hata oluştu.", "An error occurred while starting the application."),
                            ex);
                }
            });
        }
    }

    private void updateSplashProgress(double targetProgress) {
        SplashScreen activeSplash = splashScreen;
        if (activeSplash != null && !shuttingDown) {
            activeSplash.updateProgress(targetProgress);
        }
    }

    private static AppSettings loadSettingsSafely() {
        try {
            return AppSettings.load();
        } catch (RuntimeException ignored) {
            return new AppSettings();
        }
    }

    private void initializePrimaryUi(Stage stage, AppSettings loadedSettings) {
        this.settings = loadedSettings;
        I18n.setLanguage(settings.language());
        this.themeManager = new ThemeManager(loadedSettings);
        this.uiShell = new UiShell(stage, themeManager, this::handleOpenSettings);
        this.welcomeView = new WelcomeView(
                settings,
                themeManager,
                this::handleCreateVault,
                this::handleOpenVault,
                this::handleRenameClosedVault,
                this::handleDeleteClosedVault);

        uiShell.setTitleText("Sapphirypt");
        uiShell.setContent(welcomeView);

        scene = new Scene(uiShell, 1200, 770);
        scene.setFill(Color.TRANSPARENT);
        var cssUrl = SapphiryptApplication.class.getResource(CSS_PATH);
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        autoLockMonitor = new AutoLockMonitor(scene, settings.autoLockMinutes(), this::lockActiveVault);
        autoLockMonitor.setKeepActiveDuringTransfers(settings.keepUnlockedDuringTransfers());

        clipboardGuard = new ClipboardGuard(settings.clipboardAutoClearEnabled(), settings.clipboardClearSeconds());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (activeSession != null && !activeSession.isClosed()
                    && ev.isShortcutDown() && ev.getCode() == KeyCode.C) {
                Platform.runLater(() -> {
                    if (activeSession != null && !activeSession.isClosed() && clipboardGuard != null) {
                        Clipboard cb = Clipboard.getSystemClipboard();
                        if (cb.hasString()) {
                            clipboardGuard.recordSensitiveCopy(cb.getString());
                        }
                    }
                });
            }
        });

        systemTrayController = new SystemTrayController(
                this::bringMainWindowToFront,
                this::lockActiveVaultFromTray,
                this::requestExplicitExit);
        if (systemTrayController.isActive()) {
            Platform.setImplicitExit(false);
        }

        stage.setTitle("Sapphirypt");
        stage.setMinWidth(940);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.setOnCloseRequest(ev -> {
            if (closingVaultInProgress) {
                exitAfterAutoCompact = !settings.minimizeToTray() || explicitExitRequested;
                ev.consume();
                return;
            }
            if (!explicitExitRequested
                    && settings != null
                    && settings.minimizeToTray()
                    && systemTrayController != null
                    && systemTrayController.isActive()) {
                ev.consume();
                primaryStage.hide();
                systemTrayController.showMinimizedNotification();
                return;
            }
            if (shouldAutoCompactBeforeClose()) {
                ev.consume();
                compactThenLockOrExit(true, false);
                return;
            }
            shutdownCleanly();
            Platform.exit();
        });
    }

    private void showMainWindowAndHandleArgs() {
        if (primaryStage == null || scene == null || welcomeView == null) {
            return;
        }
        primaryStage.show();
        welcomeView.playEntranceAnimation();

        Path targetPath = pendingIncomingVaultPath;
        pendingIncomingVaultPath = null;
        if (targetPath == null) {
            List<String> rawArgs = getParameters().getRaw();
            if (!rawArgs.isEmpty() && rawArgs.get(0) != null && !rawArgs.get(0).isBlank()) {
                targetPath = Path.of(rawArgs.get(0));
            }
        }
        if (targetPath != null && Files.isRegularFile(targetPath)) {
            Path resolved = targetPath;
            Platform.runLater(() -> handleIncomingFileOpen(resolved));
        }
    }

    private void bringMainWindowToFront() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::bringMainWindowToFront);
            return;
        }
        if (shuttingDown || primaryStage == null || splashScreen != null) {
            return;
        }
        if (!primaryStage.isShowing()) {
            primaryStage.show();
        }
        if (primaryStage.isIconified()) {
            primaryStage.setIconified(false);
        }
        primaryStage.toFront();
        primaryStage.requestFocus();
    }

    private void handleIncomingFileOpen(Path rawPath) {
        if (rawPath == null || shuttingDown) {
            return;
        }
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> handleIncomingFileOpen(rawPath));
            return;
        }
        Path target = rawPath.toAbsolutePath().normalize();
        if (welcomeView == null || splashScreen != null) {
            pendingIncomingVaultPath = target;
            return;
        }
        bringMainWindowToFront();
        if (!Files.isRegularFile(target)) {
            if (uiShell != null) {
                uiShell.showToast(
                        ToastType.WARNING,
                        I18n.t("Dosya Bulunamadı", "File Not Found"),
                        target.getFileName().toString());
            }
            return;
        }
        if (activeSession != null && !activeSession.isClosed()) {
            if (activeVaultPath != null && activeVaultPath.toAbsolutePath().normalize().equals(target)) {
                uiShell.showToast(
                        ToastType.INFO,
                        I18n.t("Kasa Zaten Açık", "Vault Already Open"),
                        target.getFileName().toString());
            } else {
                uiShell.showToast(
                        ToastType.INFO,
                        I18n.t("Başka Bir Kasa Açık", "Another Vault Is Open"),
                        I18n.t("Açmak için önce mevcut kasayı kilitleyin: ", "Lock the current vault first to open: ")
                                + target.getFileName());
            }
            return;
        }
        handleOpenVault(target);
    }

    private void lockActiveVaultFromTray() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::lockActiveVaultFromTray);
            return;
        }
        if (activeSession != null && !activeSession.isClosed()) {
            lockActiveVault();
        }
    }

    private void requestExplicitExit() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::requestExplicitExit);
            return;
        }
        explicitExitRequested = true;
        if (closingVaultInProgress) {
            exitAfterAutoCompact = true;
            return;
        }
        if (shouldAutoCompactBeforeClose()) {
            compactThenLockOrExit(true, false);
            return;
        }
        shutdownCleanly();
        Platform.exit();
    }

    private void handleCreateVault() {
        VaultDrawers.showCreateVaultDrawer(uiShell, primaryStage, themeManager, (req, callback) ->
                authExecutor.submit(() -> {
                    try (req) {
                        VaultCreationResult created = VaultSession.createVault(
                                req.vaultPath, req.password, req.argon2Params, req.recoveryKeyPath,
                                settings.cpuProfile(), settings.maxWorkersOverride());
                        Platform.runLater(() -> {
                            callback.onSuccess();
                            activateSession(created.session());
                            uiShell.showToast(
                                    ToastType.SUCCESS,
                                    I18n.t("Kasa Oluşturuldu", "Vault Created"),
                                    I18n.t("Kurtarma anahtarı kaydedildi: ", "Recovery key saved: ") + created.recoveryKeyPath().getFileName());
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() -> callback.onFailure(ex.getMessage()));
                    }
                }));
    }

    private void handleOpenVault(Path preselectedPath) {
        VaultDrawers.showOpenVaultDrawer(uiShell, primaryStage, themeManager, preselectedPath, (req, callback) ->
                authExecutor.submit(() -> {
                    try (req) {
                        VaultSession session = req.usingRecoveryKey
                                ? VaultSession.openWithRecoveryKey(
                                        req.vaultPath, req.recoveryKeyPath, settings.cpuProfile(), settings.maxWorkersOverride())
                                : VaultSession.openWithPassword(
                                        req.vaultPath, req.password, settings.cpuProfile(), settings.maxWorkersOverride());
                        Platform.runLater(() -> {
                            callback.onSuccess();
                            activateSession(session);
                            uiShell.showToast(ToastType.SUCCESS, I18n.t("Kasa Açıldı", "Vault Unlocked"), session.vaultPath().getFileName().toString());
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() -> callback.onFailure(ex.getMessage()));
                    }
                }));
    }

    private void handleRenameClosedVault(Path preselectedPath) {
        VaultDrawers.showRenameClosedVaultDrawer(uiShell, primaryStage, themeManager, preselectedPath, (req, callback) -> {
            Path oldPath = req.vaultPath.toAbsolutePath().normalize();
            authExecutor.submit(() -> {
                try (req) {
                    Path newPath = VaultSession.renameVault(req.vaultPath, req.newVaultName, req.password, req.recoveryKeyPath);
                    Platform.runLater(() -> {
                        callback.onSuccess();
                        settings.removeRecentVault(oldPath);
                        settings.addRecentVault(newPath);
                        settings.save();
                        welcomeView.refreshRecentVaults();
                        uiShell.showToast(ToastType.SUCCESS, I18n.t("Kasa Adı Değiştirildi", "Vault Renamed"), newPath.getFileName().toString());
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> callback.onFailure(ex.getMessage()));
                }
            });
        });
    }

    private void handleDeleteClosedVault(Path preselectedPath) {
        VaultDrawers.showDeleteClosedVaultDrawer(uiShell, primaryStage, themeManager, preselectedPath, (req, callback) -> {
            Path targetPath = req.vaultPath.toAbsolutePath().normalize();
            authExecutor.submit(() -> {
                try (req) {
                    VaultSession.deleteVault(req.vaultPath, req.password, req.recoveryKeyPath, req.deleteRecoveryKeyFile);
                    Platform.runLater(() -> {
                        callback.onSuccess();
                        settings.removeRecentVault(targetPath);
                        settings.save();
                        welcomeView.refreshRecentVaults();
                        uiShell.showToast(ToastType.WARNING, I18n.t("Kasa Kalıcı Silindi", "Vault Permanently Deleted"), targetPath.getFileName().toString());
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> callback.onFailure(ex.getMessage()));
                }
            });
        });
    }

    private void handleOpenSettings() {
        try {
            VaultDrawers.showSettingsDrawer(uiShell, settings, themeManager, activeSession, autoLockMonitor, clipboardGuard, () -> {
                if (uiShell != null) {
                    uiShell.refreshLanguage();
                }
                if (systemTrayController != null) {
                    systemTrayController.refreshLabels();
                }
                if (securityContextBtn != null) {
                    securityContextBtn.setText(I18n.t("Güvenlik", "Security"));
                }
                if (lockContextBtn != null) {
                    lockContextBtn.setText(I18n.t("Kilitle", "Lock"));
                }
                if (activeVaultView != null) {
                    activeVaultView.refreshLanguage();
                    activeVaultView.applyViewSettings();
                    activeVaultView.refreshBadges();
                }
                if (welcomeView != null) {
                    welcomeView.refreshLanguage();
                }
            });
        } catch (Throwable ex) {
            if (uiShell != null) {
                uiShell.showErrorOverlay(I18n.t("Ayarlar Açılamadı", "Could Not Open Settings"), ex.getMessage(), ex);
            }
        }
    }

    private void activateSession(VaultSession session) {
        lockActiveVaultInternal(false);
        activeSession = session;
        activeVaultPath = session.vaultPath();
        settings.addRecentVault(activeVaultPath);
        settings.save();
        welcomeView.refreshRecentVaults();

        autoLockMonitor.resetActivity();
        if (systemTrayController != null) {
            systemTrayController.setVaultUnlocked(true);
        }
        activeVaultView = new VaultView(
                session,
                settings,
                uiShell,
                themeManager,
                autoLockMonitor,
                this::handleActiveVaultRenamed,
                this::handleActiveVaultDeleted);

        securityContextBtn = new Button(I18n.t("Güvenlik", "Security"));
        securityContextBtn.getStyleClass().add("btn-ghost");
        securityContextBtn.setGraphic(SapphireIcons.shield(14, uiShell.accentIconColor()));
        securityContextBtn.setFocusTraversable(false);
        securityContextBtn.setOnAction(e -> {
            if (activeVaultView != null) {
                activeVaultView.openSecurityDrawer();
            }
        });

        lockContextBtn = new Button(I18n.t("Kilitle", "Lock"));
        lockContextBtn.getStyleClass().add("btn-danger");
        lockContextBtn.setGraphic(SapphireIcons.lock(14, "#F87171"));
        lockContextBtn.setFocusTraversable(false);
        lockContextBtn.setOnAction(e -> lockActiveVault());

        com.sapphircrypt.sapphirypt.ui.MotionFx.attachPressSpring(securityContextBtn);
        com.sapphircrypt.sapphirypt.ui.MotionFx.attachPressSpring(lockContextBtn);
        uiShell.setContextActions(securityContextBtn, lockContextBtn);
        uiShell.setContentAnimated(activeVaultView);
        uiShell.setTitleText("Sapphirypt — " + activeVaultPath.getFileName());
    }

    private void handleActiveVaultRenamed(Path newPath) {
        Path oldPath = activeVaultPath;
        activeVaultPath = newPath;
        if (oldPath != null) {
            settings.removeRecentVault(oldPath);
        }
        settings.addRecentVault(newPath);
        settings.save();
        welcomeView.refreshRecentVaults();
        uiShell.setTitleText("Sapphirypt — " + newPath.getFileName());
    }

    private void handleActiveVaultDeleted() {
        Path deletedPath = activeVaultPath;
        if (deletedPath != null) {
            settings.removeRecentVault(deletedPath);
            settings.save();
        }
        activeVaultPath = null;
        if (activeVaultView != null) {
            activeVaultView.close();
            activeVaultView = null;
        }
        activeSession = null;
        securityContextBtn = null;
        lockContextBtn = null;
        if (clipboardGuard != null) {
            clipboardGuard.clearPendingNow();
        }
        if (systemTrayController != null) {
            systemTrayController.setVaultUnlocked(false);
        }
        welcomeView.refreshRecentVaults();
        uiShell.closeDrawer(true);
        uiShell.setContextActions();
        uiShell.setContentAnimated(welcomeView);
        welcomeView.playEntranceAnimation();
        uiShell.setTitleText("Sapphirypt");
        uiShell.showToast(
                ToastType.WARNING,
                I18n.t("Kasa Silindi", "Vault Deleted"),
                deletedPath != null
                        ? deletedPath.getFileName().toString()
                        : I18n.t("Açık kasa kalıcı olarak silindi.", "Open vault was permanently deleted."));
    }

    private boolean shouldAutoCompactBeforeClose() {
        return !shuttingDown
                && settings != null
                && settings.autoCompactOnClose()
                && activeSession != null
                && activeSession.hasReclaimableSpaceForAutoCompact();
    }

    private void lockActiveVault() {
        if (closingVaultInProgress) {
            return;
        }
        if (shouldAutoCompactBeforeClose()) {
            compactThenLockOrExit(false, true);
            return;
        }
        lockActiveVaultInternal(true);
    }

    private void compactThenLockOrExit(boolean exitApp, boolean showToast) {
        VaultSession targetSession = activeSession;
        if (targetSession == null) {
            if (exitApp) {
                shutdownCleanly();
                Platform.exit();
            } else {
                lockActiveVaultInternal(showToast);
            }
            return;
        }
        closingVaultInProgress = true;
        exitAfterAutoCompact = exitApp;
        if (activeVaultView != null) {
            activeVaultView.setDisable(true);
        }
        if (uiShell != null) {
            uiShell.showToast(
                    ToastType.INFO,
                    I18n.t("Kasa Sıkıştırılıyor", "Compacting Vault"),
                    I18n.t("Kapatılmadan önce boş alan temizleniyor...", "Reclaiming unused space before closing..."));
        }
        authExecutor.submit(() -> {
            StorageStats after = null;
            try {
                after = targetSession.compact(CancellationToken.NONE, ProgressListener.NO_OP);
            } catch (Exception ignored) {
            }
            StorageStats compactedStats = after;
            Platform.runLater(() -> {
                closingVaultInProgress = false;
                boolean shouldExit = exitAfterAutoCompact;
                exitAfterAutoCompact = false;
                if (shouldExit) {
                    shutdownCleanly();
                    Platform.exit();
                } else {
                    lockActiveVaultInternal(false);
                    if (showToast && !shuttingDown && uiShell != null) {
                        if (compactedStats != null) {
                            uiShell.showToast(
                                    ToastType.SUCCESS,
                                    I18n.t("Kasa Sıkıştırıldı ve Kilitlendi", "Vault Compacted & Locked"),
                                    I18n.t("Yeni boyut: ", "New size: ") + FormatUtils.formatBytes(compactedStats.physicalFileSize()));
                        } else {
                            uiShell.showToast(
                                    ToastType.WARNING,
                                    I18n.t("Kasa Kilitlendi", "Vault Locked"),
                                    I18n.t("Otomatik sıkıştırma tamamlanamadı; kasa güvenle kilitlendi.", "Auto-compaction did not finish; vault locked safely."));
                        }
                    }
                }
            });
        });
    }

    private void lockActiveVaultInternal(boolean showToast) {
        boolean wasUnlocked = activeSession != null || activeVaultView != null;
        if (activeSession != null && !activeSession.isClosed()) {
            activeSession.tempPreviewManager().syncPendingEditsNow();
        }
        if (activeVaultView != null) {
            activeVaultView.close();
            activeVaultView = null;
        }
        if (activeSession != null) {
            activeSession.close();
            activeSession = null;
        }
        if (clipboardGuard != null) {
            clipboardGuard.clearPendingNow();
        }
        if (systemTrayController != null) {
            systemTrayController.setVaultUnlocked(false);
        }
        securityContextBtn = null;
        lockContextBtn = null;
        if (autoLockMonitor != null) {
            autoLockMonitor.clearActivityHolds();
        }
        activeVaultPath = null;
        if (uiShell != null && welcomeView != null) {
            uiShell.closeDrawer(true);
            uiShell.setContextActions();
            if (wasUnlocked) {
                welcomeView.refreshRecentVaults();
                uiShell.setContentAnimated(welcomeView);
                welcomeView.playEntranceAnimation();
                uiShell.setTitleText("Sapphirypt");
                if (showToast && !shuttingDown) {
                    uiShell.showToast(
                            ToastType.INFO,
                            I18n.t("Kasa Kilitlendi", "Vault Locked"),
                            I18n.t("Oturum anahtarları bellekten temizlendi.", "Session keys cleared from memory."));
                }
            }
        }
    }

    private void shutdownCleanly() {
        shuttingDown = true;
        if (splashScreen != null) {
            splashScreen.closeImmediately();
            splashScreen = null;
        }
        lockActiveVaultInternal(false);
        if (welcomeView != null) {
            welcomeView.close();
            welcomeView = null;
        }
        if (autoLockMonitor != null) {
            autoLockMonitor.close();
            autoLockMonitor = null;
        }
        if (clipboardGuard != null) {
            clipboardGuard.close();
            clipboardGuard = null;
        }
        if (systemTrayController != null) {
            systemTrayController.close();
            systemTrayController = null;
        }
        if (singleInstanceCoordinator != null) {
            singleInstanceCoordinator.close();
            singleInstanceCoordinator = null;
        }
        if (themeManager != null) {
            themeManager.close();
            themeManager = null;
        }
        authExecutor.shutdownNow();
    }

    @Override
    public void stop() {
        shutdownCleanly();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
