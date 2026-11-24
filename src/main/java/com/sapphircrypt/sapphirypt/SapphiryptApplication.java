package com.sapphircrypt.sapphirypt;

import com.sapphircrypt.sapphirypt.storage.AppSettings;
import com.sapphircrypt.sapphirypt.storage.TempPreviewManager;
import com.sapphircrypt.sapphirypt.storage.VaultSession;
import com.sapphircrypt.sapphirypt.storage.VaultSession.VaultCreationResult;
import com.sapphircrypt.sapphirypt.ui.AutoLockMonitor;
import com.sapphircrypt.sapphirypt.ui.SapphireIcons;
import com.sapphircrypt.sapphirypt.ui.SplashScreen;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.CreateVaultRequest;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.DeleteVaultRequest;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.OpenVaultRequest;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.RenameVaultRequest;
import com.sapphircrypt.sapphirypt.ui.VaultView;
import com.sapphircrypt.sapphirypt.ui.WelcomeView;
import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
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
    private Stage primaryStage;
    private Scene scene;
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
        var appIcons = SapphireIcons.getApplicationIcons();
        if (!appIcons.isEmpty()) {
            stage.getIcons().setAll(appIcons);
        }

        this.splashScreen = new SplashScreen();
        this.splashScreen.show();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (activeSession != null) activeSession.close();
        }, "sapphirypt-shutdown"));

        Thread startupThread = new Thread(() -> runStartupSequence(stage), "sapphirypt-startup");
        startupThread.setDaemon(true);
        startupThread.start();
    }

    private void runStartupSequence(Stage stage) {
        long startedAtNanos = System.nanoTime();
        try {
            updateSplashProgress(0.25, "Güvenlik bileşenleri hazırlanıyor...");
            TempPreviewManager.sweepOrphanTempFiles();
            new SecureRandom().nextInt();
            Argon2BytesGenerator.class.getName();
            if (shuttingDown) {
                return;
            }

            updateSplashProgress(0.55, "Uygulama ayarları yükleniyor...");
            AppSettings loadedSettings = loadSettingsSafely();
            if (shuttingDown) {
                return;
            }

            updateSplashProgress(0.85, "Arayüz hazırlanıyor...");
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

            updateSplashProgress(1.0, "Hazır");
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
                VaultDialogs.showError(stage, "Başlatma Hatası", "Uygulama başlatılırken bir hata oluştu.", ex);
            });
        }
    }

    private void updateSplashProgress(double targetProgress, String message) {
        SplashScreen activeSplash = splashScreen;
        if (activeSplash != null && !shuttingDown) {
            activeSplash.updateProgress(targetProgress, message);
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
        welcomeView = new WelcomeView(
                settings,
                this::handleCreateVault,
                this::handleOpenVault,
                this::handleRenameClosedVault,
                this::handleDeleteClosedVault,
                this::handleOpenWelcomeSettings);

        scene = new Scene(welcomeView, 1180, 760);
        var cssUrl = SapphiryptApplication.class.getResource(CSS_PATH);
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        autoLockMonitor = new AutoLockMonitor(scene, settings.autoLockMinutes(), this::lockActiveVault);

        stage.setTitle("Sapphirypt — Şifreli Kasa");
        stage.setMinWidth(920);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.setOnCloseRequest(ev -> shutdownCleanly());
    }

    private void showMainWindowAndHandleArgs() {
        if (primaryStage == null || scene == null || welcomeView == null) {
            return;
        }
        welcomeView.setOpacity(0.0);
        primaryStage.show();

        FadeTransition fadeIn = new FadeTransition(Duration.millis(220), welcomeView);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.play();

        // Support opening a .sapphirypt file passed as CLI argument (double-click association)
        List<String> rawArgs = getParameters().getRaw();
        if (!rawArgs.isEmpty()) {
            Path argPath = Path.of(rawArgs.get(0));
            if (Files.isRegularFile(argPath)) Platform.runLater(() -> handleOpenVault(argPath));
        }
    }

    private void handleCreateVault() {
        Optional<CreateVaultRequest> reqOpt = VaultDialogs.showCreateVaultDialog(primaryStage);
        if (reqOpt.isEmpty()) return;

        CreateVaultRequest req = reqOpt.get();
        welcomeView.setBusy(true, "Argon2id anahtarı türetiliyor ve kasa oluşturuluyor...");

        authExecutor.submit(() -> {
            try (req) {
                VaultCreationResult created = VaultSession.createVault(
                        req.vaultPath, req.password, req.argon2Params, req.recoveryKeyPath,
                        settings.cpuProfile(), settings.maxWorkersOverride());
                Platform.runLater(() -> {
                    welcomeView.setBusy(false, "");
                    activateSession(created.session());
                    VaultDialogs.showInfo(
                            primaryStage, "Kasa Oluşturuldu", "Yeni kasanız başarıyla oluşturuldu ve kilidi açıldı.",
                            "Kurtarma anahtarı kaydedildi: " + created.recoveryKeyPath()
                                    + "\nBu .key dosyasını kasadan ayrı ve güvenli bir yerde saklayın.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    welcomeView.setBusy(false, "");
                    VaultDialogs.showError(primaryStage, "Kasa Oluşturulamadı", ex.getMessage(), ex);
                });
            }
        });
    }

    private void handleOpenVault(Path preselectedPath) {
        Optional<OpenVaultRequest> reqOpt = VaultDialogs.showOpenVaultDialog(primaryStage, preselectedPath);
        if (reqOpt.isEmpty()) {
            return;
        }
        OpenVaultRequest req = reqOpt.get();
        welcomeView.setBusy(true, req.usingRecoveryKey
                ? "Kurtarma anahtarı ile kasa doğrulanıyor..."
                : "Argon2id ile parola doğrulanıyor ve kasa açılıyor...");

        authExecutor.submit(() -> {
            try (req) {
                VaultSession session = req.usingRecoveryKey
                        ? VaultSession.openWithRecoveryKey(
                                req.vaultPath, req.recoveryKeyPath, settings.cpuProfile(), settings.maxWorkersOverride())
                        : VaultSession.openWithPassword(
                                req.vaultPath, req.password, settings.cpuProfile(), settings.maxWorkersOverride());
                Platform.runLater(() -> {
                    welcomeView.setBusy(false, "");
                    activateSession(session);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    welcomeView.setBusy(false, "");
                    VaultDialogs.showError(primaryStage, "Kasa Açılamadı", ex.getMessage(), ex);
                });
            }
        });
    }

    private void handleRenameClosedVault(Path preselectedPath) {
        Optional<RenameVaultRequest> reqOpt = VaultDialogs.showRenameVaultDialog(primaryStage, preselectedPath);
        if (reqOpt.isEmpty()) {
            return;
        }
        RenameVaultRequest req = reqOpt.get();
        Path oldPath = req.vaultPath.toAbsolutePath().normalize();
        welcomeView.setBusy(true, "Parola ve kurtarma anahtarı doğrulanıyor, kasa adı değiştiriliyor...");

        authExecutor.submit(() -> {
            try (req) {
                Path newPath = VaultSession.renameVault(req.vaultPath, req.newVaultName, req.password, req.recoveryKeyPath);
                Platform.runLater(() -> {
                    welcomeView.setBusy(false, "");
                    settings.removeRecentVault(oldPath);
                    settings.addRecentVault(newPath);
                    settings.save();
                    welcomeView.refreshRecentVaults();
                    VaultDialogs.showInfo(
                            primaryStage,
                            "Kasa Adı Değiştirildi",
                            "Kasa başarıyla yeniden adlandırıldı.",
                            "Yeni konum: " + newPath);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    welcomeView.setBusy(false, "");
                    VaultDialogs.showError(primaryStage, "Kasa Adı Değiştirilemedi", ex.getMessage(), ex);
                });
            }
        });
    }

    private void handleDeleteClosedVault(Path preselectedPath) {
        Optional<DeleteVaultRequest> reqOpt = VaultDialogs.showDeleteVaultDialog(primaryStage, preselectedPath);
        if (reqOpt.isEmpty()) {
            return;
        }
        DeleteVaultRequest req = reqOpt.get();
        Path targetPath = req.vaultPath.toAbsolutePath().normalize();
        welcomeView.setBusy(true, "Parola ve kurtarma anahtarı doğrulanıyor, kasa siliniyor...");

        authExecutor.submit(() -> {
            try (req) {
                VaultSession.deleteVault(req.vaultPath, req.password, req.recoveryKeyPath, req.deleteRecoveryKeyFile);
                Platform.runLater(() -> {
                    welcomeView.setBusy(false, "");
                    settings.removeRecentVault(targetPath);
                    settings.save();
                    welcomeView.refreshRecentVaults();
                    VaultDialogs.showInfo(
                            primaryStage,
                            "Kasa Silindi",
                            "Kasa kalıcı olarak silindi.",
                            "Silinen dosya: " + targetPath);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    welcomeView.setBusy(false, "");
                    VaultDialogs.showError(primaryStage, "Kasa Silinemedi", ex.getMessage(), ex);
                });
            }
        });
    }

    private void handleOpenWelcomeSettings() {
        VaultDialogs.showSettingsDialog(primaryStage, settings, null, autoLockMonitor, null);
    }

    private void activateSession(VaultSession session) {
        lockActiveVault();
        activeSession = session;
        activeVaultPath = session.vaultPath();
        settings.addRecentVault(activeVaultPath);
        settings.save();
        welcomeView.refreshRecentVaults();

        autoLockMonitor.resetActivity();
        activeVaultView = new VaultView(
                session,
                settings,
                autoLockMonitor,
                this::lockActiveVault,
                this::handleActiveVaultRenamed,
                this::handleActiveVaultDeleted);
        scene.setRoot(activeVaultView);
        primaryStage.setTitle("Sapphirypt — " + activeVaultPath.getFileName());
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
        primaryStage.setTitle("Sapphirypt — " + newPath.getFileName());
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
        scene.setRoot(welcomeView);
        primaryStage.setTitle("Sapphirypt — Şifreli Kasa");
        VaultDialogs.showInfo(
                primaryStage,
                "Kasa Silindi",
                "Açık kasa kalıcı olarak silindi.",
                deletedPath != null ? "Silinen dosya: " + deletedPath : "");
    }

    private void lockActiveVault() {
        if (activeVaultView != null) {
            activeVaultView.close();
            activeVaultView = null;
        }
        if (activeSession != null) {
            activeSession.close();
            activeSession = null;
        }
        activeVaultPath = null;
        if (scene != null && welcomeView != null && scene.getRoot() != welcomeView) {
            welcomeView.refreshRecentVaults();
            scene.setRoot(welcomeView);
            primaryStage.setTitle("Sapphirypt — Şifreli Kasa");
        }
    }

    private void shutdownCleanly() {
        shuttingDown = true;
        if (splashScreen != null) {
            splashScreen.closeImmediately();
            splashScreen = null;
        }
        lockActiveVault();
        if (autoLockMonitor != null) {
            autoLockMonitor.close();
            autoLockMonitor = null;
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
