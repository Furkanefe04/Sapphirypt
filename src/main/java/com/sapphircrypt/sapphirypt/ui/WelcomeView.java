package com.sapphircrypt.sapphirypt.ui;

import com.sapphircrypt.sapphirypt.storage.AppSettings;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Two-column modern Welcome Hub displaying brand identity and quick vault actions on the left,
 * interactive recent vault cards with inline actions on the right, and an integrated bottom bar.
 */
public final class WelcomeView extends BorderPane implements AutoCloseable {
    private final AppSettings settings;
    private final ThemeManager themeManager;
    private final Runnable onCreateVault;
    private final Consumer<Path> onOpenVault;
    private final Consumer<Path> onRenameVault;
    private final Consumer<Path> onDeleteVault;
    private final Runnable themeListener = this::refreshThemeSensitiveUi;

    private final ListView<Path> recentList = new ListView<>();
    private final Label recentCountBadge = new Label("0 kasa");
    private final Label statusLeftLabel = new Label();
    private final Label statusThemeLabel = new Label();

    private Node brandEmblem;
    private VBox leftColumn;
    private VBox rightColumn;
    private Timeline logoGlowTimeline;

    public WelcomeView(
            AppSettings settings,
            ThemeManager themeManager,
            Runnable onCreateVault,
            Consumer<Path> onOpenVault,
            Consumer<Path> onRenameVault,
            Consumer<Path> onDeleteVault) {
        this.settings = settings;
        this.themeManager = themeManager;
        this.onCreateVault = onCreateVault;
        this.onOpenVault = onOpenVault;
        this.onRenameVault = onRenameVault;
        this.onDeleteVault = onDeleteVault;

        buildLayout();
        themeManager.addListener(themeListener);
        refreshRecentVaults();
        refreshStatusBar();
    }

    /**
     * Plays the staggered vertical entrance transition for the two Welcome Hub columns.
     */
    public void playEntranceAnimation() {
        MotionFx.playEntrance(leftColumn, 14.0, 0.0, 260.0);
        MotionFx.playEntrance(rightColumn, 14.0, 75.0, 280.0);
    }

    private void buildLayout() {
        // Left column: Brand hero (logo + Sapphirypt side by side) + primary actions + external vault tools
        brandEmblem = SapphireIcons.createSapphireVaultEmblem(72);
        logoGlowTimeline = MotionFx.createSapphireBreathingGlow(brandEmblem, themeManager.isDarkNow());

        Label title = new Label("Sapphirypt");
        title.getStyleClass().add("welcome-brand-title");
        title.setMinWidth(Region.USE_PREF_SIZE);

        HBox brandRow = new HBox(14, brandEmblem, title);
        brandRow.setAlignment(Pos.CENTER_LEFT);

        Button createBtn = new Button("Yeni Kasa Oluştur");
        createBtn.getStyleClass().add("btn-primary");
        createBtn.setPrefHeight(44);
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setGraphic(SapphireIcons.plus(15, "#FFFFFF"));
        createBtn.setOnAction(e -> onCreateVault.run());
        MotionFx.attachPressSpring(createBtn);

        Button openBtn = new Button("Kasa Aç (.sapphirypt)");
        openBtn.setPrefHeight(44);
        openBtn.setMaxWidth(Double.MAX_VALUE);
        openBtn.setOnAction(e -> onOpenVault.accept(null));
        MotionFx.attachPressSpring(openBtn);

        MenuItem externalRenameItem = new MenuItem("Kasa Adını Değiştir (Parola + .key)");
        externalRenameItem.setOnAction(e -> onRenameVault.accept(null));
        MenuItem externalDeleteItem = new MenuItem("Kasayı Kalıcı Sil (Parola + .key)");
        externalDeleteItem.setOnAction(e -> onDeleteVault.accept(null));

        MenuButton manageExternalBtn = new MenuButton("Başka Kasa Yönet...", null, externalRenameItem, externalDeleteItem);
        manageExternalBtn.getStyleClass().add("btn-ghost");
        manageExternalBtn.setMaxWidth(Double.MAX_VALUE);

        VBox actionsBox = new VBox(10, createBtn, openBtn, manageExternalBtn);

        Region leftSpacer = new Region();
        VBox.setVgrow(leftSpacer, Priority.ALWAYS);

        leftColumn = new VBox(22, brandRow, actionsBox, leftSpacer);
        leftColumn.getStyleClass().add("hero-card");
        leftColumn.setPadding(new Insets(26));
        leftColumn.setPrefWidth(375);
        leftColumn.setMinWidth(345);
        leftColumn.setMaxWidth(405);

        // Right column: Recent vaults list with inline hover actions
        Label recentHeader = new Label("SON KASALAR");
        recentHeader.getStyleClass().add("section-title");
        recentCountBadge.getStyleClass().add("sapphire-badge");

        Region rSpacer = new Region();
        HBox.setHgrow(rSpacer, Priority.ALWAYS);
        HBox recentTopRow = new HBox(8, recentHeader, rSpacer, recentCountBadge);
        recentTopRow.setAlignment(Pos.CENTER_LEFT);

        configureRecentList();
        VBox.setVgrow(recentList, Priority.ALWAYS);

        rightColumn = new VBox(12, recentTopRow, recentList);
        rightColumn.getStyleClass().add("hero-card");
        rightColumn.setPadding(new Insets(24));
        HBox.setHgrow(rightColumn, Priority.ALWAYS);

        HBox hubColumns = new HBox(20, leftColumn, rightColumn);
        hubColumns.setAlignment(Pos.CENTER);
        hubColumns.setMaxWidth(1040);
        hubColumns.setMaxHeight(580);

        StackPane centerContainer = new StackPane(hubColumns);
        centerContainer.setPadding(new Insets(28, 32, 24, 32));
        setCenter(centerContainer);

        // Integrated bottom status bar
        statusLeftLabel.getStyleClass().add("muted-text");
        statusLeftLabel.setStyle("-fx-font-size: 11.5px;");
        statusThemeLabel.getStyleClass().add("muted-text");
        statusThemeLabel.setStyle("-fx-font-size: 11.5px;");

        Region stSpacer = new Region();
        HBox.setHgrow(stSpacer, Priority.ALWAYS);

        HBox statusBar = new HBox(10, statusLeftLabel, stSpacer, statusThemeLabel);
        statusBar.getStyleClass().add("integrated-status-bar");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        setBottom(statusBar);
    }

