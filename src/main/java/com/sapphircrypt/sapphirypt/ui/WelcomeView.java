package com.sapphircrypt.sapphirypt.ui;

import com.sapphircrypt.sapphirypt.storage.AppSettings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Welcome / Vault selection view displaying the Sapphire + Vault identity, quick actions,
 * vault rename/delete actions, and recent local vaults.
 */
public final class WelcomeView extends StackPane {
    private final AppSettings settings;
    private final Runnable onCreateVault;
    private final Consumer<Path> onOpenVault;
    private final Consumer<Path> onRenameVault;
    private final Consumer<Path> onDeleteVault;
    private final Runnable onOpenSettings;
    private final ListView<Path> recentList = new ListView<>();
    private final VBox busyOverlay = new VBox(12);
    private final Label busyLabel = new Label("İşlem yürütülüyor...");

    public WelcomeView(
            AppSettings settings,
            Runnable onCreateVault,
            Consumer<Path> onOpenVault,
            Consumer<Path> onRenameVault,
            Consumer<Path> onDeleteVault,
            Runnable onOpenSettings) {
        this.settings = settings;
        this.onCreateVault = onCreateVault;
        this.onOpenVault = onOpenVault;
        this.onRenameVault = onRenameVault;
        this.onDeleteVault = onDeleteVault;
        this.onOpenSettings = onOpenSettings;

        getStyleClass().add("app-surface");
        buildLayout();
        refreshRecentVaults();
    }

