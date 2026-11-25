package com.sapphircrypt.sapphirypt.ui;

import com.sapphircrypt.sapphirypt.crypto.Argon2Params;
import com.sapphircrypt.sapphirypt.crypto.Argon2Params.Argon2Preset;
import com.sapphircrypt.sapphirypt.crypto.SensitiveBytes;
import com.sapphircrypt.sapphirypt.format.VaultConstants;
import com.sapphircrypt.sapphirypt.storage.AppSettings;
import com.sapphircrypt.sapphirypt.storage.BlockCache;
import com.sapphircrypt.sapphirypt.storage.CpuProfile;
import com.sapphircrypt.sapphirypt.storage.VaultSession;
import com.sapphircrypt.sapphirypt.storage.VaultSession.VaultInfo;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Styled modal dialogs for vault creation, unlock, conflict resolution, key management,
 * settings, and structured error reporting.
 */
public final class VaultDialogs {
    private static final String CSS_RESOURCE = "/com/sapphircrypt/sapphirypt/sapphirypt.css";

    private VaultDialogs() {
    }

    public static final class CreateVaultRequest implements AutoCloseable {
        public final Path vaultPath;
        public final Path recoveryKeyPath;
        public final char[] password;
        public final Argon2Params argon2Params;

        CreateVaultRequest(Path vaultPath, Path recoveryKeyPath, char[] password, Argon2Params argon2Params) {
            this.vaultPath = vaultPath;
            this.recoveryKeyPath = recoveryKeyPath;
            this.password = password;
            this.argon2Params = argon2Params;
        }

        @Override
        public void close() {
            SensitiveBytes.wipe(password);
        }
    }

    public static final class OpenVaultRequest implements AutoCloseable {
        public final Path vaultPath;
        public final boolean usingRecoveryKey;
        public final char[] password;
        public final Path recoveryKeyPath;

        OpenVaultRequest(Path vaultPath, boolean usingRecoveryKey, char[] password, Path recoveryKeyPath) {
            this.vaultPath = vaultPath;
            this.usingRecoveryKey = usingRecoveryKey;
            this.password = password;
            this.recoveryKeyPath = recoveryKeyPath;
        }

        @Override
        public void close() {
            SensitiveBytes.wipe(password);
        }
    }

    public static final class RenameVaultRequest implements AutoCloseable {
        public final Path vaultPath;
        public final String newVaultName;
        public final char[] password;
        public final Path recoveryKeyPath;

        RenameVaultRequest(Path vaultPath, String newVaultName, char[] password, Path recoveryKeyPath) {
            this.vaultPath = vaultPath;
            this.newVaultName = newVaultName;
            this.password = password;
            this.recoveryKeyPath = recoveryKeyPath;
        }

        @Override
        public void close() {
            SensitiveBytes.wipe(password);
        }
    }

    public static final class DeleteVaultRequest implements AutoCloseable {
        public final Path vaultPath;
        public final char[] password;
        public final Path recoveryKeyPath;
        public final boolean deleteRecoveryKeyFile;

        DeleteVaultRequest(Path vaultPath, char[] password, Path recoveryKeyPath, boolean deleteRecoveryKeyFile) {
            this.vaultPath = vaultPath;
            this.password = password;
            this.recoveryKeyPath = recoveryKeyPath;
            this.deleteRecoveryKeyFile = deleteRecoveryKeyFile;
        }

        @Override
        public void close() {
            SensitiveBytes.wipe(password);
        }
    }

    public enum ConflictAction {
        OVERWRITE, RENAME, CANCEL
    }

    public record ConflictResolution(ConflictAction action, String newName) {
    }