    private void configureRecentList() {
        Label emptyPlaceholder = new Label("Henüz açılmış veya oluşturulmuş bir kasa yok.\nSol taraftan yeni bir kasa oluşturabilir veya mevcut bir .sapphirypt dosyası açabilirsiniz.");
        emptyPlaceholder.getStyleClass().add("muted-text");
        emptyPlaceholder.setWrapText(true);
        emptyPlaceholder.setStyle("-fx-text-alignment: center; -fx-padding: 24px;");

        recentList.setPlaceholder(emptyPlaceholder);
        recentList.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-background-insets: 0;");
        recentList.setCellFactory(lv -> new RecentVaultCell());
        recentList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Path selected = recentList.getSelectionModel().getSelectedItem();
                if (selected != null && Files.isRegularFile(selected)) {
                    onOpenVault.accept(selected);
                }
            }
        });
        recentList.setContextMenu(buildContextMenuForSelection());
    }

    private final class RecentVaultCell extends ListCell<Path> {
        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);
            setStyle("-fx-background-color: transparent; -fx-padding: 4px 0;");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            boolean exists = Files.isRegularFile(item);
            String accent = themeManager.isDarkNow() ? "#60A5FA" : "#2563EB";
            String muted = themeManager.isDarkNow() ? "#64748B" : "#94A3B8";

            Node lockIcon = SapphireIcons.lock(18, exists ? accent : muted);

            Label nameLbl = new Label(item.getFileName() != null ? item.getFileName().toString() : item.toString());
            nameLbl.getStyleClass().add("primary-text");
            nameLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 13.5px;");

            Label pathLbl = new Label(item.getParent() != null ? item.getParent().toString() : item.toString());
            pathLbl.getStyleClass().add("muted-text");
            pathLbl.setStyle("-fx-font-size: 11px;");

            VBox infoCol = new VBox(2, nameLbl, pathLbl);
            HBox.setHgrow(infoCol, Priority.ALWAYS);

            if (!exists) {
                Label missingBadge = new Label("Dosya bulunamadı");
                missingBadge.getStyleClass().add("warning-badge");
                infoCol.getChildren().add(missingBadge);
            }

            Button openInlineBtn = new Button("Aç");
            openInlineBtn.getStyleClass().add("btn-primary");
            openInlineBtn.setPadding(new Insets(4, 12, 4, 12));
            openInlineBtn.setDisable(!exists);
            openInlineBtn.setOnAction(e -> onOpenVault.accept(item));
            MotionFx.attachPressSpring(openInlineBtn);

            MenuItem ctxOpen = new MenuItem("Kasa Aç");
            ctxOpen.setDisable(!exists);
            ctxOpen.setOnAction(e -> onOpenVault.accept(item));

            MenuItem ctxRename = new MenuItem("Adını Değiştir (Parola + .key)");
            ctxRename.setDisable(!exists);
            ctxRename.setOnAction(e -> onRenameVault.accept(item));

            MenuItem ctxDelete = new MenuItem("Kalıcı Sil (Parola + .key)");
            ctxDelete.setDisable(!exists);
            ctxDelete.setOnAction(e -> onDeleteVault.accept(item));

            MenuItem ctxRemove = new MenuItem("Listeden Kaldır");
            ctxRemove.setOnAction(e -> {
                settings.removeRecentVault(item);
                settings.save();
                refreshRecentVaults();
            });

            MenuButton moreBtn = new MenuButton();
            moreBtn.getStyleClass().add("icon-btn");
            moreBtn.setGraphic(SapphireIcons.moreHorizontal(15, themeManager.isDarkNow() ? "#CBD5E1" : "#334155"));
            moreBtn.setTooltip(new Tooltip("Kasa İşlemleri"));
            moreBtn.getItems().addAll(ctxOpen, new SeparatorMenuItem(), ctxRename, ctxDelete, new SeparatorMenuItem(), ctxRemove);

            HBox actions = new HBox(6, openInlineBtn, moreBtn);
            actions.setAlignment(Pos.CENTER_RIGHT);

            HBox rowCard = new HBox(12, lockIcon, infoCol, actions);
            rowCard.setAlignment(Pos.CENTER_LEFT);
            rowCard.getStyleClass().add("vault-row-card");
            rowCard.setPadding(new Insets(10, 14, 10, 14));
            MotionFx.attachHoverLift(rowCard, -1.5, 1.008);

            setGraphic(rowCard);
        }
    }

    private ContextMenu buildContextMenuForSelection() {
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

        MenuItem removeItem = new MenuItem("Son Listeden Kaldır");
        removeItem.setOnAction(e -> {
            Path sel = recentList.getSelectionModel().getSelectedItem();
            if (sel != null) {
                settings.removeRecentVault(sel);
                settings.save();
                refreshRecentVaults();
            }
        });

        ContextMenu menu = new ContextMenu(
                openItem, new SeparatorMenuItem(), renameItem, deleteItem, new SeparatorMenuItem(), removeItem);
        menu.setOnShowing(e -> {
            Path sel = recentList.getSelectionModel().getSelectedItem();
            boolean none = sel == null;
            boolean missing = none || !Files.isRegularFile(sel);
            openItem.setDisable(missing);
            renameItem.setDisable(missing);
            deleteItem.setDisable(missing);
            removeItem.setDisable(none);
        });
        return menu;
    }

    public void refreshRecentVaults() {
        var vaults = settings.recentVaults();
        recentList.getItems().setAll(vaults);
        recentCountBadge.setText(vaults.size() + " kasa");
        refreshStatusBar();
    }

    private void refreshThemeSensitiveUi() {
        if (logoGlowTimeline != null) {
            logoGlowTimeline.stop();
        }
        if (brandEmblem != null) {
            logoGlowTimeline = MotionFx.createSapphireBreathingGlow(brandEmblem, themeManager.isDarkNow());
        }
        recentList.refresh();
        refreshStatusBar();
    }

    @Override
    public void close() {
        themeManager.removeListener(themeListener);
        if (logoGlowTimeline != null) {
            logoGlowTimeline.stop();
            logoGlowTimeline = null;
        }
    }

    private void refreshStatusBar() {
        int count = settings.recentVaults().size();
        statusLeftLabel.setText(count + " kayıtlı kasa");
        statusThemeLabel.setText("Tema: " + themeManager.mode().label());
    }

    private static Label badge(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("sapphire-badge");
        return lbl;
    }
}
