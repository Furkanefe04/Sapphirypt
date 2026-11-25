package com.sapphircrypt.sapphirypt.ui;

import com.sapphircrypt.sapphirypt.crypto.Argon2Params;
import com.sapphircrypt.sapphirypt.crypto.Argon2Params.Argon2Preset;
import com.sapphircrypt.sapphirypt.crypto.SensitiveBytes;
import com.sapphircrypt.sapphirypt.format.VaultConstants;
import com.sapphircrypt.sapphirypt.storage.AppSettings;
import com.sapphircrypt.sapphirypt.storage.BlockCache;
import com.sapphircrypt.sapphirypt.storage.CpuProfile;
import com.sapphircrypt.sapphirypt.storage.ThemeMode;
import com.sapphircrypt.sapphirypt.storage.VaultException;
import com.sapphircrypt.sapphirypt.storage.VaultSession;
import com.sapphircrypt.sapphirypt.ui.UiShell.ToastType;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.CreateVaultRequest;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.DeleteVaultRequest;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.OpenVaultRequest;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.RenameVaultRequest;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Modern slide-over right drawer workflows for creating/opening vaults, security & vault lifecycle
 * management, and theme/performance settings.
 */
public final class VaultDrawers {

    private VaultDrawers() {
    }

    public interface DrawerTaskCallback {
        void onSuccess();

        void onFailure(String errorMessage);
    }

    /**
     * Reusable password field with synchronized eye toggle button to reveal/hide characters cleanly.
     */
    private static final class PasswordInputRow extends HBox {
        private final PasswordField maskedField = new PasswordField();
        private final TextField plainField = new TextField();
        private final Button eyeBtn = new Button();
        private final ThemeManager themeManager;
        private boolean revealed;

        PasswordInputRow(String prompt, ThemeManager themeManager) {
            super(6);
            this.themeManager = themeManager;
            setAlignment(Pos.CENTER_LEFT);

            maskedField.setPromptText(prompt);
            plainField.setPromptText(prompt);
            plainField.textProperty().bindBidirectional(maskedField.textProperty());
            plainField.setVisible(false);
            plainField.setManaged(false);

            StackPane fieldStack = new StackPane(maskedField, plainField);
            HBox.setHgrow(fieldStack, Priority.ALWAYS);

            eyeBtn.getStyleClass().add("icon-btn");
            eyeBtn.setFocusTraversable(false);
            eyeBtn.setTooltip(new Tooltip(I18n.t("Parolayı Göster / Gizle", "Show / Hide Password")));
            updateEyeIcon();
            eyeBtn.setOnAction(e -> toggleReveal());

            getChildren().addAll(fieldStack, eyeBtn);
        }

        private void toggleReveal() {
            revealed = !revealed;
            plainField.setVisible(revealed);
            plainField.setManaged(revealed);
            maskedField.setVisible(!revealed);
            maskedField.setManaged(!revealed);
            updateEyeIcon();
            if (revealed) {
                plainField.requestFocus();
                plainField.positionCaret(plainField.getText() == null ? 0 : plainField.getText().length());
            } else {
                maskedField.requestFocus();
                maskedField.positionCaret(maskedField.getCharacters().length());
            }
        }

        private void updateEyeIcon() {
            String color = themeManager.isDarkNow() ? "#94A3B8" : "#475569";
            eyeBtn.setGraphic(revealed ? SapphireIcons.eyeOff(15, color) : SapphireIcons.eye(15, color));
        }

        char[] readChars() {
            CharSequence seq = maskedField.getCharacters();
            char[] arr = new char[seq.length()];
            for (int i = 0; i < seq.length(); i++) {
                arr[i] = seq.charAt(i);
            }
            return arr;
        }

        String textValue() {
            return maskedField.getText() == null ? "" : maskedField.getText();
        }

        void clear() {
            maskedField.clear();
            plainField.clear();
        }

        void addTextListener(Runnable onChange) {
            maskedField.textProperty().addListener((o, a, b) -> onChange.run());
        }

        void setOnSubmit(Runnable onEnter) {
            maskedField.setOnAction(e -> onEnter.run());
            plainField.setOnAction(e -> onEnter.run());
        }

        void focusField() {
            Platform.runLater(() -> {
                if (revealed) {
                    plainField.requestFocus();
                } else {
                    maskedField.requestFocus();
                }
            });
        }
    }

    // ========================= CREATE VAULT (2-STEP WIZARD) =========================

