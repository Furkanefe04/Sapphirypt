package com.sapphircrypt.sapphirypt;

import com.sapphircrypt.sapphirypt.storage.AppSettings;
import com.sapphircrypt.sapphirypt.storage.TempPreviewManager;
import com.sapphircrypt.sapphirypt.storage.VaultSession;
import com.sapphircrypt.sapphirypt.storage.VaultSession.VaultCreationResult;
import com.sapphircrypt.sapphirypt.ui.AutoLockMonitor;
import com.sapphircrypt.sapphirypt.ui.SapphireIcons;
import com.sapphircrypt.sapphirypt.ui.SplashScreen;
import com.sapphircrypt.sapphirypt.ui.ThemeManager;
import com.sapphircrypt.sapphirypt.ui.UiShell;
import com.sapphircrypt.sapphirypt.ui.UiShell.ToastType;
import com.sapphircrypt.sapphirypt.ui.VaultDrawers;
import com.sapphircrypt.sapphirypt.ui.VaultView;
import com.sapphircrypt.sapphirypt.ui.WelcomeView;
import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
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
    private volatile boolean shuttingDown;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        stage.initStyle(StageStyle.TRANSPARENT);
        var appIcons = SapphireIcons.getApplicationIcons();
        if (!appIcons.isEmpty()) {
            stage.getIcons().setAll(appIcons);
        }

        AppSettings preloadedSettings = loadSettingsSafely();
        boolean initialDark = preloadedSettings.themeMode().isDarkAt(LocalTime.now());
        this.splashScreen = new SplashScreen(initialDark);
        this.splashScreen.show();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (activeSession != null) activeSession.close();
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
                    uiShell.showErrorOverlay("Başlatma Hatası", "Uygulama başlatılırken bir hata oluştu.", ex);
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

        stage.setTitle("Sapphirypt");
        stage.setMinWidth(940);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.setOnCloseRequest(ev -> shutdownCleanly());
    }

    private void showMainWindowAndHandleArgs() {
        if (primaryStage == null || scene == null || welcomeView == null) {
            return;
        }
        primaryStage.show();
        welcomeView.playEntranceAnimation();

        List<String> rawArgs = getParameters().getRaw();
        if (!rawArgs.isEmpty()) {
            Path argPath = Path.of(rawArgs.get(0));
            if (Files.isRegularFile(argPath)) Platform.runLater(() -> handleOpenVault(argPath));
        }
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
                                    "Kasa Oluşturuldu",
                                    "Kurtarma anahtarı kaydedildi: " + created.recoveryKeyPath().getFileName());
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
                            uiShell.showToast(ToastType.SUCCESS, "Kasa Açıldı", session.vaultPath().getFileName().toString());
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
                        uiShell.showToast(ToastType.SUCCESS, "Kasa Adı Değiştirildi", newPath.getFileName().toString());
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
                        uiShell.showToast(ToastType.WARNING, "Kasa Kalıcı Silindi", targetPath.getFileName().toString());
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> callback.onFailure(ex.getMessage()));
                }
            });
        });
    }

    private void handleOpenSettings() {
        VaultDrawers.showSettingsDrawer(uiShell, settings, themeManager, activeSession, autoLockMonitor, () -> {
            if (activeVaultView != null) {
                activeVaultView.refreshBadges();
            }
            if (welcomeView != null) {
                welcomeView.refreshRecentVaults();
            }
        });
    }

    private void activateSession(VaultSession session) {
        lockActiveVaultInternal(false);
        activeSession = session;
        activeVaultPath = session.vaultPath();
        settings.addRecentVault(activeVaultPath);
        settings.save();
        welcomeView.refreshRecentVaults();

        autoLockMonitor.resetActivity();
        activeVaultView = new VaultView(
                session,
                settings,
                uiShell,
                themeManager,
                autoLockMonitor,
                this::handleActiveVaultRenamed,
                this::handleActiveVaultDeleted);

        Button securityBtn = new Button("Güvenlik");
        securityBtn.getStyleClass().add("btn-ghost");
        securityBtn.setGraphic(SapphireIcons.shield(14, uiShell.accentIconColor()));
        securityBtn.setFocusTraversable(false);
        securityBtn.setOnAction(e -> {
            if (activeVaultView != null) {
                activeVaultView.openSecurityDrawer();
            }
        });

        Button lockBtn = new Button("Kilitle");
        lockBtn.getStyleClass().add("btn-danger");
        lockBtn.setGraphic(SapphireIcons.lock(14, "#F87171"));
        lockBtn.setFocusTraversable(false);
        lockBtn.setOnAction(e -> lockActiveVault());

        com.sapphircrypt.sapphirypt.ui.MotionFx.attachPressSpring(securityBtn);
        com.sapphircrypt.sapphirypt.ui.MotionFx.attachPressSpring(lockBtn);
        uiShell.setContextActions(securityBtn, lockBtn);
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
        welcomeView.refreshRecentVaults();
        uiShell.closeDrawer(true);
        uiShell.setContextActions();
        uiShell.setContentAnimated(welcomeView);
        welcomeView.playEntranceAnimation();
        uiShell.setTitleText("Sapphirypt");
        uiShell.showToast(
                ToastType.WARNING,
                "Kasa Silindi",
                deletedPath != null ? deletedPath.getFileName().toString() : "Açık kasa kalıcı olarak silindi.");
    }

    private void lockActiveVault() {
        lockActiveVaultInternal(true);
    }

    private void lockActiveVaultInternal(boolean showToast) {
        boolean wasUnlocked = activeSession != null || activeVaultView != null;
        if (activeVaultView != null) {
            activeVaultView.close();
            activeVaultView = null;
        }
        if (activeSession != null) {
            activeSession.close();
            activeSession = null;
        }
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
                    uiShell.showToast(ToastType.INFO, "Kasa Kilitlendi", "Oturum anahtarları bellekten temizlendi.");
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
        }
        if (autoLockMonitor != null) {
            autoLockMonitor.close();
            autoLockMonitor = null;
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