    public static Optional<CreateVaultRequest> showCreateVaultDialog(Window owner) {
        Dialog<CreateVaultRequest> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Yeni Kasa Oluştur");
        dialog.setHeaderText("Yeni şifreli .sapphirypt kasası ve kurtarma anahtarı oluşturun");
        styleDialog(dialog.getDialogPane());

        ButtonType createBtnType = new ButtonType("Kasa Oluştur", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createBtnType, ButtonType.CANCEL);

        TextField vaultPathField = new TextField();
        vaultPathField.setPromptText("Örnek: C:\\Kasalar\\kisisel.sapphirypt");
        Button browseVaultBtn = new Button("Seç...");

        TextField keyPathField = new TextField();
        keyPathField.setPromptText("Kurtarma anahtarı (.key) yolu");
        Button browseKeyBtn = new Button("Seç...");

        PasswordField pwField = new PasswordField();
        pwField.setPromptText("Güçlü bir parola girin");
        PasswordField confirmField = new PasswordField();
        confirmField.setPromptText("Parolayı tekrar girin");
        Label pwStrengthLabel = new Label("Parola gücü: -");
        pwStrengthLabel.getStyleClass().add("muted-text");

        ComboBox<Argon2Preset> kdfCombo = new ComboBox<>();
        kdfCombo.getItems().addAll(Argon2Params.availablePresets());
        kdfCombo.getSelectionModel().selectFirst();
        kdfCombo.setMaxWidth(Double.MAX_VALUE);
        configurePresetCombo(kdfCombo);

        Label warningNote = new Label("Önemli: Kurtarma (.key) dosyasını kasadan farklı ve güvenli bir konumda saklayın. " + "Hem parola hem de .key dosyası kaybolursa verilere erişilemez.");
        warningNote.setWrapText(true);
        warningNote.getStyleClass().add("warning-badge");

        Label validationError = new Label();
        validationError.setStyle("-fx-text-fill: #F87171;");
        validationError.setWrapText(true);

        browseVaultBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Kasa Dosyası Konumu");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Sapphirypt Kasası (*" + VaultConstants.FILE_EXTENSION + ")", "*" + VaultConstants.FILE_EXTENSION));
            chooser.setInitialFileName("Kasa" + VaultConstants.FILE_EXTENSION);
            File chosen = chooser.showSaveDialog(owner);
            if (chosen != null) {
                String pathStr = ensureExtension(chosen.getAbsolutePath(), VaultConstants.FILE_EXTENSION);
                vaultPathField.setText(pathStr);
                if (keyPathField.getText().isBlank()) keyPathField.setText(deriveDefaultKeyPath(pathStr));
            }
        });

        browseKeyBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Kurtarma Anahtarı (.key) Konumu");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Kurtarma Anahtarı (*" + VaultConstants.RECOVERY_KEY_EXTENSION + ")", "*" + VaultConstants.RECOVERY_KEY_EXTENSION));
            chooser.setInitialFileName("kurtarma" + VaultConstants.RECOVERY_KEY_EXTENSION);
            File chosen = chooser.showSaveDialog(owner);
            if (chosen != null) keyPathField.setText(ensureExtension(chosen.getAbsolutePath(), VaultConstants.RECOVERY_KEY_EXTENSION));

        });

        pwField.textProperty().addListener((obs, oldV, newV) -> pwStrengthLabel.setText("Parola gücü: " + evaluatePasswordStrength(newV)));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        grid.setPrefWidth(540);

        HBox vaultRow = new HBox(8, vaultPathField, browseVaultBtn);
        HBox.setHgrow(vaultPathField, Priority.ALWAYS);
        HBox keyRow = new HBox(8, keyPathField, browseKeyBtn);
        HBox.setHgrow(keyPathField, Priority.ALWAYS);

        grid.add(new Label("Kasa Dosyası:"), 0, 0);
        grid.add(vaultRow, 1, 0);
        grid.add(new Label("Kurtarma Anahtarı:"), 0, 1);
        grid.add(keyRow, 1, 1);
        grid.add(new Label("Parola:"), 0, 2);
        grid.add(pwField, 1, 2);
        grid.add(new Label("Parola Tekrar:"), 0, 3);
        grid.add(confirmField, 1, 3);
        grid.add(pwStrengthLabel, 1, 4);
        grid.add(new Label("Argon2id Profili:"), 0, 5);
        grid.add(kdfCombo, 1, 5);
        grid.add(warningNote, 0, 6, 2, 1);
        grid.add(validationError, 0, 7, 2, 1);
        GridPane.setHgrow(vaultRow, Priority.ALWAYS);

        dialog.getDialogPane().setContent(grid);

        Button okBtn = (Button) dialog.getDialogPane().lookupButton(createBtnType);
        okBtn.getStyleClass().add("btn-primary");
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            validationError.setText("");
            String vPath = vaultPathField.getText().trim();
            String kPath = keyPathField.getText().trim();
            Argon2Preset preset = kdfCombo.getValue();
            if (vPath.isEmpty()) {
                validationError.setText("Lütfen bir kasa dosya yolu seçin.");
                ev.consume();
                return;
            }
            if (kPath.isEmpty()) {
                validationError.setText("Lütfen kurtarma anahtarı (.key) dosya yolu seçin.");
                ev.consume();
                return;
            }
            if (Path.of(vPath).toAbsolutePath().normalize().equals(Path.of(kPath).toAbsolutePath().normalize())) {
                validationError.setText("Kasa ve kurtarma anahtarı aynı dosya olamaz.");
                ev.consume();
                return;
            }
            if (pwField.getCharacters().isEmpty()) {
                validationError.setText("Parola boş bırakılamaz.");
                ev.consume();
                return;
            }
            char[] p1 = readChars(pwField);
            char[] p2 = readChars(confirmField);
            try {
                if (!Arrays.equals(p1, p2)) {
                    validationError.setText("Parolalar eşleşmiyor.");
                    ev.consume();
                    return;
                }
            } finally {
                SensitiveBytes.wipe(p1);
                SensitiveBytes.wipe(p2);
            }
            if (preset == null || !preset.available()) {
                validationError.setText("Seçilen Argon2id profili bu sistem/JVM belleği için uygun değil.");
                ev.consume();
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn != createBtnType) {
                pwField.clear();
                confirmField.clear();
                return null;
            }
            char[] password = readAndClear(pwField);
            confirmField.clear();
            String vPath = ensureExtension(vaultPathField.getText().trim(), VaultConstants.FILE_EXTENSION);
            String kPath = ensureExtension(keyPathField.getText().trim(), VaultConstants.RECOVERY_KEY_EXTENSION);
            return new CreateVaultRequest(
                    Path.of(vPath), Path.of(kPath), password, kdfCombo.getValue().params());
        });

        return dialog.showAndWait();
    }

    public static Optional<OpenVaultRequest> showOpenVaultDialog(Window owner, Path preselectedVault) {
        Dialog<OpenVaultRequest> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Kasa Aç");
        dialog.setHeaderText("Şifreli .sapphirypt kasasının kilidini açın");
        styleDialog(dialog.getDialogPane());

        ButtonType openBtnType = new ButtonType("Kilidi Aç", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(openBtnType, ButtonType.CANCEL);

        TextField vaultField = new TextField();
        vaultField.setPromptText("Açılacak .sapphirypt dosyasını seçin");
        Button browseVaultBtn = new Button("Gözat...");

        Label vaultInfoLabel = new Label("Kasa bilgisi: Dosya seçilmedi");
        vaultInfoLabel.getStyleClass().add("muted-text");
        vaultInfoLabel.setWrapText(true);

        PasswordField pwField = new PasswordField();
        pwField.setPromptText("Kasa parolasını girin");

        TextField keyField = new TextField();
        keyField.setPromptText("Kurtarma anahtarı (.key) dosyasını seçin");
        Button browseKeyBtn = new Button("Gözat...");

        Tab passwordTab = new Tab("Parola ile Aç");
        passwordTab.setClosable(false);
        VBox pwBox = new VBox(8, new Label("Kasa Parolası:"), pwField);
        pwBox.setPadding(new Insets(12, 4, 8, 4));
        passwordTab.setContent(pwBox);

        Tab recoveryTab = new Tab("Kurtarma Anahtarı (.key) ile Aç");
        recoveryTab.setClosable(false);
        HBox keyRow = new HBox(8, keyField, browseKeyBtn);
        HBox.setHgrow(keyField, Priority.ALWAYS);
        VBox recBox = new VBox(8, new Label("Kurtarma Anahtarı Dosyası:"), keyRow);
        recBox.setPadding(new Insets(12, 4, 8, 4));
        recoveryTab.setContent(recBox);

        TabPane modeTabs = new TabPane(passwordTab, recoveryTab);

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #F87171;");
        errorLabel.setWrapText(true);

        Consumer<Path> inspectPath = path -> {
            if (path == null || !Files.isRegularFile(path)) {
                vaultInfoLabel.setText("Kasa bilgisi: Dosya bulunamadı");
                return;
            }
            try {
                VaultInfo info = VaultSession.inspectVault(path);
                String kdfText = info.argon2Params() != null ? info.argon2Params().memoryMiB() + " MiB, " + info.argon2Params().iterations() + " iter, " + info.argon2Params().parallelism() + " iş parçacığı" : "-";
                vaultInfoLabel.setText(String.format("Kasa ID: %s | Format v%d | Argon2id: %s", FormatUtils.formatContainerId(info.containerId()), info.formatVersion(), kdfText));
                errorLabel.setText("");
            } catch (Exception ex) {
                vaultInfoLabel.setText("Kasa başlığı okunamadı: " + ex.getMessage());
            }
        };

        if (preselectedVault != null) {
            vaultField.setText(preselectedVault.toAbsolutePath().toString());
            inspectPath.accept(preselectedVault);
        }

        browseVaultBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Kasa Dosyası Seç");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Sapphirypt Kasası (*" + VaultConstants.FILE_EXTENSION + ")", "*" + VaultConstants.FILE_EXTENSION));
            File chosen = chooser.showOpenDialog(owner);
            if (chosen != null) {
                vaultField.setText(chosen.getAbsolutePath());
                inspectPath.accept(chosen.toPath());
            }
        });

        browseKeyBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Kurtarma Anahtarı Seç");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Kurtarma Anahtarı (*" + VaultConstants.RECOVERY_KEY_EXTENSION + ")", "*" + VaultConstants.RECOVERY_KEY_EXTENSION));
            File chosen = chooser.showOpenDialog(owner);
            if (chosen != null) keyField.setText(chosen.getAbsolutePath());
        });

        HBox vaultRow = new HBox(8, vaultField, browseVaultBtn);
        HBox.setHgrow(vaultField, Priority.ALWAYS);

        VBox content = new VBox(10, new Label("Kasa Dosyası (.sapphirypt):"), vaultRow, vaultInfoLabel, modeTabs, errorLabel);
        content.setPadding(new Insets(12));
        content.setPrefWidth(520);
        dialog.getDialogPane().setContent(content);

        Button okBtn = (Button) dialog.getDialogPane().lookupButton(openBtnType);
        okBtn.getStyleClass().add("btn-primary");
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            errorLabel.setText("");
            String vStr = vaultField.getText().trim();
            if (vStr.isEmpty() || !Files.isRegularFile(Path.of(vStr))) {
                errorLabel.setText("Lütfen geçerli bir .sapphirypt kasa dosyası seçin.");
                ev.consume();
                return;
            }
            if (modeTabs.getSelectionModel().getSelectedItem() == passwordTab) {
                if (pwField.getCharacters().isEmpty()) {
                    errorLabel.setText("Lütfen kasa parolasını girin.");
                    ev.consume();
                }
            } else {
                String kStr = keyField.getText().trim();
                if (kStr.isEmpty() || !Files.isRegularFile(Path.of(kStr))) {
                    errorLabel.setText("Lütfen geçerli bir .key kurtarma dosyası seçin.");
                    ev.consume();
                }
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn != openBtnType) {
                pwField.clear();
                return null;
            }
            Path vPath = Path.of(vaultField.getText().trim());
            boolean useRecovery = modeTabs.getSelectionModel().getSelectedItem() == recoveryTab;
            if (useRecovery) {
                pwField.clear();
                return new OpenVaultRequest(vPath, true, null, Path.of(keyField.getText().trim()));
            }
            return new OpenVaultRequest(vPath, false, readAndClear(pwField), null);
        });

        return dialog.showAndWait();
    }

    public static Optional<RenameVaultRequest> showRenameVaultDialog(Window owner, Path preselectedVault) {
        Dialog<RenameVaultRequest> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Kasa Adını Değiştir");
        dialog.setHeaderText("Kasa adını değiştirmek için parola ve kurtarma anahtarı (.key) gereklidir");
        styleDialog(dialog.getDialogPane());

        ButtonType renameBtnType = new ButtonType("Adını Değiştir", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(renameBtnType, ButtonType.CANCEL);

        TextField vaultField = new TextField();
        vaultField.setPromptText("Adı değiştirilecek .sapphirypt dosyasını seçin");
        TextField newNameField = new TextField();
        newNameField.setPromptText("Örnek: yeni-kasa-adi");

        Runnable fillBaseName = () -> {
            String v = vaultField.getText().trim();
            if (!v.isEmpty() && newNameField.getText().isBlank()) {
                newNameField.setText(stripVaultExtension(Path.of(v)));
            }
        };

        HBox vaultRow = createOpenFilePickerRow(
                owner, vaultField, "Kasa Dosyası Seç", vaultExtFilter(), () -> {
                    String txt = vaultField.getText().trim();
                    if (!txt.isEmpty()) {
                        newNameField.setText(stripVaultExtension(Path.of(txt)));
                    }
                });

        if (preselectedVault != null) {
            vaultField.setText(preselectedVault.toAbsolutePath().toString());
            fillBaseName.run();
        }

        PasswordField pwField = new PasswordField();
        pwField.setPromptText("Kasa parolasını girin");

        TextField keyField = new TextField();
        keyField.setPromptText("Bu kasaya ait .key kurtarma dosyasını seçin");
        HBox keyRow = createOpenFilePickerRow(owner, keyField, "Kurtarma Anahtarı Seç", keyExtFilter(), null);

        Label requirementNote = new Label(
                "Güvenlik kuralı: Kasa adının değiştirilebilmesi için hem kasa parolası hem de eşleşen .key kurtarma dosyası doğrulanır.");
        requirementNote.setWrapText(true);
        requirementNote.getStyleClass().add("warning-badge");

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #F87171;");
        errorLabel.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        grid.setPrefWidth(540);
        grid.add(new Label("Kasa Dosyası:"), 0, 0);
        grid.add(vaultRow, 1, 0);
        grid.add(new Label("Yeni Kasa Adı:"), 0, 1);
        grid.add(newNameField, 1, 1);
        grid.add(new Label("Kasa Parolası:"), 0, 2);
        grid.add(pwField, 1, 2);
        grid.add(new Label("Kurtarma Anahtarı:"), 0, 3);
        grid.add(keyRow, 1, 3);
        grid.add(requirementNote, 0, 4, 2, 1);
        grid.add(errorLabel, 0, 5, 2, 1);
        GridPane.setHgrow(vaultRow, Priority.ALWAYS);

        dialog.getDialogPane().setContent(grid);

        Button okBtn = (Button) dialog.getDialogPane().lookupButton(renameBtnType);
        okBtn.getStyleClass().add("btn-primary");
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            errorLabel.setText("");
            String validationMsg = validateClosedVaultDualAuthInputs(
                    vaultField.getText(), pwField, keyField.getText());
            if (validationMsg != null) {
                errorLabel.setText(validationMsg);
                ev.consume();
                return;
            }
            try {
                String validFileName = VaultSession.validateVaultFileName(newNameField.getText());
                String currentFileName = Path.of(vaultField.getText().trim()).getFileName().toString();
                if (validFileName.equals(currentFileName)) {
                    errorLabel.setText("Yeni kasa adı mevcut ad ile aynı.");
                    ev.consume();
                }
            } catch (Exception ex) {
                errorLabel.setText(ex.getMessage());
                ev.consume();
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn != renameBtnType) {
                pwField.clear();
                return null;
            }
            return new RenameVaultRequest(
                    Path.of(vaultField.getText().trim()),
                    newNameField.getText().trim(),
                    readAndClear(pwField),
                    Path.of(keyField.getText().trim()));
        });

        return dialog.showAndWait();
    }

    public static Optional<DeleteVaultRequest> showDeleteVaultDialog(Window owner, Path preselectedVault) {
        Dialog<DeleteVaultRequest> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Kasayı Kalıcı Olarak Sil");
        dialog.setHeaderText("Kasa dosyasını silmek için parola ve kurtarma anahtarı (.key) gereklidir");
        styleDialog(dialog.getDialogPane());

        ButtonType deleteBtnType = new ButtonType("Kasayı Kalıcı Olarak Sil", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(deleteBtnType, ButtonType.CANCEL);

        TextField vaultField = new TextField();
        vaultField.setPromptText("Silinecek .sapphirypt dosyasını seçin");
        HBox vaultRow = createOpenFilePickerRow(owner, vaultField, "Silinecek Kasayı Seç", vaultExtFilter(), null);
        if (preselectedVault != null) {
            vaultField.setText(preselectedVault.toAbsolutePath().toString());
        }

        PasswordField pwField = new PasswordField();
        pwField.setPromptText("Kasa parolasını girin");

        TextField keyField = new TextField();
        keyField.setPromptText("Bu kasaya ait .key kurtarma dosyasını seçin");
        HBox keyRow = createOpenFilePickerRow(owner, keyField, "Kurtarma Anahtarı Seç", keyExtFilter(), null);

        CheckBox deleteKeyCheck = new CheckBox("Kurtarma anahtarı (.key) dosyasını da kalıcı olarak sil");
        CheckBox confirmCheck = new CheckBox("Bu kasanın ve içindeki tüm verilerin kalıcı olarak silineceğini onaylıyorum");

        Label dangerNote = new Label(
                "Dikkat: Kasa silme işlemi geri alınamaz. Güvenlik nedeniyle hem kasa parolası hem de .key dosyası doğrulanacaktır.");
        dangerNote.setWrapText(true);
        dangerNote.getStyleClass().add("warning-badge");

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #F87171;");
        errorLabel.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        grid.setPrefWidth(540);
        grid.add(new Label("Kasa Dosyası:"), 0, 0);
        grid.add(vaultRow, 1, 0);
        grid.add(new Label("Kasa Parolası:"), 0, 1);
        grid.add(pwField, 1, 1);
        grid.add(new Label("Kurtarma Anahtarı:"), 0, 2);
        grid.add(keyRow, 1, 2);
        grid.add(deleteKeyCheck, 0, 3, 2, 1);
        grid.add(confirmCheck, 0, 4, 2, 1);
        grid.add(dangerNote, 0, 5, 2, 1);
        grid.add(errorLabel, 0, 6, 2, 1);
        GridPane.setHgrow(vaultRow, Priority.ALWAYS);

        dialog.getDialogPane().setContent(grid);

        Button deleteBtn = (Button) dialog.getDialogPane().lookupButton(deleteBtnType);
        deleteBtn.getStyleClass().add("btn-danger");
        deleteBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            errorLabel.setText("");
            String validationMsg = validateClosedVaultDualAuthInputs(
                    vaultField.getText(), pwField, keyField.getText());
            if (validationMsg != null) {
                errorLabel.setText(validationMsg);
                ev.consume();
                return;
            }
            if (!confirmCheck.isSelected()) {
                errorLabel.setText("Lütfen kalıcı silme onay kutucuğunu işaretleyin.");
                ev.consume();
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn != deleteBtnType) {
                pwField.clear();
                return null;
            }
            return new DeleteVaultRequest(
                    Path.of(vaultField.getText().trim()),
                    readAndClear(pwField),
                    Path.of(keyField.getText().trim()),
                    deleteKeyCheck.isSelected());
        });

        return dialog.showAndWait();
    }

    public static Optional<String> showTextInputDialog(Window owner, String title, String header, String promptLabel, String initialValue) {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        styleDialog(dialog.getDialogPane());

        ButtonType okType = new ButtonType("Tamam", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        TextField input = new TextField(initialValue == null ? "" : initialValue);
        Label error = new Label();
        error.setStyle("-fx-text-fill: #F87171;");
        VBox box = new VBox(8, new Label(promptLabel), input, error);
        box.setPadding(new Insets(12));
        box.setPrefWidth(400);
        dialog.getDialogPane().setContent(box);

        Button okBtn = (Button) dialog.getDialogPane().lookupButton(okType);
        okBtn.getStyleClass().add("btn-primary");
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            String text = input.getText().trim();
            if (text.isEmpty() || ".".equals(text) || "..".equals(text) || text.indexOf('/') >= 0 || text.indexOf('\\') >= 0) {
                error.setText("Geçerli bir ad girin ('/' ve '\\' karakterleri kullanılamaz).");
                ev.consume();
            }
        });

        dialog.setResultConverter(btn -> btn == okType ? input.getText().trim() : null);
        return dialog.showAndWait();
    }

    public static ConflictResolution showConflictDialog(Window owner, String conflictingName, boolean existingIsFolder) {
        return showConflictDialog(owner, conflictingName, existingIsFolder, !existingIsFolder);
    }

    public static ConflictResolution showConflictDialog(Window owner, String conflictingName, boolean existingIsFolder, boolean allowOverwrite) {
        Dialog<ConflictResolution> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("İsim Çakışması");
        dialog.setHeaderText("Hedef klasörde '" + conflictingName + "' adında bir öğe zaten var.");
        styleDialog(dialog.getDialogPane());

        ButtonType overwriteType = new ButtonType("Üzerine Yaz", ButtonBar.ButtonData.OTHER);
        ButtonType renameType = new ButtonType("Yeniden Adlandır", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = ButtonType.CANCEL;
        dialog.getDialogPane().getButtonTypes().addAll(overwriteType, renameType, cancelType);

        TextField newNameField = new TextField(conflictingName);
        newNameField.setPromptText("Yeni ad girin");
        String noteText;
        if (existingIsFolder) {
            noteText = "Mevcut öğe bir klasör olduğu için üzerine yazılamaz; yeni ad verebilir veya iptal edebilirsiniz.";
        } else if (!allowOverwrite) {
            noteText = "Kopyalama işleminde çakışan öğe için yeni bir ad verebilir veya iptal edebilirsiniz.";
        } else {
            noteText = "Üzerine yazarsanız aynı dosya ID'si korunur ve değişmeyen bloklar tekrar şifrelenmez.";
        }
        Label noteLabel = new Label(noteText);
        noteLabel.setWrapText(true);
        noteLabel.getStyleClass().add("muted-text");

        Label errLabel = new Label();
        errLabel.setStyle("-fx-text-fill: #F87171;");

        VBox box = new VBox(10, noteLabel, new Label("Yeniden adlandırma için yeni ad:"), newNameField, errLabel);
        box.setPadding(new Insets(12));
        box.setPrefWidth(460);
        dialog.getDialogPane().setContent(box);

        Button overwriteBtn = (Button) dialog.getDialogPane().lookupButton(overwriteType);
        overwriteBtn.setDisable(!allowOverwrite || existingIsFolder);

        Button renameBtn = (Button) dialog.getDialogPane().lookupButton(renameType);
        renameBtn.getStyleClass().add("btn-primary");
        renameBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            String candidate = newNameField.getText().trim();
            if (candidate.isEmpty() || candidate.equalsIgnoreCase(conflictingName) || candidate.indexOf('/') >= 0 || candidate.indexOf('\\') >= 0) {
                errLabel.setText("Lütfen mevcut isimden farklı ve geçerli bir ad girin.");
                ev.consume();
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn == overwriteType) {
                return new ConflictResolution(ConflictAction.OVERWRITE, conflictingName);
            }
            if (btn == renameType) {
                return new ConflictResolution(ConflictAction.RENAME, newNameField.getText().trim());
            }
            return new ConflictResolution(ConflictAction.CANCEL, null);
        });

        return dialog.showAndWait().orElse(new ConflictResolution(ConflictAction.CANCEL, null));
    }

    public static void showSecurityDialog(
            Window owner,
            VaultSession session,
            ExecutorService backgroundExecutor,
            Runnable onDone,
            Consumer<Path> onVaultRenamed,
            Runnable onVaultDeleted) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Güvenlik ve Kasa Yönetimi");
        dialog.setHeaderText("Parola, kurtarma anahtarı (.key), kasa adı ve kalıcı silme işlemleri");
        styleDialog(dialog.getDialogPane());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        Argon2Params curParams = session.currentArgon2Params();
        String kdfDesc = curParams != null ? "Argon2id (" + curParams.memoryMiB() + " MiB RAM, " + curParams.iterations() + " iterasyon, " + curParams.parallelism() + " iş parçacığı)" : "-";
        Label cryptoInfo = new Label("Şifreleme: AES-256-GCM (128-bit tag, 96-bit sayaç nonce)\n" + "Parola KDF: " + kdfDesc + "\n"
                        + "Anahtar Hiyerarşisi: HKDF-SHA-256 (veri, metaveri, commit ve nonce anahtarları ayrı)\n" + "Kasa ID: " + FormatUtils.formatContainerId(session.header().containerId())
                        + " | Aktif Commit: #" + session.currentCommitSequence());
        cryptoInfo.setWrapText(true);
        cryptoInfo.getStyleClass().add("muted-text");

        // Tab 1: Change password
        PasswordField currentPw = new PasswordField();
        PasswordField newPw = new PasswordField();
        PasswordField confirmNewPw = new PasswordField();
        ComboBox<Argon2Preset> kdfCombo = new ComboBox<>();
        kdfCombo.getItems().addAll(Argon2Params.availablePresets());
        kdfCombo.getSelectionModel().selectFirst();
        kdfCombo.setMaxWidth(Double.MAX_VALUE);
        configurePresetCombo(kdfCombo);

        Label pwStatus = new Label();
        pwStatus.setWrapText(true);
        Button changePwBtn = new Button("Parolayı Güncelle");
        changePwBtn.getStyleClass().add("btn-primary");

        GridPane pwGrid = new GridPane();
        pwGrid.setHgap(10);
        pwGrid.setVgap(10);
        pwGrid.setPadding(new Insets(12));
        pwGrid.add(new Label("Mevcut Parola:"), 0, 0);
        pwGrid.add(currentPw, 1, 0);
        pwGrid.add(new Label("Yeni Parola:"), 0, 1);
        pwGrid.add(newPw, 1, 1);
        pwGrid.add(new Label("Yeni Parola Tekrar:"), 0, 2);
        pwGrid.add(confirmNewPw, 1, 2);
        pwGrid.add(new Label("Argon2id Profili:"), 0, 3);
        pwGrid.add(kdfCombo, 1, 3);
        pwGrid.add(changePwBtn, 1, 4);
        pwGrid.add(pwStatus, 0, 5, 2, 1);
        GridPane.setHgrow(currentPw, Priority.ALWAYS);

        changePwBtn.setOnAction(e -> {
            pwStatus.setStyle("-fx-text-fill: #94A3B8;");
            char[] cur = readChars(currentPw);
            char[] n1 = readChars(newPw);
            char[] n2 = readChars(confirmNewPw);
            if (cur.length == 0 || n1.length == 0) {
                SensitiveBytes.wipe(cur);
                SensitiveBytes.wipe(n1);
                SensitiveBytes.wipe(n2);
                pwStatus.setStyle("-fx-text-fill: #F87171;");
                pwStatus.setText("Mevcut ve yeni parola alanları boş olamaz.");
                return;
            }
            if (!Arrays.equals(n1, n2)) {
                SensitiveBytes.wipe(cur);
                SensitiveBytes.wipe(n1);
                SensitiveBytes.wipe(n2);
                pwStatus.setStyle("-fx-text-fill: #F87171;");
                pwStatus.setText("Yeni parolalar eşleşmiyor.");
                return;
            }
            SensitiveBytes.wipe(n2);
            Argon2Preset preset = kdfCombo.getValue();
            if (preset == null || !preset.available()) {
                SensitiveBytes.wipe(cur);
                SensitiveBytes.wipe(n1);
                pwStatus.setStyle("-fx-text-fill: #F87171;");
                pwStatus.setText("Seçilen Argon2id profili bu sistemde kullanılamıyor.");
                return;
            }
            currentPw.clear();
            newPw.clear();
            confirmNewPw.clear();
            changePwBtn.setDisable(true);
            pwStatus.setText("Parola slotu A/B güvenli geçişle güncelleniyor...");

            backgroundExecutor.submit(() -> {
                try {
                    session.changePassword(cur, n1, preset.params());
                    javafx.application.Platform.runLater(() -> {
                        changePwBtn.setDisable(false);
                        pwStatus.setStyle("-fx-text-fill: #4ADE80;");
                        pwStatus.setText("Parola başarıyla güncellendi (içerik yeniden şifrelenmedi, DEK slotu döndürüldü).");
                        if (onDone != null) onDone.run();
                    });
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> {
                        changePwBtn.setDisable(false);
                        pwStatus.setStyle("-fx-text-fill: #F87171;");
                        pwStatus.setText("Parola değiştirilemedi: " + ex.getMessage());
                    });
                } finally {
                    SensitiveBytes.wipe(cur);
                    SensitiveBytes.wipe(n1);
                }
            });
        });

        // Tab 2: Rotate Recovery Key
        TextField newKeyPath = new TextField();
        newKeyPath.setPromptText("Yeni .key kurtarma dosyası yolu");
        Button browseKeyBtn = new Button("Konum Seç...");
        browseKeyBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Yeni Kurtarma Anahtarı (.key)");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Kurtarma Anahtarı (*" + VaultConstants.RECOVERY_KEY_EXTENSION + ")", "*" + VaultConstants.RECOVERY_KEY_EXTENSION));
            chooser.setInitialFileName("yeni-kurtarma" + VaultConstants.RECOVERY_KEY_EXTENSION);
            File f = chooser.showSaveDialog(dialog.getOwner());
            if (f != null) {
                newKeyPath.setText(ensureExtension(f.getAbsolutePath(), VaultConstants.RECOVERY_KEY_EXTENSION));
            }
        });

        Label recStatus = new Label();
        recStatus.setWrapText(true);
        Button rotateKeyBtn = new Button("Yeni Kurtarma Anahtarı Oluştur");
        rotateKeyBtn.getStyleClass().add("btn-primary");
        rotateKeyBtn.setOnAction(e -> {
            String pathStr = newKeyPath.getText().trim();
            if (pathStr.isEmpty()) {
                recStatus.setStyle("-fx-text-fill: #F87171;");
                recStatus.setText("Lütfen yeni kurtarma anahtarı için bir dosya yolu seçin.");
                return;
            }
            rotateKeyBtn.setDisable(true);
            recStatus.setStyle("-fx-text-fill: #94A3B8;");
            recStatus.setText("Kurtarma anahtarı slotu yenileniyor...");
            backgroundExecutor.submit(() -> {
                try {
                    Path saved = session.rotateRecoveryKey(Path.of(pathStr));
                    Platform.runLater(() -> {
                        rotateKeyBtn.setDisable(false);
                        recStatus.setStyle("-fx-text-fill: #4ADE80;");
                        recStatus.setText("Yeni kurtarma anahtarı oluşturuldu: " + saved + ". Önceki kurtarma anahtarı geçersiz kılındı.");
                        if (onDone != null) onDone.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        rotateKeyBtn.setDisable(false);
                        recStatus.setStyle("-fx-text-fill: #F87171;");
                        recStatus.setText("Kurtarma anahtarı yenilenemedi: " + ex.getMessage());
                    });
                }
            });
        });

        HBox recRow = new HBox(8, newKeyPath, browseKeyBtn);
        HBox.setHgrow(newKeyPath, Priority.ALWAYS);
        VBox recBox = new VBox(10, new Label("Yeni kurtarma anahtarı üretildiğinde eski .key dosyası kalıcı olarak geçersiz olur."), recRow, rotateKeyBtn, recStatus);
        recBox.setPadding(new Insets(12));

        // Tab 3: Vault Rename & Delete (requires both password and .key)
        TextField renameNameField = new TextField(stripVaultExtension(session.vaultPath()));
        renameNameField.setPromptText("Yeni kasa adı");
        PasswordField renamePwField = new PasswordField();
        renamePwField.setPromptText("Kasa parolasını girin");
        TextField renameKeyField = new TextField();
        renameKeyField.setPromptText("Bu kasaya ait .key dosyasını seçin");
        HBox renameKeyRow = createOpenFilePickerRow(dialog.getOwner(), renameKeyField, "Kurtarma Anahtarı Seç", keyExtFilter(), null);
        Label renameStatus = new Label();
        renameStatus.setWrapText(true);
        Button renameVaultBtn = new Button("Kasa Adını Değiştir");
        renameVaultBtn.getStyleClass().add("btn-primary");

        renameVaultBtn.setOnAction(e -> {
            renameStatus.setStyle("-fx-text-fill: #94A3B8;");
            char[] pw = readChars(renamePwField);
            String keyStr = renameKeyField.getText().trim();
            if (pw.length == 0 || keyStr.isEmpty() || !Files.isRegularFile(Path.of(keyStr))) {
                SensitiveBytes.wipe(pw);
                renameStatus.setStyle("-fx-text-fill: #F87171;");
                renameStatus.setText("Ad değişikliği için kasa parolası ve geçerli bir .key dosyası gereklidir.");
                return;
            }
            String rawName = renameNameField.getText();
            try {
                VaultSession.validateVaultFileName(rawName);
            } catch (Exception ex) {
                SensitiveBytes.wipe(pw);
                renameStatus.setStyle("-fx-text-fill: #F87171;");
                renameStatus.setText(ex.getMessage());
                return;
            }
            renamePwField.clear();
            renameVaultBtn.setDisable(true);
            renameStatus.setText("Parola ve .key dosyası doğrulanıyor...");
            Path keyPath = Path.of(keyStr);

            backgroundExecutor.submit(() -> {
                try {
                    Path updatedPath = session.renameVault(rawName, pw, keyPath);
                    Platform.runLater(() -> {
                        renameVaultBtn.setDisable(false);
                        renameStatus.setStyle("-fx-text-fill: #4ADE80;");
                        renameStatus.setText("Kasa adı güncellendi: " + updatedPath.getFileName());
                        renameNameField.setText(stripVaultExtension(updatedPath));
                        if (onVaultRenamed != null) onVaultRenamed.accept(updatedPath);
                        if (onDone != null) onDone.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        renameVaultBtn.setDisable(false);
                        renameStatus.setStyle("-fx-text-fill: #F87171;");
                        renameStatus.setText("Kasa adı değiştirilemedi: " + ex.getMessage());
                    });
                } finally {
                    SensitiveBytes.wipe(pw);
                }
            });
        });

        GridPane renameGrid = new GridPane();
        renameGrid.setHgap(10);
        renameGrid.setVgap(8);
        renameGrid.add(new Label("Yeni Kasa Adı:"), 0, 0);
        renameGrid.add(renameNameField, 1, 0);
        renameGrid.add(new Label("Mevcut Parola:"), 0, 1);
        renameGrid.add(renamePwField, 1, 1);
        renameGrid.add(new Label("Kurtarma Anahtarı:"), 0, 2);
        renameGrid.add(renameKeyRow, 1, 2);
        renameGrid.add(renameVaultBtn, 1, 3);
        renameGrid.add(renameStatus, 0, 4, 2, 1);
        GridPane.setHgrow(renameNameField, Priority.ALWAYS);

        PasswordField deletePwField = new PasswordField();
        deletePwField.setPromptText("Kasa parolasını girin");
        TextField deleteKeyField = new TextField();
        deleteKeyField.setPromptText("Bu kasaya ait .key dosyasını seçin");
        HBox deleteKeyRow = createOpenFilePickerRow(dialog.getOwner(), deleteKeyField, "Kurtarma Anahtarı Seç", keyExtFilter(), null);
        CheckBox deleteKeyFileCheck = new CheckBox("Kurtarma anahtarı (.key) dosyasını da sil");
        CheckBox deleteConfirmCheck = new CheckBox("Açık olan bu kasayı kalıcı olarak silmeyi onaylıyorum");
        Label deleteStatus = new Label();
        deleteStatus.setWrapText(true);
        Button deleteVaultBtn = new Button("Kasayı Kalıcı Olarak Sil");
        deleteVaultBtn.getStyleClass().add("btn-danger");

        deleteVaultBtn.setOnAction(e -> {
            deleteStatus.setStyle("-fx-text-fill: #94A3B8;");
            char[] pw = readChars(deletePwField);
            String keyStr = deleteKeyField.getText().trim();
            if (pw.length == 0 || keyStr.isEmpty() || !Files.isRegularFile(Path.of(keyStr))) {
                SensitiveBytes.wipe(pw);
                deleteStatus.setStyle("-fx-text-fill: #F87171;");
                deleteStatus.setText("Silme işlemi için kasa parolası ve geçerli bir .key dosyası gereklidir.");
                return;
            }
            if (!deleteConfirmCheck.isSelected()) {
                SensitiveBytes.wipe(pw);
                deleteStatus.setStyle("-fx-text-fill: #F87171;");
                deleteStatus.setText("Lütfen kalıcı silme onay kutucuğunu işaretleyin.");
                return;
            }
            deletePwField.clear();
            deleteVaultBtn.setDisable(true);
            deleteStatus.setText("Kimlik doğrulanıyor ve kasa siliniyor...");
            Path keyPath = Path.of(keyStr);
            boolean removeKey = deleteKeyFileCheck.isSelected();

            backgroundExecutor.submit(() -> {
                try {
                    session.deleteVault(pw, keyPath, removeKey);
                    Platform.runLater(() -> {
                        dialog.close();
                        if (onVaultDeleted != null) onVaultDeleted.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        deleteVaultBtn.setDisable(false);
                        deleteStatus.setStyle("-fx-text-fill: #F87171;");
                        deleteStatus.setText("Kasa silinemedi: " + ex.getMessage());
                    });
                } finally {
                    SensitiveBytes.wipe(pw);
                }
            });
        });

        GridPane deleteGrid = new GridPane();
        deleteGrid.setHgap(10);
        deleteGrid.setVgap(8);
        deleteGrid.add(new Label("Mevcut Parola:"), 0, 0);
        deleteGrid.add(deletePwField, 1, 0);
        deleteGrid.add(new Label("Kurtarma Anahtarı:"), 0, 1);
        deleteGrid.add(deleteKeyRow, 1, 1);
        deleteGrid.add(deleteKeyFileCheck, 0, 2, 2, 1);
        deleteGrid.add(deleteConfirmCheck, 0, 3, 2, 1);
        deleteGrid.add(deleteVaultBtn, 1, 4);
        deleteGrid.add(deleteStatus, 0, 5, 2, 1);
        GridPane.setHgrow(deletePwField, Priority.ALWAYS);

        Label renameSectionTitle = new Label("Kasa Adını Değiştir (Parola + .key)");
        renameSectionTitle.getStyleClass().add("heading-md");
        Label deleteSectionTitle = new Label("Kasayı Kalıcı Olarak Sil (Parola + .key)");
        deleteSectionTitle.getStyleClass().add("heading-md");

        VBox manageBox = new VBox(12,
                renameSectionTitle,
                renameGrid,
                new javafx.scene.control.Separator(),
                deleteSectionTitle,
                deleteGrid);
        manageBox.setPadding(new Insets(12));

        Tab pwTab = new Tab("Parola Değiştir", pwGrid);
        pwTab.setClosable(false);
        Tab recTab = new Tab("Kurtarma Anahtarını Yenile", recBox);
        recTab.setClosable(false);
        Tab manageTab = new Tab("Kasa Yönetimi", manageBox);
        manageTab.setClosable(false);
        TabPane tabs = new TabPane(pwTab, recTab, manageTab);

        VBox root = new VBox(12, cryptoInfo, tabs);
        root.setPadding(new Insets(12));
        root.setPrefWidth(600);
        dialog.getDialogPane().setContent(root);
        dialog.showAndWait();
    }

    public static void showSettingsDialog(Window owner, AppSettings settings, VaultSession session, AutoLockMonitor autoLockMonitor, Runnable onSettingsApplied) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Performans ve Güvenlik Ayarları");
        dialog.setHeaderText("CPU profili, worker sınırları, otomatik kilitleme ve önbellek");
        styleDialog(dialog.getDialogPane());

        ButtonType saveType = new ButtonType("Kaydet ve Uygula", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        ComboBox<CpuProfile> cpuCombo = new ComboBox<>();
        cpuCombo.getItems().addAll(CpuProfile.values());
        cpuCombo.setValue(settings.cpuProfile());

        Spinner<Integer> workerSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 16, settings.maxWorkersOverride()));
        workerSpinner.setEditable(true);

        Label workerInfo = new Label();
        workerInfo.getStyleClass().add("muted-text");
        Runnable updateWorkerLabel = () -> {
            CpuProfile p = cpuCombo.getValue();
            int maxW = workerSpinner.getValue();
            workerInfo.setText("Etkin worker sayısı: " + p.computeWorkerCount(maxW) + " (0 = otomatik CPU/RAM uyarlaması)");
        };
        updateWorkerLabel.run();
        cpuCombo.valueProperty().addListener((o, a, b) -> updateWorkerLabel.run());
        workerSpinner.valueProperty().addListener((o, a, b) -> updateWorkerLabel.run());

        ComboBox<Integer> autoLockCombo = new ComboBox<>();
        autoLockCombo.getItems().addAll(0, 1, 5, 10, 15, 30, 60);
        autoLockCombo.setValue(settings.autoLockMinutes());
        autoLockCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : (item == 0 ? "Kapalı (yalnızca uyku/kapanışta kilitle)" : item + " dakika"));
            }
        });
        autoLockCombo.setButtonCell(autoLockCombo.getCellFactory().call(null));

        Label cacheLabel = new Label();
        Runnable refreshCacheLabel = () -> {
            if (session == null) {
                cacheLabel.setText("Açık kasa yok.");
            } else {
                BlockCache.CacheStats st = session.getCacheStats();
                cacheLabel.setText(String.format(
                        "Şifreli blok önbelleği: %s (%d blok) | Çözülmüş kısa ömürlü önbellek: %s (%d blok)",
                        FormatUtils.formatBytes(st.encryptedBytes()), st.encryptedEntries(),
                        FormatUtils.formatBytes(st.plaintextBytes()), st.plaintextEntries()));
            }
        };
        refreshCacheLabel.run();

        Button clearCacheBtn = new Button("Önbelleği Şimdi Temizle");
        clearCacheBtn.setDisable(session == null);
        clearCacheBtn.setOnAction(e -> {
            if (session != null) {
                session.clearCache();
                refreshCacheLabel.run();
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        grid.setPrefWidth(520);

        grid.add(new Label("CPU Profili:"), 0, 0);
        grid.add(cpuCombo, 1, 0);
        grid.add(new Label("Maks. Worker Sınırı:"), 0, 1);
        grid.add(workerSpinner, 1, 1);
        grid.add(workerInfo, 1, 2);
        grid.add(new Label("Hareketsizlik Kilidi:"), 0, 3);
        grid.add(autoLockCombo, 1, 3);
        grid.add(new Label("Önbellek Durumu:"), 0, 4);
        grid.add(cacheLabel, 1, 4);
        grid.add(clearCacheBtn, 1, 5);

        dialog.getDialogPane().setContent(grid);
        Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveType);
        saveBtn.getStyleClass().add("btn-primary");

        dialog.setResultConverter(btn -> btn == saveType);
        Optional<Boolean> res = dialog.showAndWait();
        if (res.orElse(false)) {
            settings.setCpuProfile(cpuCombo.getValue());
            settings.setMaxWorkersOverride(workerSpinner.getValue());
            settings.setAutoLockMinutes(autoLockCombo.getValue());
            settings.save();
            if (session != null) session.reconfigureWorkers(settings.cpuProfile(), settings.maxWorkersOverride());
            if (autoLockMonitor != null) autoLockMonitor.updateTimeoutMinutes(settings.autoLockMinutes());
            if (onSettingsApplied != null) onSettingsApplied.run();

        }
    }

    public static void showError(Window owner, String title, String userMessage, Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle(title == null ? "Hata" : title);
        alert.setHeaderText(userMessage);
        styleDialog(alert.getDialogPane());

        if (error != null) {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            TextArea details = new TextArea(sw.toString());
            details.setEditable(false);
            details.setWrapText(false);
            details.setPrefRowCount(10);
            details.setPrefWidth(540);
            alert.getDialogPane().setExpandableContent(new VBox(6, new Label("Teknik Hata Ayrıntısı:"), details));
        }
        alert.showAndWait();
    }

    public static boolean showConfirm(Window owner, String title, String header, String body) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(body);
        styleDialog(alert.getDialogPane());
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    public static void showInfo(Window owner, String title, String header, String body) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(body);
        styleDialog(alert.getDialogPane());
        alert.showAndWait();
    }

    public static void styleDialog(DialogPane pane) {
        var cssUrl = VaultDialogs.class.getResource(CSS_RESOURCE);
        if (cssUrl != null && !pane.getStylesheets().contains(cssUrl.toExternalForm())) {
            pane.getStylesheets().add(cssUrl.toExternalForm());
        }
        applyWindowIcon(pane);
    }

    private static void applyWindowIcon(DialogPane pane) {
        if (pane.getScene() != null && pane.getScene().getWindow() instanceof Stage stage) {
            ensureStageIcons(stage);
        }
        if (Boolean.TRUE.equals(pane.getProperties().get("sapphirypt.iconHook"))) {
            return;
        }
        pane.getProperties().put("sapphirypt.iconHook", Boolean.TRUE);
        pane.sceneProperty().addListener((obsScene, oldScene, newScene) -> {
            if (newScene == null) {
                return;
            }
            if (newScene.getWindow() instanceof Stage stage) {
                ensureStageIcons(stage);
            }
            newScene.windowProperty().addListener((obsWin, oldWin, newWin) -> {
                if (newWin instanceof Stage stage) {
                    ensureStageIcons(stage);
                }
            });
        });
    }

    private static void ensureStageIcons(Stage stage) {
        if (stage.getIcons().isEmpty()) {
            var icons = SapphireIcons.getApplicationIcons();
            if (!icons.isEmpty()) {
                stage.getIcons().setAll(icons);
            }
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
                } else {
                    setText(item.toString());
                    setDisable(!item.available());
                    setOpacity(item.available() ? 1.0 : 0.45);
                }
            }
        });
    }

    private static char[] readChars(PasswordField field) {
        CharSequence seq = field.getCharacters();
        char[] arr = new char[seq.length()];
        for (int i = 0; i < seq.length(); i++) {
            arr[i] = seq.charAt(i);
        }
        return arr;
    }

    private static char[] readAndClear(PasswordField field) {
        char[] arr = readChars(field);
        field.clear();
        return arr;
    }

    private static FileChooser.ExtensionFilter vaultExtFilter() {
        return new FileChooser.ExtensionFilter(
                "Sapphirypt Kasası (*" + VaultConstants.FILE_EXTENSION + ")",
                "*" + VaultConstants.FILE_EXTENSION);
    }

    private static FileChooser.ExtensionFilter keyExtFilter() {
        return new FileChooser.ExtensionFilter(
                "Kurtarma Anahtarı (*" + VaultConstants.RECOVERY_KEY_EXTENSION + ")",
                "*" + VaultConstants.RECOVERY_KEY_EXTENSION);
    }

    private static HBox createOpenFilePickerRow(
            Window owner,
            TextField targetField,
            String chooserTitle,
            FileChooser.ExtensionFilter filter,
            Runnable onPicked) {
        Button browseBtn = new Button("Gözat...");
        browseBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(chooserTitle);
            chooser.getExtensionFilters().add(filter);
            File chosen = chooser.showOpenDialog(owner);
            if (chosen != null) {
                targetField.setText(chosen.getAbsolutePath());
                if (onPicked != null) {
                    onPicked.run();
                }
            }
        });
        HBox row = new HBox(8, targetField, browseBtn);
        HBox.setHgrow(targetField, Priority.ALWAYS);
        return row;
    }

    private static String validateClosedVaultDualAuthInputs(
            String vaultPathStr, PasswordField pwField, String keyPathStr) {
        String vStr = vaultPathStr == null ? "" : vaultPathStr.trim();
        if (vStr.isEmpty() || !Files.isRegularFile(Path.of(vStr))) {
            return "Lütfen geçerli bir .sapphirypt kasa dosyası seçin.";
        }
        if (pwField.getCharacters().isEmpty()) {
            return "Lütfen kasa parolasını girin.";
        }
        String kStr = keyPathStr == null ? "" : keyPathStr.trim();
        if (kStr.isEmpty() || !Files.isRegularFile(Path.of(kStr))) {
            return "Lütfen bu kasaya ait geçerli bir .key kurtarma dosyası seçin.";
        }
        return null;
    }

    private static String stripVaultExtension(Path path) {
        if (path == null || path.getFileName() == null) return "";

        String fileName = path.getFileName().toString();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(VaultConstants.FILE_EXTENSION)) {
            return fileName.substring(0, fileName.length() - VaultConstants.FILE_EXTENSION.length());
        }
        return fileName;
    }

    private static String ensureExtension(String path, String ext) {
        return path.toLowerCase().endsWith(ext) ? path : path + ext;
    }

    private static String deriveDefaultKeyPath(String vaultPath) {
        if (vaultPath.toLowerCase().endsWith(VaultConstants.FILE_EXTENSION)) {
            return vaultPath.substring(0, vaultPath.length() - VaultConstants.FILE_EXTENSION.length()) + VaultConstants.RECOVERY_KEY_EXTENSION;
        }
        return vaultPath + VaultConstants.RECOVERY_KEY_EXTENSION;
    }

    private static String evaluatePasswordStrength(String pw) {
        if (pw == null || pw.isEmpty()) {
            return "-";
        }
        int score = 0;
        if (pw.length() >= 8) score++;
        if (pw.length() >= 14) score++;
        if (pw.chars().anyMatch(Character::isUpperCase)) score++;
        if (pw.chars().anyMatch(Character::isLowerCase)) score++;
        if (pw.chars().anyMatch(Character::isDigit)) score++;
        if (pw.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch))) score++;
        if (score <= 2) return "Zayıf";
        if (score <= 4) return "Orta";
        return "Güçlü";
    }
}