    public static void showCreateVaultDrawer(
            UiShell shell,
            Window owner,
            ThemeManager themeManager,
            BiConsumer<CreateVaultRequest, DrawerTaskCallback> onSubmit) {
        AtomicBoolean busy = new AtomicBoolean(false);

        Label step1Pill = new Label(I18n.t("1. Konum & .key", "1. Location & .key"));
        step1Pill.getStyleClass().addAll("stepper-pill", "stepper-pill-active");
        Label step2Pill = new Label(I18n.t("2. Parola & Güvenlik", "2. Password & Security"));
        step2Pill.getStyleClass().add("stepper-pill");
        HBox stepperBar = new HBox(8, step1Pill, SapphireIcons.chevronRight(14, shell.mutedIconColor()), step2Pill);
        stepperBar.setAlignment(Pos.CENTER_LEFT);

        // Step 1 controls
        TextField vaultPathField = new TextField();
        vaultPathField.setPromptText(I18n.t("Örnek: C:\\Kasalar\\kisisel.sapphirypt", "Example: C:\\Vaults\\personal.sapphirypt"));
        Button browseVaultBtn = new Button(I18n.t("Gözat...", "Browse..."));
        TextField keyPathField = new TextField();
        keyPathField.setPromptText(I18n.t("Kurtarma anahtarı (.key) yolu", "Recovery key (.key) path"));
        Button browseKeyBtn = new Button(I18n.t("Gözat...", "Browse..."));

        browseVaultBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("Kasa Dosyası Konumu", "Vault File Location"));
            chooser.getExtensionFilters().add(vaultExtFilter());
            chooser.setInitialFileName(I18n.t("Kasa", "Vault") + VaultConstants.FILE_EXTENSION);
            File chosen = chooser.showSaveDialog(owner);
            if (chosen != null) {
                String pathStr = ensureExtension(chosen.getAbsolutePath(), VaultConstants.FILE_EXTENSION);
                vaultPathField.setText(pathStr);
                if (keyPathField.getText().isBlank()) {
                    keyPathField.setText(deriveDefaultKeyPath(pathStr));
                }
            }
        });

        browseKeyBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("Kurtarma Anahtarı (.key) Konumu", "Recovery Key (.key) Location"));
            chooser.getExtensionFilters().add(keyExtFilter());
            chooser.setInitialFileName(I18n.t("kurtarma", "recovery") + VaultConstants.RECOVERY_KEY_EXTENSION);
            File chosen = chooser.showSaveDialog(owner);
            if (chosen != null) {
                keyPathField.setText(ensureExtension(chosen.getAbsolutePath(), VaultConstants.RECOVERY_KEY_EXTENSION));
            }
        });

        Label tipBox = new Label(I18n.t(
                "Kurtarma anahtarı (.key) dosyasını kasa dosyasından farklı bir USB veya yedek klasöre kaydetmeniz önerilir.",
                "Store your recovery key (.key) in a separate USB drive or backup folder from your vault file."));
        tipBox.getStyleClass().add("warning-badge");
        tipBox.setWrapText(true);
        tipBox.setMaxWidth(Double.MAX_VALUE);

        VBox step1Pane = new VBox(12,
                fieldLabel(I18n.t("Kasa Dosyası (.sapphirypt)", "Vault File (.sapphirypt)")),
                pickerRow(vaultPathField, browseVaultBtn),
                fieldLabel(I18n.t("Kurtarma Anahtarı (.key)", "Recovery Key (.key)")),
                pickerRow(keyPathField, browseKeyBtn),
                tipBox);

        // Step 2 controls
        PasswordInputRow pwInput = new PasswordInputRow(I18n.t("Güçlü bir parola girin", "Enter a strong password"), themeManager);
        PasswordInputRow confirmInput = new PasswordInputRow(I18n.t("Parolayı tekrar girin", "Confirm password"), themeManager);

        ProgressBar strengthBar = new ProgressBar(0.0);
        strengthBar.setMaxWidth(Double.MAX_VALUE);
        strengthBar.setPrefHeight(6);
        Label strengthLbl = new Label(I18n.t("Parola gücü: -", "Password strength: -"));
        strengthLbl.getStyleClass().add("muted-text");
        strengthLbl.setStyle("-fx-font-size: 11.5px;");

        pwInput.addTextListener(() -> updateStrengthMeter(pwInput.textValue(), strengthBar, strengthLbl));

        ComboBox<Argon2Preset> kdfCombo = new ComboBox<>();
        kdfCombo.getItems().addAll(Argon2Params.availablePresets());
        kdfCombo.getSelectionModel().selectFirst();
        kdfCombo.setMaxWidth(Double.MAX_VALUE);
        configurePresetCombo(kdfCombo);

        VBox step2Pane = new VBox(12,
                fieldLabel(I18n.t("Kasa Parolası", "Vault Password")),
                pwInput,
                fieldLabel(I18n.t("Parola Tekrar", "Confirm Password")),
                confirmInput,
                new VBox(5, strengthBar, strengthLbl),
                fieldLabel(I18n.t("Güvenlik Seviyesi", "Security Level")),
                kdfCombo);
        step2Pane.setVisible(false);
        step2Pane.setManaged(false);

        Label errorBanner = createErrorBanner();
        HBox busyRow = createBusyRow(I18n.t("Kasa oluşturuluyor...", "Creating vault..."));

        VBox body = new VBox(16, stepperBar, step1Pane, step2Pane, errorBanner, busyRow);

        // Footer controls
        Button cancelBtn = new Button(I18n.t("İptal", "Cancel"));
        Button backBtn = new Button(I18n.t("← Geri", "← Back"));
        backBtn.setVisible(false);
        backBtn.setManaged(false);
        Button primaryBtn = new Button(I18n.t("İleri →", "Next →"));
        primaryBtn.getStyleClass().add("btn-primary");
        MotionFx.attachPressSpring(primaryBtn);

        Region fSpacer = new Region();
        HBox.setHgrow(fSpacer, Priority.ALWAYS);
        HBox footer = new HBox(10, cancelBtn, fSpacer, backBtn, primaryBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);

        AtomicBoolean onStep2 = new AtomicBoolean(false);
        Runnable switchToStep1 = () -> {
            onStep2.set(false);
            clearError(errorBanner);
            step1Pane.setVisible(true);
            step1Pane.setManaged(true);
            step2Pane.setVisible(false);
            step2Pane.setManaged(false);
            step1Pill.getStyleClass().add("stepper-pill-active");
            step2Pill.getStyleClass().remove("stepper-pill-active");
            backBtn.setVisible(false);
            backBtn.setManaged(false);
            primaryBtn.setText(I18n.t("İleri →", "Next →"));
            MotionFx.playSlideSwitch(step1Pane, -24.0, 220.0);
        };
        Runnable switchToStep2 = () -> {
            clearError(errorBanner);
            String vPath = vaultPathField.getText().trim();
            String kPath = keyPathField.getText().trim();
            if (vPath.isEmpty()) {
                showError(errorBanner, I18n.t("Lütfen bir kasa dosya yolu seçin.", "Please select a vault file path."));
                return;
            }
            if (kPath.isEmpty()) {
                showError(errorBanner, I18n.t("Lütfen kurtarma anahtarı (.key) dosya yolu seçin.", "Please select a recovery key (.key) path."));
                return;
            }
            onStep2.set(true);
            step1Pane.setVisible(false);
            step1Pane.setManaged(false);
            step2Pane.setVisible(true);
            step2Pane.setManaged(true);
            step1Pill.getStyleClass().remove("stepper-pill-active");
            step2Pill.getStyleClass().add("stepper-pill-active");
            backBtn.setVisible(true);
            backBtn.setManaged(true);
            primaryBtn.setText(I18n.t("Kasayı Oluştur", "Create Vault"));
            MotionFx.playSlideSwitch(step2Pane, 24.0, 220.0);
            pwInput.focusField();
        };

        cancelBtn.setOnAction(e -> shell.closeDrawer());
        backBtn.setOnAction(e -> switchToStep1.run());

        Runnable submitStep2 = () -> {
            clearError(errorBanner);
            char[] pw = pwInput.readChars();
            char[] cf = confirmInput.readChars();
            if (pw.length == 0) {
                SensitiveBytes.wipe(pw);
                SensitiveBytes.wipe(cf);
                showError(errorBanner, I18n.t("Parola boş bırakılamaz.", "Password cannot be empty."));
                return;
            }
            if (!Arrays.equals(pw, cf)) {
                SensitiveBytes.wipe(pw);
                SensitiveBytes.wipe(cf);
                showError(errorBanner, I18n.t("Parolalar birbiriyle eşleşmiyor.", "Passwords do not match."));
                return;
            }
            SensitiveBytes.wipe(cf);

            Argon2Preset preset = kdfCombo.getValue();
            if (preset == null || !preset.available()) {
                SensitiveBytes.wipe(pw);
                showError(errorBanner, I18n.t("Seçilen güvenlik seviyesi bu cihazda kullanılamıyor.", "Selected security level is unavailable on this device."));
                return;
            }

            Path vaultPath = Path.of(ensureExtension(vaultPathField.getText().trim(), VaultConstants.FILE_EXTENSION));
            Path keyPath = Path.of(ensureExtension(keyPathField.getText().trim(), VaultConstants.RECOVERY_KEY_EXTENSION));

            setBusy(busy, true, busyRow, primaryBtn, backBtn, cancelBtn);
            CreateVaultRequest request = new CreateVaultRequest(vaultPath, keyPath, pw, preset.params());
            onSubmit.accept(request, new DrawerTaskCallback() {
                @Override
                public void onSuccess() {
                    pwInput.clear();
                    confirmInput.clear();
                    setBusy(busy, false, busyRow, primaryBtn, backBtn, cancelBtn);
                    shell.closeDrawer(true);
                }

                @Override
                public void onFailure(String errorMessage) {
                    setBusy(busy, false, busyRow, primaryBtn, backBtn, cancelBtn);
                    showError(errorBanner, errorMessage);
                }
            });
        };

        primaryBtn.setOnAction(e -> {
            if (!onStep2.get()) {
                switchToStep2.run();
            } else {
                submitStep2.run();
            }
        });
        confirmInput.setOnSubmit(() -> {
            if (onStep2.get() && !busy.get()) {
                submitStep2.run();
            }
        });

        shell.openDrawer(
                I18n.t("Yeni Kasa Oluştur", "Create New Vault"),
                I18n.t("Şifreli .sapphirypt kasası ve .key kurtarma anahtarı tanımlayın", "Set up an encrypted .sapphirypt vault and .key recovery file"),
                body,
                footer,
                () -> !busy.get(),
                () -> {
                    pwInput.clear();
                    confirmInput.clear();
                });
    }

    // ========================= OPEN VAULT (SEGMENTED SWITCH) =========================

    public static void showOpenVaultDrawer(
            UiShell shell,
            Window owner,
            ThemeManager themeManager,
            Path preselectedVault,
            BiConsumer<OpenVaultRequest, DrawerTaskCallback> onSubmit) {
        AtomicBoolean busy = new AtomicBoolean(false);
        AtomicBoolean useRecoveryKey = new AtomicBoolean(false);

        TextField vaultPathField = new TextField(preselectedVault == null ? "" : preselectedVault.toString());
        vaultPathField.setPromptText(I18n.t("Açılacak .sapphirypt kasa dosyası", "Vault file (.sapphirypt) to open"));
        Button browseVaultBtn = new Button(I18n.t("Gözat...", "Browse..."));
        browseVaultBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("Kasa Dosyası Seç", "Select Vault File"));
            chooser.getExtensionFilters().add(vaultExtFilter());
            File f = chooser.showOpenDialog(owner);
            if (f != null) {
                vaultPathField.setText(f.getAbsolutePath());
            }
        });

        Button passwordModeBtn = new Button(I18n.t("Parola ile", "With Password"));
        passwordModeBtn.getStyleClass().add("segmented-btn");
        passwordModeBtn.setMaxWidth(Double.MAX_VALUE);
        Button keyModeBtn = new Button(I18n.t("Kurtarma Anahtarı (.key)", "Recovery Key (.key)"));
        keyModeBtn.getStyleClass().add("segmented-btn");
        keyModeBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(passwordModeBtn, Priority.ALWAYS);
        HBox.setHgrow(keyModeBtn, Priority.ALWAYS);

        MotionFx.SegmentedSwitch modeSwitch = new MotionFx.SegmentedSwitch(4, passwordModeBtn, keyModeBtn);
        modeSwitch.setActive(passwordModeBtn, false);

        PasswordInputRow pwInput = new PasswordInputRow(I18n.t("Kasa parolasını girin", "Enter vault password"), themeManager);
        VBox pwSection = new VBox(8, fieldLabel(I18n.t("Kasa Parolası", "Vault Password")), pwInput);

        TextField keyPathField = new TextField();
        keyPathField.setPromptText(I18n.t(".key kurtarma dosyasını seçin", "Select .key recovery file"));
        Button browseKeyBtn = new Button(I18n.t("Gözat...", "Browse..."));
        browseKeyBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("Kurtarma Anahtarı (.key) Seç", "Select Recovery Key (.key)"));
            chooser.getExtensionFilters().add(keyExtFilter());
            File f = chooser.showOpenDialog(owner);
            if (f != null) {
                keyPathField.setText(f.getAbsolutePath());
            }
        });
        VBox keySection = new VBox(8, fieldLabel(I18n.t("Kurtarma Anahtarı (.key)", "Recovery Key (.key)")), pickerRow(keyPathField, browseKeyBtn));
        keySection.setVisible(false);
        keySection.setManaged(false);

        Label errorBanner = createErrorBanner();
        HBox busyRow = createBusyRow(I18n.t("Kasa doğrulanıyor ve açılıyor...", "Verifying and opening vault..."));

        VBox body = new VBox(16,
                fieldLabel(I18n.t("Kasa Dosyası (.sapphirypt)", "Vault File (.sapphirypt)")),
                pickerRow(vaultPathField, browseVaultBtn),
                modeSwitch,
                pwSection,
                keySection,
                errorBanner,
                busyRow);

        passwordModeBtn.setOnAction(e -> {
            boolean wasKey = useRecoveryKey.getAndSet(false);
            modeSwitch.setActive(passwordModeBtn, wasKey);
            pwSection.setVisible(true);
            pwSection.setManaged(true);
            keySection.setVisible(false);
            keySection.setManaged(false);
            clearError(errorBanner);
            if (wasKey) {
                MotionFx.playSlideSwitch(pwSection, -18.0, 200.0);
            }
            pwInput.focusField();
        });
        keyModeBtn.setOnAction(e -> {
            boolean wasPw = !useRecoveryKey.getAndSet(true);
            modeSwitch.setActive(keyModeBtn, wasPw);
            pwSection.setVisible(false);
            pwSection.setManaged(false);
            keySection.setVisible(true);
            keySection.setManaged(true);
            clearError(errorBanner);
            if (wasPw) {
                MotionFx.playSlideSwitch(keySection, 18.0, 200.0);
            }
        });

        Button cancelBtn = new Button(I18n.t("İptal", "Cancel"));
        Button unlockBtn = new Button(I18n.t("Kilidi Aç", "Unlock"));
        unlockBtn.getStyleClass().add("btn-primary");
        MotionFx.attachPressSpring(unlockBtn);
        Region fSpacer = new Region();
        HBox.setHgrow(fSpacer, Priority.ALWAYS);
        HBox footer = new HBox(10, cancelBtn, fSpacer, unlockBtn);

        cancelBtn.setOnAction(e -> shell.closeDrawer());

        Runnable submitOpen = () -> {
            clearError(errorBanner);
            String vStr = vaultPathField.getText().trim();
            if (vStr.isEmpty() || !Files.isRegularFile(Path.of(vStr))) {
                showError(errorBanner, I18n.t("Lütfen geçerli bir .sapphirypt kasa dosyası seçin.", "Please select a valid .sapphirypt vault file."));
                return;
            }
            Path vaultPath = Path.of(vStr);
            OpenVaultRequest request;
            if (useRecoveryKey.get()) {
                String kStr = keyPathField.getText().trim();
                if (kStr.isEmpty() || !Files.isRegularFile(Path.of(kStr))) {
                    showError(errorBanner, I18n.t("Lütfen geçerli bir .key kurtarma dosyası seçin.", "Please select a valid .key recovery file."));
                    return;
                }
                request = new OpenVaultRequest(vaultPath, true, null, Path.of(kStr));
            } else {
                char[] pw = pwInput.readChars();
                if (pw.length == 0) {
                    SensitiveBytes.wipe(pw);
                    showError(errorBanner, I18n.t("Lütfen kasa parolasını girin.", "Please enter the vault password."));
                    return;
                }
                request = new OpenVaultRequest(vaultPath, false, pw, null);
            }

            setBusy(busy, true, busyRow, unlockBtn, cancelBtn);
            onSubmit.accept(request, new DrawerTaskCallback() {
                @Override
                public void onSuccess() {
                    pwInput.clear();
                    setBusy(busy, false, busyRow, unlockBtn, cancelBtn);
                    shell.closeDrawer(true);
                }

                @Override
                public void onFailure(String errorMessage) {
                    setBusy(busy, false, busyRow, unlockBtn, cancelBtn);
                    showError(errorBanner, errorMessage);
                }
            });
        };

        unlockBtn.setOnAction(e -> submitOpen.run());
        pwInput.setOnSubmit(() -> {
            if (!busy.get()) {
                submitOpen.run();
            }
        });

        shell.openDrawer(
                I18n.t("Kasa Aç", "Open Vault"),
                I18n.t("Parolanız veya kurtarma anahtarınız ile kasayı çözün", "Unlock with your password or recovery key"),
                body,
                footer,
                () -> !busy.get(),
                pwInput::clear);

        if (preselectedVault != null) {
            pwInput.focusField();
        }
    }

    // ========================= RENAME / DELETE CLOSED VAULT =========================

    public static void showRenameClosedVaultDrawer(
            UiShell shell,
            Window owner,
            ThemeManager themeManager,
            Path preselectedVault,
            BiConsumer<RenameVaultRequest, DrawerTaskCallback> onSubmit) {
        AtomicBoolean busy = new AtomicBoolean(false);

        TextField vaultField = new TextField(preselectedVault == null ? "" : preselectedVault.toString());
        vaultField.setPromptText(I18n.t("Adı değiştirilecek .sapphirypt kasa dosyası", ".sapphirypt vault file to rename"));
        TextField newNameField = new TextField(preselectedVault == null ? "" : stripVaultExtension(preselectedVault));
        newNameField.setPromptText(I18n.t("Yeni kasa adı (uzantısız veya .sapphirypt)", "New vault name"));

        Button browseVaultBtn = new Button(I18n.t("Gözat...", "Browse..."));
        browseVaultBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("Kasa Dosyası Seç", "Select Vault File"));
            chooser.getExtensionFilters().add(vaultExtFilter());
            File f = chooser.showOpenDialog(owner);
            if (f != null) {
                vaultField.setText(f.getAbsolutePath());
                newNameField.setText(stripVaultExtension(f.toPath()));
            }
        });

        PasswordInputRow pwInput = new PasswordInputRow(I18n.t("Kasa parolasını girin", "Enter vault password"), themeManager);
        TextField keyField = new TextField();
        keyField.setPromptText(I18n.t("Bu kasaya ait .key kurtarma dosyası", ".key recovery file for this vault"));
        Button browseKeyBtn = new Button(I18n.t("Gözat...", "Browse..."));
        browseKeyBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("Kurtarma Anahtarı Seç", "Select Recovery Key"));
            chooser.getExtensionFilters().add(keyExtFilter());
            File f = chooser.showOpenDialog(owner);
            if (f != null) {
                keyField.setText(f.getAbsolutePath());
            }
        });

        Label infoNote = new Label(I18n.t(
                "Güvenlik gereği kasa adını değiştirmek için hem parola hem .key dosyası doğrulanır.",
                "For security, both password and .key file are verified before renaming."));
        infoNote.getStyleClass().add("sapphire-badge");
        infoNote.setWrapText(true);
        infoNote.setMaxWidth(Double.MAX_VALUE);

        Label errorBanner = createErrorBanner();
        HBox busyRow = createBusyRow(I18n.t("Parola ve .key doğrulanıyor, kasa yeniden adlandırılıyor...", "Verifying password and .key, renaming vault..."));

        VBox body = new VBox(12,
                fieldLabel(I18n.t("Kasa Dosyası (.sapphirypt)", "Vault File (.sapphirypt)")), pickerRow(vaultField, browseVaultBtn),
                fieldLabel(I18n.t("Yeni Kasa Adı", "New Vault Name")), newNameField,
                fieldLabel(I18n.t("Kasa Parolası", "Vault Password")), pwInput,
                fieldLabel(I18n.t("Kurtarma Anahtarı (.key)", "Recovery Key (.key)")), pickerRow(keyField, browseKeyBtn),
                infoNote, errorBanner, busyRow);

        Button cancelBtn = new Button(I18n.t("İptal", "Cancel"));
        Button applyBtn = new Button(I18n.t("Adı Değiştir", "Rename Vault"));
        applyBtn.getStyleClass().add("btn-primary");
        Region fSpacer = new Region();
        HBox.setHgrow(fSpacer, Priority.ALWAYS);
        HBox footer = new HBox(10, cancelBtn, fSpacer, applyBtn);

        cancelBtn.setOnAction(e -> shell.closeDrawer());
        applyBtn.setOnAction(e -> {
            clearError(errorBanner);
            String vStr = vaultField.getText().trim();
            if (vStr.isEmpty() || !Files.isRegularFile(Path.of(vStr))) {
                showError(errorBanner, I18n.t("Lütfen geçerli bir .sapphirypt kasa dosyası seçin.", "Please select a valid .sapphirypt vault file."));
                return;
            }
            String kStr = keyField.getText().trim();
            if (kStr.isEmpty() || !Files.isRegularFile(Path.of(kStr))) {
                showError(errorBanner, I18n.t("Lütfen bu kasaya ait geçerli bir .key kurtarma dosyası seçin.", "Please select a valid .key recovery file for this vault."));
                return;
            }
            char[] pw = pwInput.readChars();
            if (pw.length == 0) {
                SensitiveBytes.wipe(pw);
                showError(errorBanner, I18n.t("Lütfen kasa parolasını girin.", "Please enter the vault password."));
                return;
            }
            String candidate;
            try {
                candidate = VaultSession.validateVaultFileName(newNameField.getText());
            } catch (VaultException | IllegalArgumentException ex) {
                SensitiveBytes.wipe(pw);
                showError(errorBanner, ex.getMessage());
                return;
            }

            setBusy(busy, true, busyRow, applyBtn, cancelBtn);
            RenameVaultRequest req = new RenameVaultRequest(Path.of(vStr), candidate, pw, Path.of(kStr));
            onSubmit.accept(req, new DrawerTaskCallback() {
                @Override
                public void onSuccess() {
                    pwInput.clear();
                    setBusy(busy, false, busyRow, applyBtn, cancelBtn);
                    shell.closeDrawer(true);
                }

                @Override
                public void onFailure(String errorMessage) {
                    setBusy(busy, false, busyRow, applyBtn, cancelBtn);
                    showError(errorBanner, errorMessage);
                }
            });
        });

        shell.openDrawer(
                I18n.t("Kasa Adını Değiştir", "Rename Vault"),
                I18n.t("Parola + .key çift doğrulaması ile kasa dosya adını güncelleyin", "Rename the vault file with Password + .key verification"),
                body,
                footer,
                () -> !busy.get(),
                pwInput::clear);
    }

    public static void showDeleteClosedVaultDrawer(
            UiShell shell,
            Window owner,
            ThemeManager themeManager,
            Path preselectedVault,
            BiConsumer<DeleteVaultRequest, DrawerTaskCallback> onSubmit) {
        AtomicBoolean busy = new AtomicBoolean(false);

        TextField vaultField = new TextField(preselectedVault == null ? "" : preselectedVault.toString());
        vaultField.setPromptText(I18n.t("Silinecek .sapphirypt kasa dosyası", ".sapphirypt vault file to delete"));
        Button browseVaultBtn = new Button(I18n.t("Gözat...", "Browse..."));
        browseVaultBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("Silinecek Kasa Dosyası", "Vault File to Delete"));
            chooser.getExtensionFilters().add(vaultExtFilter());
            File f = chooser.showOpenDialog(owner);
            if (f != null) {
                vaultField.setText(f.getAbsolutePath());
            }
        });

        PasswordInputRow pwInput = new PasswordInputRow(I18n.t("Kasa parolasını girin", "Enter vault password"), themeManager);
        TextField keyField = new TextField();
        keyField.setPromptText(I18n.t("Bu kasaya ait .key kurtarma dosyası", ".key recovery file for this vault"));
        Button browseKeyBtn = new Button(I18n.t("Gözat...", "Browse..."));
        browseKeyBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("Kurtarma Anahtarı Seç", "Select Recovery Key"));
            chooser.getExtensionFilters().add(keyExtFilter());
            File f = chooser.showOpenDialog(owner);
            if (f != null) {
                keyField.setText(f.getAbsolutePath());
            }
        });

        CheckBox deleteKeyCheck = new CheckBox(I18n.t("Seçilen .key kurtarma dosyasını da diskten kalıcı sil", "Also permanently delete the selected .key recovery file"));
        MotionFx.enhanceCheckBox(deleteKeyCheck);

        VBox dangerBox = new VBox(6,
                new Label(I18n.t("Kalıcı Silme Uyarısı", "Permanent Deletion Warning")),
                mutedWrap(I18n.t(
                        "Kasa ve içindeki tüm şifrelenmiş dosyalar geri dönüşsüz olarak silinir. Parola + .key eşleşmezse işlem engellenir.",
                        "The vault and all files inside it will be permanently deleted. Verification requires Password + .key.")));
        dangerBox.getStyleClass().add("danger-card");
        dangerBox.setPadding(new Insets(12));

        Label errorBanner = createErrorBanner();
        HBox busyRow = createBusyRow(I18n.t("Parola ve .key doğrulanıyor, kasa siliniyor...", "Verifying password and .key, deleting vault..."));

        VBox body = new VBox(12,
                fieldLabel(I18n.t("Silinecek Kasa (.sapphirypt)", "Vault to Delete (.sapphirypt)")), pickerRow(vaultField, browseVaultBtn),
                fieldLabel(I18n.t("Kasa Parolası", "Vault Password")), pwInput,
                fieldLabel(I18n.t("Kurtarma Anahtarı (.key)", "Recovery Key (.key)")), pickerRow(keyField, browseKeyBtn),
                deleteKeyCheck,
                dangerBox,
                errorBanner,
                busyRow);

        Button cancelBtn = new Button(I18n.t("Vazgeç", "Cancel"));
        Button deleteBtn = new Button(I18n.t("Kasayı Kalıcı Sil", "Delete Vault Permanently"));
        deleteBtn.getStyleClass().add("btn-danger");
        Region fSpacer = new Region();
        HBox.setHgrow(fSpacer, Priority.ALWAYS);
        HBox footer = new HBox(10, cancelBtn, fSpacer, deleteBtn);

        cancelBtn.setOnAction(e -> shell.closeDrawer());
        deleteBtn.setOnAction(e -> {
            clearError(errorBanner);
            String vStr = vaultField.getText().trim();
            if (vStr.isEmpty() || !Files.isRegularFile(Path.of(vStr))) {
                showError(errorBanner, I18n.t("Lütfen geçerli bir .sapphirypt kasa dosyası seçin.", "Please select a valid .sapphirypt vault file."));
                return;
            }
            String kStr = keyField.getText().trim();
            if (kStr.isEmpty() || !Files.isRegularFile(Path.of(kStr))) {
                showError(errorBanner, I18n.t("Lütfen bu kasaya ait geçerli bir .key kurtarma dosyası seçin.", "Please select a valid .key recovery file for this vault."));
                return;
            }
            char[] pw = pwInput.readChars();
            if (pw.length == 0) {
                SensitiveBytes.wipe(pw);
                showError(errorBanner, I18n.t("Lütfen kasa parolasını girin.", "Please enter the vault password."));
                return;
            }

            Path targetVault = Path.of(vStr);
            shell.showConfirmOverlay(
                    I18n.t("Kasayı Kalıcı Olarak Sil?", "Permanently Delete Vault?"),
                    targetVault.getFileName() + I18n.t(" kasası kalıcı olarak silinecek ve bu işlem geri alınamayacaktır.", " will be permanently deleted and cannot be recovered."),
                    I18n.t("Evet, Kalıcı Sil", "Yes, Delete Permanently"),
                    true,
                    confirmed -> {
                        if (!confirmed) {
                            SensitiveBytes.wipe(pw);
                            return;
                        }
                        setBusy(busy, true, busyRow, deleteBtn, cancelBtn);
                        DeleteVaultRequest req = new DeleteVaultRequest(
                                targetVault, pw, Path.of(kStr), deleteKeyCheck.isSelected());
                        onSubmit.accept(req, new DrawerTaskCallback() {
                            @Override
                            public void onSuccess() {
                                pwInput.clear();
                                setBusy(busy, false, busyRow, deleteBtn, cancelBtn);
                                shell.closeDrawer(true);
                            }

                            @Override
                            public void onFailure(String errorMessage) {
                                setBusy(busy, false, busyRow, deleteBtn, cancelBtn);
                                showError(errorBanner, errorMessage);
                            }
                        });
                    });
        });

        shell.openDrawer(
                I18n.t("Kasayı Kalıcı Sil", "Delete Vault Permanently"),
                I18n.t("Parola + .key çift doğrulaması gerektirir", "Requires Password + .key dual verification"),
                body,
                footer,
                () -> !busy.get(),
                pwInput::clear);
    }

    // ========================= SECURITY & LIFECYCLE (3-TAB DRAWER) =========================

    public static void showSecurityDrawer(
            UiShell shell,
            Window owner,
            ThemeManager themeManager,
            VaultSession session,
            ExecutorService backgroundExecutor,
            Runnable onDone,
            Consumer<Path> onVaultRenamed,
            Runnable onVaultDeleted) {
        AtomicBoolean busy = new AtomicBoolean(false);

        // Segmented tabs
        Button tabPwBtn = new Button(I18n.t("Parola", "Password"));
        Button tabKeyBtn = new Button(".key");
        Button tabManageBtn = new Button(I18n.t("Kasa Yönetimi", "Vault Management"));
        tabPwBtn.getStyleClass().add("segmented-btn");
        tabKeyBtn.getStyleClass().add("segmented-btn");
        tabManageBtn.getStyleClass().add("segmented-btn");
        for (Button b : new Button[]{tabPwBtn, tabKeyBtn, tabManageBtn}) {
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        MotionFx.SegmentedSwitch tabBar = new MotionFx.SegmentedSwitch(4, tabPwBtn, tabKeyBtn, tabManageBtn);
        tabBar.setActive(tabPwBtn, false);

        // Tab 1: Password
        PasswordInputRow curPwInput = new PasswordInputRow(I18n.t("Mevcut parola", "Current password"), themeManager);
        PasswordInputRow newPwInput = new PasswordInputRow(I18n.t("Yeni parola", "New password"), themeManager);
        PasswordInputRow confirmPwInput = new PasswordInputRow(I18n.t("Yeni parola tekrar", "Confirm new password"), themeManager);
        ProgressBar newStrengthBar = new ProgressBar(0.0);
        newStrengthBar.setMaxWidth(Double.MAX_VALUE);
        newStrengthBar.setPrefHeight(6);
        Label newStrengthLbl = new Label(I18n.t("Parola gücü: -", "Password strength: -"));
        newStrengthLbl.getStyleClass().add("muted-text");
        newStrengthLbl.setStyle("-fx-font-size: 11.5px;");
        newPwInput.addTextListener(() -> updateStrengthMeter(newPwInput.textValue(), newStrengthBar, newStrengthLbl));

        ComboBox<Argon2Preset> kdfCombo = new ComboBox<>();
        kdfCombo.getItems().addAll(Argon2Params.availablePresets());
        kdfCombo.getSelectionModel().selectFirst();
        kdfCombo.setMaxWidth(Double.MAX_VALUE);
        configurePresetCombo(kdfCombo);

        Label pwError = createErrorBanner();
        Button updatePwBtn = new Button(I18n.t("Parolayı Güncelle", "Update Password"));
        updatePwBtn.getStyleClass().add("btn-primary");
        updatePwBtn.setMaxWidth(Double.MAX_VALUE);

        VBox pwTabPane = new VBox(11,
                fieldLabel(I18n.t("Mevcut Parola", "Current Password")), curPwInput,
                fieldLabel(I18n.t("Yeni Parola", "New Password")), newPwInput,
                fieldLabel(I18n.t("Yeni Parola Tekrar", "Confirm New Password")), confirmPwInput,
                new VBox(4, newStrengthBar, newStrengthLbl),
                fieldLabel(I18n.t("Güvenlik Seviyesi", "Security Level")), kdfCombo,
                pwError,
                updatePwBtn);

        // Tab 2: .key Rotation
        TextField newKeyPathField = new TextField();
        newKeyPathField.setPromptText(I18n.t("Yeni .key dosyası konumu", "New .key file location"));
        Button browseNewKeyBtn = new Button(I18n.t("Gözat...", "Browse..."));
        browseNewKeyBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("Yeni Kurtarma Anahtarı (.key)", "New Recovery Key (.key)"));
            chooser.getExtensionFilters().add(keyExtFilter());
            chooser.setInitialFileName(I18n.t("yeni-kurtarma", "new-recovery") + VaultConstants.RECOVERY_KEY_EXTENSION);
            File f = chooser.showSaveDialog(owner);
            if (f != null) {
                newKeyPathField.setText(ensureExtension(f.getAbsolutePath(), VaultConstants.RECOVERY_KEY_EXTENSION));
            }
        });
        Label recError = createErrorBanner();
        Button rotateKeyBtn = new Button(I18n.t("Yeni Kurtarma Anahtarı Oluştur", "Generate New Recovery Key"));
        rotateKeyBtn.getStyleClass().add("btn-primary");
        rotateKeyBtn.setMaxWidth(Double.MAX_VALUE);

        VBox keyTabPane = new VBox(12,
                mutedWrap(I18n.t(
                        "Yeni kurtarma anahtarı oluşturulduğunda önceki .key dosyası kalıcı olarak geçersiz olur.",
                        "Generating a new recovery key permanently invalidates the previous .key file.")),
                fieldLabel(I18n.t("Yeni .key Dosya Yolu", "New .key File Path")),
                pickerRow(newKeyPathField, browseNewKeyBtn),
                recError,
                rotateKeyBtn);
        keyTabPane.setVisible(false);
        keyTabPane.setManaged(false);

        // Tab 3: Vault Management (Rename + Dangerous Delete)
        TextField renameNameField = new TextField(stripVaultExtension(session.vaultPath()));
        renameNameField.setPromptText(I18n.t("Yeni kasa adı", "New vault name"));
        PasswordInputRow renamePwInput = new PasswordInputRow(I18n.t("Kasa parolasını girin", "Enter vault password"), themeManager);
        TextField renameKeyField = new TextField();
        renameKeyField.setPromptText(I18n.t("Mevcut .key kurtarma dosyası", "Current .key recovery file"));
        Button browseRenameKeyBtn = new Button(I18n.t("Gözat...", "Browse..."));
        browseRenameKeyBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("Kurtarma Anahtarı Seç", "Select Recovery Key"));
            chooser.getExtensionFilters().add(keyExtFilter());
            File f = chooser.showOpenDialog(owner);
            if (f != null) {
                renameKeyField.setText(f.getAbsolutePath());
            }
        });
        Label renameError = createErrorBanner();
        Button renameBtn = new Button(I18n.t("Kasa Adını Değiştir", "Rename Vault"));
        renameBtn.getStyleClass().add("btn-primary");
        renameBtn.setMaxWidth(Double.MAX_VALUE);

        VBox renameCard = new VBox(10,
                sectionHeader(I18n.t("KASA ADINI DEĞİŞTİR", "RENAME VAULT")),
                fieldLabel(I18n.t("Yeni Ad", "New Name")), renameNameField,
                fieldLabel(I18n.t("Kasa Parolası", "Vault Password")), renamePwInput,
                fieldLabel(I18n.t("Kurtarma Anahtarı (.key)", "Recovery Key (.key)")), pickerRow(renameKeyField, browseRenameKeyBtn),
                renameError,
                renameBtn);
        renameCard.getStyleClass().add("panel-card");
        renameCard.setPadding(new Insets(14));

        PasswordInputRow delPwInput = new PasswordInputRow(I18n.t("Kasa parolasını girin", "Enter vault password"), themeManager);
        TextField delKeyField = new TextField();
        delKeyField.setPromptText(I18n.t("Bu kasaya ait .key kurtarma dosyası", ".key recovery file for this vault"));
        Button browseDelKeyBtn = new Button(I18n.t("Gözat...", "Browse..."));
        browseDelKeyBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("Kurtarma Anahtarı Seç", "Select Recovery Key"));
            chooser.getExtensionFilters().add(keyExtFilter());
            File f = chooser.showOpenDialog(owner);
            if (f != null) {
                delKeyField.setText(f.getAbsolutePath());
            }
        });
        CheckBox delKeyCheck = new CheckBox(I18n.t(".key kurtarma dosyasını da diskten kalıcı sil", "Also permanently delete the .key recovery file"));
        MotionFx.enhanceCheckBox(delKeyCheck);
        Label delError = createErrorBanner();
        Button delVaultBtn = new Button(I18n.t("Kasayı Kalıcı Olarak Sil", "Permanently Delete Vault"));
        delVaultBtn.getStyleClass().add("btn-danger");
        delVaultBtn.setMaxWidth(Double.MAX_VALUE);

        Label dangerTitle = new Label(I18n.t("TEHLİKELİ BÖLGE — KALICI SİLME", "DANGER ZONE — PERMANENT DELETION"));
        dangerTitle.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #F87171;");
        VBox dangerCard = new VBox(10,
                dangerTitle,
                mutedWrap(I18n.t(
                        "Kasa oturumu kapatılır ve dosya kalıcı olarak silinir. İşlem Parola + .key gerektirir.",
                        "Closes the vault session and permanently deletes the file. Requires Password + .key.")),
                fieldLabel(I18n.t("Kasa Parolası", "Vault Password")), delPwInput,
                fieldLabel(I18n.t("Kurtarma Anahtarı (.key)", "Recovery Key (.key)")), pickerRow(delKeyField, browseDelKeyBtn),
                delKeyCheck,
                delError,
                delVaultBtn);
        dangerCard.getStyleClass().add("danger-card");
        dangerCard.setPadding(new Insets(14));

        VBox manageTabPane = new VBox(14, renameCard, dangerCard);
        manageTabPane.setVisible(false);
        manageTabPane.setManaged(false);

        HBox busyRow = createBusyRow(I18n.t("İşlem yürütülüyor...", "Processing..."));

        // Tab switching
        int[] activeTabIdx = {0};
        Runnable selectPwTab = () -> {
            int dir = activeTabIdx[0] > 0 ? -1 : 0;
            activeTabIdx[0] = 0;
            setActiveSecurityTab(tabBar, tabPwBtn, pwTabPane, dir, pwTabPane, keyTabPane, manageTabPane);
        };
        Runnable selectKeyTab = () -> {
            int dir = Integer.compare(1, activeTabIdx[0]);
            activeTabIdx[0] = 1;
            setActiveSecurityTab(tabBar, tabKeyBtn, keyTabPane, dir, pwTabPane, keyTabPane, manageTabPane);
        };
        Runnable selectManageTab = () -> {
            int dir = activeTabIdx[0] < 2 ? 1 : 0;
            activeTabIdx[0] = 2;
            setActiveSecurityTab(tabBar, tabManageBtn, manageTabPane, dir, pwTabPane, keyTabPane, manageTabPane);
        };

        MotionFx.attachPressSpring(updatePwBtn);
        MotionFx.attachPressSpring(rotateKeyBtn);
        MotionFx.attachPressSpring(renameBtn);
        MotionFx.attachPressSpring(delVaultBtn);

        tabPwBtn.setOnAction(e -> selectPwTab.run());
        tabKeyBtn.setOnAction(e -> selectKeyTab.run());
        tabManageBtn.setOnAction(e -> selectManageTab.run());

        // Wire handlers
        updatePwBtn.setOnAction(e -> {
            clearError(pwError);
            char[] cur = curPwInput.readChars();
            char[] n1 = newPwInput.readChars();
            char[] n2 = confirmPwInput.readChars();
            if (cur.length == 0 || n1.length == 0) {
                SensitiveBytes.wipe(cur);
                SensitiveBytes.wipe(n1);
                SensitiveBytes.wipe(n2);
                showError(pwError, I18n.t("Mevcut ve yeni parola alanları boş olamaz.", "Current and new password fields cannot be empty."));
                return;
            }
            if (!Arrays.equals(n1, n2)) {
                SensitiveBytes.wipe(cur);
                SensitiveBytes.wipe(n1);
                SensitiveBytes.wipe(n2);
                showError(pwError, I18n.t("Yeni parolalar eşleşmiyor.", "New passwords do not match."));
                return;
            }
            SensitiveBytes.wipe(n2);
            Argon2Preset preset = kdfCombo.getValue();
            if (preset == null || !preset.available()) {
                SensitiveBytes.wipe(cur);
                SensitiveBytes.wipe(n1);
                showError(pwError, I18n.t("Seçilen güvenlik seviyesi bu cihazda kullanılamıyor.", "Selected security level is unavailable on this device."));
                return;
            }

            setBusy(busy, true, busyRow, updatePwBtn, rotateKeyBtn, renameBtn, delVaultBtn);
            backgroundExecutor.submit(() -> {
                try {
                    session.changePassword(cur, n1, preset.params());
                    Platform.runLater(() -> {
                        curPwInput.clear();
                        newPwInput.clear();
                        confirmPwInput.clear();
                        setBusy(busy, false, busyRow, updatePwBtn, rotateKeyBtn, renameBtn, delVaultBtn);
                        shell.showToast(ToastType.SUCCESS, I18n.t("Parola Güncellendi", "Password Updated"), I18n.t("Yeni parolanız kaydedildi.", "Your new password has been saved."));
                        if (onDone != null) onDone.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        setBusy(busy, false, busyRow, updatePwBtn, rotateKeyBtn, renameBtn, delVaultBtn);
                        showError(pwError, I18n.t("Parola değiştirilemedi: ", "Could not update password: ") + ex.getMessage());
                    });
                } finally {
                    SensitiveBytes.wipe(cur);
                    SensitiveBytes.wipe(n1);
                }
            });
        });

        rotateKeyBtn.setOnAction(e -> {
            clearError(recError);
            String pathStr = newKeyPathField.getText().trim();
            if (pathStr.isEmpty()) {
                showError(recError, I18n.t("Lütfen yeni kurtarma anahtarı için bir dosya yolu seçin.", "Please choose a file path for the new recovery key."));
                return;
            }
            setBusy(busy, true, busyRow, updatePwBtn, rotateKeyBtn, renameBtn, delVaultBtn);
            backgroundExecutor.submit(() -> {
                try {
                    Path saved = session.rotateRecoveryKey(Path.of(pathStr));
                    Platform.runLater(() -> {
                        setBusy(busy, false, busyRow, updatePwBtn, rotateKeyBtn, renameBtn, delVaultBtn);
                        shell.showToast(ToastType.SUCCESS, I18n.t("Kurtarma Anahtarı Yenilendi", "Recovery Key Rotated"), saved.getFileName().toString());
                        if (onDone != null) onDone.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        setBusy(busy, false, busyRow, updatePwBtn, rotateKeyBtn, renameBtn, delVaultBtn);
                        showError(recError, I18n.t("Kurtarma anahtarı yenilenemedi: ", "Could not rotate recovery key: ") + ex.getMessage());
                    });
                }
            });
        });

        renameBtn.setOnAction(e -> {
            clearError(renameError);
            String candidate;
            try {
                candidate = VaultSession.validateVaultFileName(renameNameField.getText());
            } catch (VaultException | IllegalArgumentException ex) {
                showError(renameError, ex.getMessage());
                return;
            }
            String kStr = renameKeyField.getText().trim();
            if (kStr.isEmpty() || !Files.isRegularFile(Path.of(kStr))) {
                showError(renameError, I18n.t("Lütfen bu kasaya ait geçerli bir .key kurtarma dosyası seçin.", "Please select a valid .key recovery file for this vault."));
                return;
            }
            char[] pw = renamePwInput.readChars();
            if (pw.length == 0) {
                SensitiveBytes.wipe(pw);
                showError(renameError, I18n.t("Lütfen kasa parolasını girin.", "Please enter the vault password."));
                return;
            }

            setBusy(busy, true, busyRow, updatePwBtn, rotateKeyBtn, renameBtn, delVaultBtn);
            backgroundExecutor.submit(() -> {
                try {
                    Path newPath = session.renameVault(candidate, pw, Path.of(kStr));
                    Platform.runLater(() -> {
                        renamePwInput.clear();
                        renameNameField.setText(stripVaultExtension(newPath));
                        setBusy(busy, false, busyRow, updatePwBtn, rotateKeyBtn, renameBtn, delVaultBtn);
                        shell.showToast(ToastType.SUCCESS, I18n.t("Kasa Adı Değiştirildi", "Vault Renamed"), newPath.getFileName().toString());
                        if (onVaultRenamed != null) onVaultRenamed.accept(newPath);
                        if (onDone != null) onDone.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        setBusy(busy, false, busyRow, updatePwBtn, rotateKeyBtn, renameBtn, delVaultBtn);
                        showError(renameError, I18n.t("Kasa adı değiştirilemedi: ", "Could not rename vault: ") + ex.getMessage());
                    });
                } finally {
                    SensitiveBytes.wipe(pw);
                }
            });
        });

        delVaultBtn.setOnAction(e -> {
            clearError(delError);
            String kStr = delKeyField.getText().trim();
            if (kStr.isEmpty() || !Files.isRegularFile(Path.of(kStr))) {
                showError(delError, I18n.t("Lütfen bu kasaya ait geçerli bir .key kurtarma dosyası seçin.", "Please select a valid .key recovery file for this vault."));
                return;
            }
            char[] pw = delPwInput.readChars();
            if (pw.length == 0) {
                SensitiveBytes.wipe(pw);
                showError(delError, I18n.t("Lütfen kasa parolasını girin.", "Please enter the vault password."));
                return;
            }

            boolean deleteKey = delKeyCheck.isSelected();
            shell.showConfirmOverlay(
                    I18n.t("Açık Kasa Kalıcı Silinsin mi?", "Permanently Delete Open Vault?"),
                    session.vaultPath().getFileName() + I18n.t(" kasası kapatılıp kalıcı olarak silinecektir.", " will be closed and permanently deleted."),
                    I18n.t("Evet, Kalıcı Sil", "Yes, Delete Permanently"),
                    true,
                    confirmed -> {
                        if (!confirmed) {
                            SensitiveBytes.wipe(pw);
                            return;
                        }
                        setBusy(busy, true, busyRow, updatePwBtn, rotateKeyBtn, renameBtn, delVaultBtn);
                        backgroundExecutor.submit(() -> {
                            try {
                                session.deleteVault(pw, Path.of(kStr), deleteKey);
                                Platform.runLater(() -> {
                                    delPwInput.clear();
                                    setBusy(busy, false, busyRow, updatePwBtn, rotateKeyBtn, renameBtn, delVaultBtn);
                                    shell.closeDrawer(true);
                                    if (onVaultDeleted != null) onVaultDeleted.run();
                                });
                            } catch (Exception ex) {
                                Platform.runLater(() -> {
                                    setBusy(busy, false, busyRow, updatePwBtn, rotateKeyBtn, renameBtn, delVaultBtn);
                                    showError(delError, I18n.t("Kasa silinemedi: ", "Could not delete vault: ") + ex.getMessage());
                                });
                            } finally {
                                SensitiveBytes.wipe(pw);
                            }
                        });
                    });
        });

        VBox body = new VBox(14, tabBar, pwTabPane, keyTabPane, manageTabPane, busyRow);
        Button closeBtn = new Button(I18n.t("Kapat", "Close"));
        closeBtn.setOnAction(e -> shell.closeDrawer());
        HBox footer = new HBox(closeBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);

        shell.openDrawer(
                I18n.t("Güvenlik & Kasa Yönetimi", "Security & Vault Management"),
                I18n.t("Parola, .key kurtarma anahtarı, yeniden adlandırma ve kalıcı silme", "Password, .key recovery key, rename and permanent deletion"),
                body,
                footer,
                () -> !busy.get(),
                () -> {
                    curPwInput.clear();
                    newPwInput.clear();
                    confirmPwInput.clear();
                    renamePwInput.clear();
                    delPwInput.clear();
                });
    }

    private static void setActiveSecurityTab(
            MotionFx.SegmentedSwitch tabBar,
            Button activeBtn,
            VBox activePane,
            int direction,
            VBox p1, VBox p2, VBox p3) {
        tabBar.setActive(activeBtn, direction != 0);

        for (VBox p : new VBox[]{p1, p2, p3}) {
            boolean show = p == activePane;
            p.setVisible(show);
            p.setManaged(show);
        }
        if (direction != 0) {
            MotionFx.playSlideSwitch(activePane, direction > 0 ? 20.0 : -20.0, 210.0);
        }
    }

    // ========================= SETTINGS DRAWER =========================

    public static void showSettingsDrawer(
            UiShell shell,
            AppSettings settings,
            ThemeManager themeManager,
            VaultSession session,
            AutoLockMonitor autoLockMonitor,
            Runnable onSettingsApplied) {
        showSettingsDrawer(shell, settings, themeManager, session, autoLockMonitor, null, onSettingsApplied);
    }

    public static void showSettingsDrawer(
            UiShell shell,
            AppSettings settings,
            ThemeManager themeManager,
            VaultSession session,
            AutoLockMonitor autoLockMonitor,
            ClipboardGuard clipboardGuard,
            Runnable onSettingsApplied) {
        // Theme segmented switch (live preview on click!)
        Button autoThemeBtn = new Button(I18n.t("Otomatik", "Auto"));
        Button darkThemeBtn = new Button(I18n.t("Koyu", "Dark"));
        Button lightThemeBtn = new Button(I18n.t("Açık", "Light"));
        for (Button b : new Button[]{autoThemeBtn, darkThemeBtn, lightThemeBtn}) {
            b.getStyleClass().add("segmented-btn");
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        MotionFx.SegmentedSwitch themeSwitch = new MotionFx.SegmentedSwitch(4, autoThemeBtn, darkThemeBtn, lightThemeBtn);

        boolean[] themeInit = {false};
        Runnable syncThemeButtons = () -> {
            ThemeMode cur = themeManager.mode();
            Button active = switch (cur) {
                case AUTO -> autoThemeBtn;
                case DARK -> darkThemeBtn;
                case LIGHT -> lightThemeBtn;
            };
            themeSwitch.setActive(active, themeInit[0]);
            themeInit[0] = true;
        };
        syncThemeButtons.run();
        autoThemeBtn.setOnAction(e -> {
            themeManager.setMode(ThemeMode.AUTO);
            syncThemeButtons.run();
        });
        darkThemeBtn.setOnAction(e -> {
            themeManager.setMode(ThemeMode.DARK);
            syncThemeButtons.run();
        });
        lightThemeBtn.setOnAction(e -> {
            themeManager.setMode(ThemeMode.LIGHT);
            syncThemeButtons.run();
        });

        Label themeRuleNote = mutedWrap(I18n.t(
                "Otomatik modda 07:00–19:00 arası Açık (soft buz mavisi), 19:00–07:00 arası Koyu (obsidyen safir) tema uygulanır.",
                "Auto mode applies Light (soft ice blue) from 07:00–19:00 and Dark (obsidian sapphire) from 19:00–07:00."));
        themeRuleNote.setStyle("-fx-font-size: 11.5px;");

        boolean[] selectedGridView = {settings.defaultGridView()};
        Button gridViewBtn = new Button(I18n.t("Izgara", "Grid"));
        Button listViewBtn = new Button(I18n.t("Liste", "List"));
        for (Button b : new Button[]{gridViewBtn, listViewBtn}) {
            b.getStyleClass().add("segmented-btn");
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        MotionFx.SegmentedSwitch defaultViewSwitch = new MotionFx.SegmentedSwitch(4, gridViewBtn, listViewBtn);
        defaultViewSwitch.setActive(selectedGridView[0] ? gridViewBtn : listViewBtn, false);
        gridViewBtn.setOnAction(e -> {
            if (!selectedGridView[0]) {
                selectedGridView[0] = true;
                defaultViewSwitch.setActive(gridViewBtn, true);
            }
        });
        listViewBtn.setOnAction(e -> {
            if (selectedGridView[0]) {
                selectedGridView[0] = false;
                defaultViewSwitch.setActive(listViewBtn, true);
            }
        });

        CheckBox previewDefaultCheck = new CheckBox(I18n.t("Kasa açıldığında önizleme panelini göster", "Show preview panel when opening a vault"));
        previewDefaultCheck.setSelected(settings.previewEnabledByDefault());
        MotionFx.enhanceCheckBox(previewDefaultCheck);

        // Language switch
        String[] selectedLanguage = {settings.language()};
        Button trLangBtn = new Button("Türkçe");
        Button enLangBtn = new Button("English");
        for (Button b : new Button[]{trLangBtn, enLangBtn}) {
            b.getStyleClass().add("segmented-btn");
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        MotionFx.SegmentedSwitch languageSwitch = new MotionFx.SegmentedSwitch(4, trLangBtn, enLangBtn);
        languageSwitch.setActive("en".equals(selectedLanguage[0]) ? enLangBtn : trLangBtn, false);
        trLangBtn.setOnAction(e -> {
            if (!"tr".equals(selectedLanguage[0])) {
                selectedLanguage[0] = "tr";
                languageSwitch.setActive(trLangBtn, true);
            }
        });
        enLangBtn.setOnAction(e -> {
            if (!"en".equals(selectedLanguage[0])) {
                selectedLanguage[0] = "en";
                languageSwitch.setActive(enLangBtn, true);
            }
        });

        // CPU Profile 3-way switch
        CpuProfile[] selectedProfile = {settings.cpuProfile()};
        Button lowBtn = new Button(I18n.cpuProfileLabel(CpuProfile.POWER_SAVER));
        Button balBtn = new Button(I18n.cpuProfileLabel(CpuProfile.BALANCED));
        Button fastBtn = new Button(I18n.cpuProfileLabel(CpuProfile.PERFORMANCE));
        for (Button b : new Button[]{lowBtn, balBtn, fastBtn}) {
            b.getStyleClass().add("segmented-btn");
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        MotionFx.SegmentedSwitch cpuSwitch = new MotionFx.SegmentedSwitch(4, lowBtn, balBtn, fastBtn);

        Spinner<Integer> workerSpinner = new Spinner<>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 16, settings.maxWorkersOverride()));
        workerSpinner.setEditable(true);
        workerSpinner.setMaxWidth(Double.MAX_VALUE);
        MotionFx.enhanceSpinner(workerSpinner);

        Label workerInfo = mutedWrap("");
        workerInfo.setStyle("-fx-font-size: 11.5px;");
        boolean[] cpuInit = {false};
        Runnable updateCpuUi = () -> {
            Button activeCpu = switch (selectedProfile[0]) {
                case POWER_SAVER -> lowBtn;
                case BALANCED -> balBtn;
                case PERFORMANCE -> fastBtn;
            };
            cpuSwitch.setActive(activeCpu, cpuInit[0]);
            cpuInit[0] = true;
            int val = workerSpinner.getValue() == null ? 0 : workerSpinner.getValue();
            workerInfo.setText(I18n.t("Eşzamanlı işlem sayısı: ", "Concurrent operations: ")
                    + selectedProfile[0].computeWorkerCount(val)
                    + I18n.t(" (0 = otomatik)", " (0 = automatic)"));
        };
        updateCpuUi.run();
        lowBtn.setOnAction(e -> {
            selectedProfile[0] = CpuProfile.POWER_SAVER;
            updateCpuUi.run();
        });
        balBtn.setOnAction(e -> {
            selectedProfile[0] = CpuProfile.BALANCED;
            updateCpuUi.run();
        });
        fastBtn.setOnAction(e -> {
            selectedProfile[0] = CpuProfile.PERFORMANCE;
            updateCpuUi.run();
        });
        workerSpinner.valueProperty().addListener((o, a, b) -> updateCpuUi.run());

        // Auto lock
        ComboBox<Integer> autoLockCombo = new ComboBox<>();
        autoLockCombo.getItems().addAll(0, 1, 5, 10, 15, 30, 60);
        autoLockCombo.setValue(settings.autoLockMinutes());
        autoLockCombo.setMaxWidth(Double.MAX_VALUE);
        autoLockCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("");
                } else if (item == 0) {
                    setText(I18n.t("Kapalı (yalnızca uyku/kapanışta kilitle)", "Off (lock only on sleep/exit)"));
                } else {
                    setText(item + I18n.t(" dakika", " min"));
                }
            }
        });
        autoLockCombo.setButtonCell(autoLockCombo.getCellFactory().call(null));
        MotionFx.enhanceComboBox(autoLockCombo);

        CheckBox keepActiveCheck = new CheckBox(I18n.t("Aktarım sürerken açık tut", "Keep unlocked during transfers"));
        MotionFx.enhanceCheckBox(keepActiveCheck);
        keepActiveCheck.setSelected(settings.keepUnlockedDuringTransfers());
        keepActiveCheck.setMinWidth(Region.USE_PREF_SIZE);
        keepActiveCheck.setTooltip(new Tooltip(I18n.t(
                "Dosya ekleme, dışa aktarma veya sıkıştırma devam ederken hareketsizlik süresi dolsa bile kasa kilitlenmez. Bilgisayar uyku moduna geçerse kasa yine kilitlenir.",
                "Prevents inactivity auto-lock while importing, exporting, or compacting. System sleep still locks the vault.")));
        keepActiveCheck.disableProperty().bind(autoLockCombo.valueProperty().isEqualTo(0));
        HBox.setHgrow(autoLockCombo, Priority.ALWAYS);
        HBox autoLockRow = new HBox(12, autoLockCombo, keepActiveCheck);
        autoLockRow.setAlignment(Pos.CENTER_LEFT);
        Label keepActiveNote = mutedWrap(I18n.t(
                "İşaretliyken dosya aktarımı bitene kadar süre sayacı durur; işlem bittiğinde sayaç yeniden başlar.",
                "When enabled, the inactivity timer pauses during file transfers and resumes once complete."));
        keepActiveNote.setStyle("-fx-font-size: 11.5px;");

        CheckBox autoCompactCheck = new CheckBox(I18n.t("Kasa kapatıldığında otomatik sıkıştır", "Auto-compact when closing vault"));
        MotionFx.enhanceCheckBox(autoCompactCheck);
        autoCompactCheck.setSelected(settings.autoCompactOnClose());
        Label autoCompactNote = mutedWrap(I18n.t(
                "İşaretliyken kasa kilitlendiğinde veya uygulama kapatıldığında boşta alan varsa kasa otomatik olarak küçültülür.",
                "When enabled, unused vault space is compacted automatically upon locking or closing."));
        autoCompactNote.setStyle("-fx-font-size: 11.5px;");

        // Clipboard Security (toggle + duration)
        CheckBox clipboardAutoClearCheck = new CheckBox(I18n.t("Panoyu otomatik temizle", "Auto-clear clipboard"));
        MotionFx.enhanceCheckBox(clipboardAutoClearCheck);
        clipboardAutoClearCheck.setSelected(settings.clipboardAutoClearEnabled());
        clipboardAutoClearCheck.setMinWidth(Region.USE_PREF_SIZE);

        ComboBox<Integer> clipboardSecondsCombo = new ComboBox<>();
        clipboardSecondsCombo.getItems().addAll(15, 30, 60, 120);
        int currentClipSec = settings.clipboardClearSeconds();
        if (!clipboardSecondsCombo.getItems().contains(currentClipSec)) {
            clipboardSecondsCombo.getItems().add(currentClipSec);
            clipboardSecondsCombo.getItems().sort(Integer::compareTo);
        }
        clipboardSecondsCombo.setValue(currentClipSec);
        clipboardSecondsCombo.setMaxWidth(Double.MAX_VALUE);
        clipboardSecondsCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item + I18n.t(" saniye", " seconds"));
            }
        });
        clipboardSecondsCombo.setButtonCell(clipboardSecondsCombo.getCellFactory().call(null));
        MotionFx.enhanceComboBox(clipboardSecondsCombo);
        clipboardSecondsCombo.disableProperty().bind(clipboardAutoClearCheck.selectedProperty().not());
        HBox.setHgrow(clipboardSecondsCombo, Priority.ALWAYS);
        HBox clipboardRow = new HBox(12, clipboardAutoClearCheck, clipboardSecondsCombo);
        clipboardRow.setAlignment(Pos.CENTER_LEFT);
        Label clipboardNote = mutedWrap(I18n.t(
                "Kasa açıkken kopyalanan hassas metinler seçilen süre dolunca (veya kasa kilitlendiğinde) panodan temizlenir.",
                "Sensitive text copied while unlocked is cleared from clipboard after the timeout or when locking."));
        clipboardNote.setStyle("-fx-font-size: 11.5px;");

        // System Tray
        boolean traySupported = SystemTrayController.isSupportedOnPlatform();
        CheckBox minimizeToTrayCheck = new CheckBox(I18n.t("Pencere kapatıldığında sistem tepsisine küçült", "Minimize to system tray on window close"));
        MotionFx.enhanceCheckBox(minimizeToTrayCheck);
        minimizeToTrayCheck.setSelected(settings.minimizeToTray());
        minimizeToTrayCheck.setDisable(!traySupported);
        Label trayNote = mutedWrap(traySupported
                ? I18n.t(
                        "İşaretliyken kapat düğmesine basıldığında uygulama kapanmaz; sistem tepsisinde çalışmaya devam eder.",
                        "When checked, closing the window keeps the app running in the system tray instead of exiting.")
                : I18n.t(
                        "Sistem tepsisi bu masaüstü ortamında desteklenmiyor.",
                        "System tray is not supported in this desktop environment."));
        trayNote.setStyle("-fx-font-size: 11.5px;");

        // Optional Source Shred After Import
        CheckBox shredSourceCheck = new CheckBox(I18n.t(
                "İçe aktarma sonrası kaynak dosyayı güvenli sil",
                "Securely shred source file after import"));
        MotionFx.enhanceCheckBox(shredSourceCheck);
        shredSourceCheck.setSelected(settings.shredSourceAfterImport());
        Label shredNote = mutedWrap(I18n.t(
                "Yalnızca kasa kaydı doğrulandıktan sonra diskteki orijinal dosya üzerine yazılarak kalıcı olarak silinir.",
                "Only after vault import is verified, the original file on disk is overwritten and permanently deleted."));
        shredNote.setStyle("-fx-font-size: 11.5px;");

        // Cache stats
        Label cacheLabel = mutedWrap("");
        cacheLabel.setStyle("-fx-font-size: 11.5px;");
        Runnable refreshCache = () -> {
            if (session == null || session.isClosed()) {
                cacheLabel.setText(I18n.t("Şu anda açık kasa yok.", "No vault currently open."));
            } else {
                BlockCache.CacheStats st = session.getCacheStats();
                long totalCacheBytes = st.encryptedBytes() + st.plaintextBytes();
                cacheLabel.setText(I18n.t("Geçici önbellek kullanımı: ", "Temporary cache usage: ") + FormatUtils.formatBytes(totalCacheBytes));
            }
        };
        refreshCache.run();

        Button clearCacheBtn = new Button(I18n.t("Önbelleği Şimdi Temizle", "Clear Cache Now"));
        clearCacheBtn.setDisable(session == null || session.isClosed());
        clearCacheBtn.setOnAction(e -> {
            if (session != null && !session.isClosed()) {
                session.clearCache();
                refreshCache.run();
                shell.showToast(ToastType.INFO,
                        I18n.t("Önbellek Temizlendi", "Cache Cleared"),
                        I18n.t("Bellekteki geçici veriler temizlendi.", "Temporary in-memory data cleared."));
            }
        });

        VBox themeCard = new VBox(10,
                sectionHeader(I18n.t("GÖRÜNÜM & DİL", "APPEARANCE & LANGUAGE")),
                themeSwitch,
                themeRuleNote,
                fieldLabel(I18n.t("Varsayılan Kasa Görünümü", "Default Vault View")),
                defaultViewSwitch,
                previewDefaultCheck,
                fieldLabel(I18n.t("Arayüz Dili", "Interface Language")),
                languageSwitch);
        themeCard.getStyleClass().add("panel-card");
        themeCard.setPadding(new Insets(14));

        VBox perfCard = new VBox(10,
                sectionHeader(I18n.t("PERFORMANS", "PERFORMANCE")),
                fieldLabel(I18n.t("Performans Modu", "Performance Mode")), cpuSwitch,
                fieldLabel(I18n.t("Paralel İşlem Sınırı", "Concurrent Operations Limit")), workerSpinner,
                workerInfo);
        perfCard.getStyleClass().add("panel-card");
        perfCard.setPadding(new Insets(14));

        VBox lockCacheCard = new VBox(10,
                sectionHeader(I18n.t("GÜVENLİK, SİSTEM & ÖNBELLEK", "SECURITY, SYSTEM & CACHE")),
                fieldLabel(I18n.t("Hareketsizlik Sonrası Kilitleme", "Inactivity Auto-Lock")), autoLockRow,
                keepActiveNote,
                fieldLabel(I18n.t("Pano Güvenliği", "Clipboard Security")), clipboardRow,
                clipboardNote,
                autoCompactCheck,
                autoCompactNote,
                shredSourceCheck,
                shredNote,
                minimizeToTrayCheck,
                trayNote,
                cacheLabel,
                clearCacheBtn);
        lockCacheCard.getStyleClass().add("panel-card");
        lockCacheCard.setPadding(new Insets(14));

        VBox body = new VBox(14, themeCard, perfCard, lockCacheCard);

        Button cancelBtn = new Button(I18n.t("Kapat", "Close"));
        Button saveBtn = new Button(I18n.t("Kaydet ve Uygula", "Save & Apply"));
        saveBtn.getStyleClass().add("btn-primary");
        MotionFx.attachPressSpring(saveBtn);
        Region fSpacer = new Region();
        HBox.setHgrow(fSpacer, Priority.ALWAYS);
        HBox footer = new HBox(10, cancelBtn, fSpacer, saveBtn);

        cancelBtn.setOnAction(e -> shell.closeDrawer());
        saveBtn.setOnAction(e -> {
            settings.setCpuProfile(selectedProfile[0]);
            settings.setMaxWorkersOverride(workerSpinner.getValue() == null ? 0 : workerSpinner.getValue());
            settings.setAutoLockMinutes(autoLockCombo.getValue() == null ? 10 : autoLockCombo.getValue());
            settings.setKeepUnlockedDuringTransfers(keepActiveCheck.isSelected());
            settings.setAutoCompactOnClose(autoCompactCheck.isSelected());
            settings.setShredSourceAfterImport(shredSourceCheck.isSelected());
            settings.setDefaultViewMode(selectedGridView[0] ? "GRID" : "LIST");
            settings.setPreviewEnabledByDefault(previewDefaultCheck.isSelected());
            settings.setClipboardAutoClearEnabled(clipboardAutoClearCheck.isSelected());
            settings.setClipboardClearSeconds(clipboardSecondsCombo.getValue() == null ? 30 : clipboardSecondsCombo.getValue());
            settings.setMinimizeToTray(traySupported && minimizeToTrayCheck.isSelected());
            settings.setLanguage(selectedLanguage[0]);
            settings.save();
            I18n.setLanguage(settings.language());
            if (session != null && !session.isClosed()) {
                session.reconfigureWorkers(settings.cpuProfile(), settings.maxWorkersOverride());
            }
            if (autoLockMonitor != null) {
                autoLockMonitor.updateTimeoutMinutes(settings.autoLockMinutes());
                autoLockMonitor.setKeepActiveDuringTransfers(settings.keepUnlockedDuringTransfers());
            }
            if (clipboardGuard != null) {
                clipboardGuard.updateSettings(settings.clipboardAutoClearEnabled(), settings.clipboardClearSeconds());
            }
            if (onSettingsApplied != null) {
                onSettingsApplied.run();
            }
            shell.closeDrawer(true);
            shell.showToast(ToastType.SUCCESS,
                    I18n.t("Ayarlar Kaydedildi", "Settings Saved"),
                    I18n.t("Tüm tercihleriniz uygulandı.", "Your preferences have been applied."));
        });

        shell.openDrawer(
                I18n.t("Ayarlar", "Settings"),
                I18n.t("Görünüm, dil, pano güvenliği, sistem tepsisi ve performans", "Appearance, language, clipboard security, system tray and performance"),
                body,
                footer,
                () -> true,
                null);
    }

    // ========================= SHARED UI HELPERS =========================

    private static Label fieldLabel(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("primary-text");
        lbl.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
        return lbl;
    }

    private static Label sectionHeader(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("section-title");
        return lbl;
    }

    private static Label mutedWrap(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("muted-text");
        lbl.setWrapText(true);
        return lbl;
    }

    private static HBox pickerRow(TextField field, Button browseBtn) {
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox row = new HBox(8, field, browseBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label createErrorBanner() {
        Label banner = new Label();
        banner.getStyleClass().add("inline-error-banner");
        banner.setWrapText(true);
        banner.setPadding(new Insets(8, 12, 8, 12));
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setVisible(false);
        banner.setManaged(false);
        return banner;
    }

    private static void showError(Label banner, String msg) {
        banner.setText(msg == null || msg.isBlank() ? I18n.t("İşlem sırasında bir hata oluştu.", "An error occurred during the operation.") : msg);
        banner.setVisible(true);
        banner.setManaged(true);
        MotionFx.playErrorShake(banner);
    }

    private static void clearError(Label banner) {
        banner.setText("");
        banner.setVisible(false);
        banner.setManaged(false);
    }

    private static HBox createBusyRow(String text) {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(20, 20);
        Label lbl = mutedWrap(text);
        HBox row = new HBox(10, spinner, lbl);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setVisible(false);
        row.setManaged(false);
        return row;
    }

    private static void setBusy(AtomicBoolean busy, boolean state, HBox busyRow, Button... buttonsToDisable) {
        busy.set(state);
        busyRow.setVisible(state);
        busyRow.setManaged(state);
        for (Button b : buttonsToDisable) {
            b.setDisable(state);
        }
    }

    private static void updateStrengthMeter(String pw, ProgressBar bar, Label label) {
        if (pw == null || pw.isEmpty()) {
            MotionFx.animateProgress(bar, 0.0, 180.0);
            bar.setStyle("");
            label.setText(I18n.t("Parola gücü: -", "Password strength: -"));
            return;
        }
        int score = 0;
        if (pw.length() >= 8) score++;
        if (pw.length() >= 14) score++;
        if (pw.chars().anyMatch(Character::isUpperCase)) score++;
        if (pw.chars().anyMatch(Character::isLowerCase)) score++;
        if (pw.chars().anyMatch(Character::isDigit)) score++;
        if (pw.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch))) score++;

        if (score <= 2) {
            MotionFx.animateProgress(bar, 0.30, 200.0);
            bar.setStyle("-fx-accent: #EF4444;");
            label.setText(I18n.t("Parola gücü: Zayıf", "Password strength: Weak"));
        } else if (score <= 4) {
            MotionFx.animateProgress(bar, 0.62, 200.0);
            bar.setStyle("-fx-accent: #F59E0B;");
            label.setText(I18n.t("Parola gücü: Orta", "Password strength: Fair"));
        } else {
            MotionFx.animateProgress(bar, 1.0, 200.0);
            bar.setStyle("-fx-accent: #22C55E;");
            label.setText(I18n.t("Parola gücü: Güçlü", "Password strength: Strong"));
        }
    }

    private static void configurePresetCombo(ComboBox<Argon2Preset> combo) {
        combo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Argon2Preset item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setDisable(false);
                    setOpacity(1.0);
                } else {
                    setText(formatPresetLabel(item));
                    setDisable(!item.available());
                    setOpacity(item.available() ? 1.0 : 0.45);
                }
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Argon2Preset item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatPresetLabel(item));
            }
        });
    }

    private static String formatPresetLabel(Argon2Preset item) {
        String raw = item.label() == null ? "" : item.label();
        int parenIdx = raw.indexOf(" (");
        String cleanName = parenIdx > 0 ? raw.substring(0, parenIdx) : raw;
        if (I18n.isEnglish()) {
            cleanName = switch (cleanName) {
                case "Önerilen" -> "Recommended";
                case "Düşük Bellek" -> "Low Memory";
                case "Standart" -> "Standard";
                case "Güçlü" -> "Strong";
                case "Yüksek" -> "High";
                case "Çok Yüksek" -> "Very High";
                case "Maksimum" -> "Maximum";
                default -> cleanName;
            };
        }
        return item.available() ? cleanName : cleanName + I18n.t(" (Bu cihazda kullanılamaz)", " (Unavailable on this device)");
    }

    private static FileChooser.ExtensionFilter vaultExtFilter() {
        return new FileChooser.ExtensionFilter(
                I18n.t("Sapphirypt Kasası (*", "Sapphirypt Vault (*") + VaultConstants.FILE_EXTENSION + ")",
                "*" + VaultConstants.FILE_EXTENSION);
    }

    private static FileChooser.ExtensionFilter keyExtFilter() {
        return new FileChooser.ExtensionFilter(
                I18n.t("Kurtarma Anahtarı (*", "Recovery Key (*") + VaultConstants.RECOVERY_KEY_EXTENSION + ")",
                "*" + VaultConstants.RECOVERY_KEY_EXTENSION);
    }

    private static String ensureExtension(String path, String ext) {
        return path.toLowerCase(Locale.ROOT).endsWith(ext) ? path : path + ext;
    }

    private static String deriveDefaultKeyPath(String vaultPath) {
        if (vaultPath.toLowerCase(Locale.ROOT).endsWith(VaultConstants.FILE_EXTENSION)) {
            return vaultPath.substring(0, vaultPath.length() - VaultConstants.FILE_EXTENSION.length())
                    + VaultConstants.RECOVERY_KEY_EXTENSION;
        }
        return vaultPath + VaultConstants.RECOVERY_KEY_EXTENSION;
    }

    private static String stripVaultExtension(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        String fileName = path.getFileName().toString();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(VaultConstants.FILE_EXTENSION)) {
            return fileName.substring(0, fileName.length() - VaultConstants.FILE_EXTENSION.length());
        }
        return fileName;
    }
}