    private void buildLayout() {
        BorderPane frame = new BorderPane();

        Node emblem = SapphireIcons.createSapphireVaultEmblem(72);
        Label title = new Label("Sapphirypt");
        title.getStyleClass().add("heading-xl");

        Label subtitle = new Label(
                "Tek dosyalı (.sapphirypt), çevrimdışı, AES-256-GCM ve Argon2id korumalı şifreli masaüstü kasa.");
        subtitle.getStyleClass().add("muted-text");
        subtitle.setWrapText(true);

        HBox badges = new HBox(8,
                badge("AES-256-GCM"),
                badge("Argon2id + HKDF-SHA-256"),
                badge("Append-Only Commit"),
                badge("Çevrimdışı"));
        badges.setAlignment(Pos.CENTER_LEFT);

        HBox headerRow = new HBox(18, emblem, new VBox(6, title, subtitle, badges));
        headerRow.setAlignment(Pos.CENTER_LEFT);

        Button createBtn = new Button("Yeni Kasa Oluştur");
        createBtn.getStyleClass().add("btn-primary");
        createBtn.setPrefHeight(42);
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setOnAction(e -> onCreateVault.run());

        Button openBtn = new Button("Kasa Aç (.sapphirypt)");
        openBtn.setPrefHeight(42);
        openBtn.setMaxWidth(Double.MAX_VALUE);
        openBtn.setOnAction(e -> onOpenVault.accept(null));

        Button settingsBtn = new Button("Ayarlar");
        settingsBtn.setPrefHeight(42);
        settingsBtn.setOnAction(e -> onOpenSettings.run());

        HBox primaryActions = new HBox(12, createBtn, openBtn, settingsBtn);
        HBox.setHgrow(createBtn, Priority.ALWAYS);
        HBox.setHgrow(openBtn, Priority.ALWAYS);

        Button renameVaultBtn = new Button("Kasa Adını Değiştir");
        renameVaultBtn.setMaxWidth(Double.MAX_VALUE);
        renameVaultBtn.setOnAction(e -> onRenameVault.accept(recentList.getSelectionModel().getSelectedItem()));

        Button deleteVaultBtn = new Button("Kasayı Sil");
        deleteVaultBtn.getStyleClass().add("btn-danger");
        deleteVaultBtn.setMaxWidth(Double.MAX_VALUE);
        deleteVaultBtn.setOnAction(e -> onDeleteVault.accept(recentList.getSelectionModel().getSelectedItem()));

        HBox secondaryActions = new HBox(12, renameVaultBtn, deleteVaultBtn);
        HBox.setHgrow(renameVaultBtn, Priority.ALWAYS);
        HBox.setHgrow(deleteVaultBtn, Priority.ALWAYS);

        Label recentTitle = new Label("SON KASALAR");
        recentTitle.getStyleClass().add("section-title");

        recentList.setPrefHeight(190);
        recentList.setPlaceholder(new Label("Henüz açılmış bir kasa yok."));
        recentList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    boolean exists = Files.isRegularFile(item);
                    Label nameLbl = new Label(item.getFileName().toString() + (exists ? "" : " (bulunamadı)"));
                    nameLbl.setStyle(exists ? "-fx-font-weight: bold; -fx-text-fill: #F1F5F9;"
                            : "-fx-text-fill: #94A3B8;");
                    Label pathLbl = new Label(item.toString());
                    pathLbl.getStyleClass().add("muted-text");
                    pathLbl.setStyle("-fx-font-size: 11px;");
                    HBox row = new HBox(10,
                            SapphireIcons.lock(18, exists ? "#60A5FA" : "#64748B"),
                            new VBox(2, nameLbl, pathLbl));
                    row.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(row);
                }
            }
        });
        recentList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Path selected = recentList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    onOpenVault.accept(selected);
                }
            }
        });
        recentList.setContextMenu(buildRecentContextMenu());

        Label footerNote = new Label(
                "Apache License 2.0 • Kasa adı değiştirme ve silme için parola + .key dosyası gereklidir.");
        footerNote.getStyleClass().add("muted-text");
        footerNote.setStyle("-fx-font-size: 11px;");

        VBox heroCard = new VBox(
                18,
                headerRow,
                new VBox(10, primaryActions, secondaryActions),
                new VBox(8, recentTitle, recentList),
                footerNote);
        heroCard.getStyleClass().add("hero-card");
        heroCard.setPadding(new Insets(28));
        heroCard.setMaxWidth(680);

        StackPane centerWrap = new StackPane(heroCard);
        centerWrap.setPadding(new Insets(32));
        frame.setCenter(centerWrap);

        // Busy overlay while Argon2id key derivation runs on background thread
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(48, 48);
        busyLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #F8FAFC;");
        busyOverlay.getChildren().addAll(spinner, busyLabel);
        busyOverlay.setAlignment(Pos.CENTER);
        busyOverlay.setStyle("-fx-background-color: rgba(11, 17, 32, 0.82);");
        busyOverlay.setVisible(false);
        busyOverlay.setManaged(false);

        getChildren().addAll(frame, busyOverlay);
    }

    private ContextMenu buildRecentContextMenu() {
        MenuItem openItem = new MenuItem("Aç");
        openItem.setOnAction(e -> {
            Path sel = recentList.getSelectionModel().getSelectedItem();
            if (sel != null) onOpenVault.accept(sel);
        });

        MenuItem renameItem = new MenuItem("Kasa Adını Değiştir (Parola + .key)");
        renameItem.setOnAction(e -> {
            Path sel = recentList.getSelectionModel().getSelectedItem();
            if (sel != null) onRenameVault.accept(sel);
        });

        MenuItem deleteItem = new MenuItem("Kasayı Sil (Parola + .key)");
        deleteItem.setOnAction(e -> {
            Path sel = recentList.getSelectionModel().getSelectedItem();
            if (sel != null) onDeleteVault.accept(sel);
        });

        MenuItem removeFromListItem = new MenuItem("Son Listeden Kaldır");
        removeFromListItem.setOnAction(e -> {
            Path sel = recentList.getSelectionModel().getSelectedItem();
            if (sel != null) {
                settings.removeRecentVault(sel);
                settings.save();
                refreshRecentVaults();
            }
        });

        ContextMenu menu = new ContextMenu(
                openItem,
                new SeparatorMenuItem(),
                renameItem,
                deleteItem,
                new SeparatorMenuItem(),
                removeFromListItem);
        menu.setOnShowing(e -> {
            Path sel = recentList.getSelectionModel().getSelectedItem();
            boolean none = sel == null;
            boolean missing = none || !Files.isRegularFile(sel);
            openItem.setDisable(missing);
            renameItem.setDisable(missing);
            deleteItem.setDisable(missing);
            removeFromListItem.setDisable(none);
        });
        return menu;
    }

    public void refreshRecentVaults() {
        recentList.getItems().setAll(settings.recentVaults());
    }

    public void setBusy(boolean busy, String message) {
        busyLabel.setText(message == null ? "İşlem yürütülüyor..." : message);
        busyOverlay.setVisible(busy);
        busyOverlay.setManaged(busy);
    }

    private static Label badge(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("sapphire-badge");
        return lbl;
    }
}
