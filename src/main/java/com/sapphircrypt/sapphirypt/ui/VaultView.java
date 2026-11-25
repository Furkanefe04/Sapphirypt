package com.sapphircrypt.sapphirypt.ui;

import com.sapphircrypt.sapphirypt.format.VaultMetadata;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.StorageStats;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.VaultEntry;
import com.sapphircrypt.sapphirypt.storage.AppSettings;
import com.sapphircrypt.sapphirypt.storage.CancellationToken;
import com.sapphircrypt.sapphirypt.storage.ProgressListener;
import com.sapphircrypt.sapphirypt.storage.VaultCancelledException;
import com.sapphircrypt.sapphirypt.storage.VaultSession;
import com.sapphircrypt.sapphirypt.ui.UiShell.ToastType;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.ConflictAction;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.ConflictResolution;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Modern unlocked vault workspace supporting List + Grid file views, folder tree navigation,
 * compact visual storage breakdown, theme-aware preview inspector, integrated status bar,
 * and floating crypto operation pill.
 */
public final class VaultView extends StackPane implements AutoCloseable {

    private static final DataFormat INTERNAL_ENTRY_IDS = new DataFormat("application/x-sapphirypt-entry-ids");
    private static final long MAX_TEXT_PREVIEW_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_IMAGE_PREVIEW_BYTES = 30L * 1024L * 1024L;

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "json", "xml", "csv", "log", "java", "kt", "kts", "gradle",
            "py", "js", "ts", "html", "css", "yml", "yaml", "properties", "ini",
            "sql", "sh", "bat", "conf", "cfg", "toml");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "jfif", "gif", "bmp", "tif", "tiff", "webp");

    private enum ClipboardMode {
        COPY, CUT
    }

    private enum ViewMode {
        LIST, GRID
    }

    private record EntryRow(VaultEntry entry, long computedSize) {
    }

    private final VaultSession session;
    private final AppSettings settings;
    private final UiShell shell;
    private final ThemeManager themeManager;
    private final AutoLockMonitor autoLockMonitor;
    private final Consumer<Path> onVaultRenamed;
    private final Runnable onVaultDeleted;
    private final ExecutorService bgExecutor;
    private final ExecutorService previewExecutor;
    private final Runnable themeListener = this::refreshThemeSensitiveUi;

    private UUID currentFolderId = VaultMetadata.ROOT_ID;
    private final AtomicReference<CancellationToken> currentTaskToken = new AtomicReference<>();
    private final List<UUID> clipboardEntryIds = new ArrayList<>();
    private ClipboardMode clipboardMode;

    // View & selection state
    private ViewMode viewMode = ViewMode.GRID;
    private List<EntryRow> currentDisplayedRows = List.of();
    private final Set<UUID> gridSelectedIds = new LinkedHashSet<>();
    private Set<UUID> lastDisplayedIds = new LinkedHashSet<>();
    private UUID lastDisplayedFolderId;
    private String lastFilterSignature = "";
    private final Set<UUID> pendingAnimatedRowIds = new HashSet<>();

    // Top action bar components
    private final TreeView<VaultEntry> folderTree = new TreeView<>();
    private final HBox breadcrumbBar = new HBox(4);
    private final TextField searchField = new TextField();
    private final CheckBox globalSearchCheck = new CheckBox("Tüm kasada");
    private final ComboBox<String> extFilterCombo = new ComboBox<>();
    private final Button listModeBtn = new Button();
    private final Button gridModeBtn = new Button();
    private MotionFx.SegmentedSwitch viewSwitch;
    private final Button exportBtn = new Button("Dışa Aktar");
    private final ToggleButton previewToggle = new ToggleButton("Önizleme");

    // Center content views
    private final TableView<EntryRow> entryTable = new TableView<>();
    private final FlowPane gridFlow = new FlowPane(12, 12);
    private final ScrollPane gridScroll = new ScrollPane(gridFlow);
    private final StackPane contentSwitchPane = new StackPane();

    // Compact storage breakdown
    private final Label storageHeaderSummary = new Label("-");
    private final Region storageActiveBar = new Region();
    private final Region storageOrphanBar = new Region();
    private final HBox storageBarTrack = new HBox();
    private final Label storageShortLegend = new Label("-");
    private final VBox storageDetailsBox = new VBox(5);
    private final Label logicalSizeVal = new Label("-");
    private final Label physicalSizeVal = new Label("-");
    private final Label activeBytesVal = new Label("-");
    private final Label orphanBytesVal = new Label("-");
    private final Label reclaimableVal = new Label("-");

    // Inspector & preview pane
    private final VBox inspectorPane = new VBox(10);
    private final Label detailName = new Label("Öğe seçilmedi");
    private final Label detailMeta = new Label("Önizleme veya ayrıntı için bir dosya/klasör seçin.");
    private final StackPane previewContainer = new StackPane();
    private final Button openExternalBtn = new Button("Harici Uygulamada Aç");
    private final Button exportSelectedBtn = new Button("Dışa Aktar");

    // Integrated status bar & floating operation pill
    private final Label statusCountLbl = new Label("0 öğe");
    private final Label statusEnvLbl = new Label();
    private final HBox floatingProgressPill = new HBox(10);
    private final ProgressBar pillProgressBar = new ProgressBar(0);
    private final Label pillStatusLbl = new Label("Hazır");
    private final Button pillCancelBtn = new Button("İptal");

    private SplitPane mainSplit;
    private boolean inspectorVisible = true;
    private volatile UUID lastPreviewedId;

    public VaultView(
            VaultSession session,
            AppSettings settings,
            UiShell shell,
            ThemeManager themeManager,
            AutoLockMonitor autoLockMonitor,
            Consumer<Path> onVaultRenamed,
            Runnable onVaultDeleted) {
        this.session = session;
        this.settings = settings;
        this.shell = shell;
        this.themeManager = themeManager;
        this.autoLockMonitor = autoLockMonitor;
        this.onVaultRenamed = onVaultRenamed;
        this.onVaultDeleted = onVaultDeleted;
        this.bgExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sapphirypt-vault-ops");
            t.setDaemon(true);
            return t;
        });
        this.previewExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sapphirypt-preview");
            t.setDaemon(true);
            return t;
        });

        this.viewMode = settings.defaultGridView() ? ViewMode.GRID : ViewMode.LIST;
        this.inspectorVisible = settings.previewEnabledByDefault();

        buildUi();
        themeManager.addListener(themeListener);
        refreshAll();
    }

    public void openSecurityDrawer() {
        VaultDrawers.showSecurityDrawer(
                shell,
                window(),
                themeManager,
                session,
                bgExecutor,
                this::refreshMetadataOnly,
                newPath -> {
                    shell.setTitleText("Sapphirypt — " + newPath.getFileName());
                    if (onVaultRenamed != null) {
                        onVaultRenamed.accept(newPath);
                    }
                    refreshBadges();
                },
                () -> {
                    if (onVaultDeleted != null) {
                        onVaultDeleted.run();
                    }
                });
    }

    public void refreshBadges() {
        String lockDesc = settings.autoLockMinutes() == 0
                ? "Otomatik Kilit: Kapalı"
                : "Kilit: " + settings.autoLockMinutes() + " dk";
        statusEnvLbl.setText(session.cpuProfile().displayName() + " • " + lockDesc);
    }

    public void applyViewSettings() {
        setViewMode(settings.defaultGridView() ? ViewMode.GRID : ViewMode.LIST);
        toggleInspector(settings.previewEnabledByDefault());
    }

    private void buildUi() {
        BorderPane rootLayout = new BorderPane();
        rootLayout.setTop(buildActionToolbar());
        rootLayout.setBottom(buildIntegratedStatusBar());

        VBox leftSidebar = buildSidebar();
        VBox centerArea = buildCenterArea();
        buildInspectorPane();

        if (inspectorVisible) {
            mainSplit = new SplitPane(leftSidebar, centerArea, inspectorPane);
            mainSplit.setOrientation(Orientation.HORIZONTAL);
            mainSplit.setDividerPositions(0.21, 0.74);
        } else {
            mainSplit = new SplitPane(leftSidebar, centerArea);
            mainSplit.setOrientation(Orientation.HORIZONTAL);
            mainSplit.setDividerPositions(0.24);
        }
        rootLayout.setCenter(mainSplit);

        buildFloatingProgressPill();
        StackPane.setAlignment(floatingProgressPill, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(floatingProgressPill, new Insets(0, 18, 42, 18));

        getChildren().addAll(rootLayout, floatingProgressPill);
    }

    private Node buildActionToolbar() {
        MenuItem addFilesItem = new MenuItem("Dosya Ekle...");
        addFilesItem.setOnAction(e -> handleAddFiles());
        MenuItem addFolderItem = new MenuItem("Klasör Ekle...");
        addFolderItem.setOnAction(e -> handleAddDirectory());
        MenuItem newFolderItem = new MenuItem("Yeni Klasör");
        newFolderItem.setOnAction(e -> handleCreateFolder());

        MenuButton addMenuBtn = new MenuButton("Ekle", SapphireIcons.plus(14, "#FFFFFF"), addFilesItem, addFolderItem, new SeparatorMenuItem(), newFolderItem);
        addMenuBtn.getStyleClass().add("btn-primary");
        MotionFx.attachPressSpring(addMenuBtn);

        MotionFx.attachPressSpring(exportBtn);
        exportBtn.setOnAction(e -> handleExportSelected());

        searchField.setPromptText("Kasada ara...");
        searchField.setPrefWidth(220);
        searchField.textProperty().addListener((o, a, b) -> refreshTableOnly());

        MotionFx.enhanceCheckBox(globalSearchCheck);
        globalSearchCheck.selectedProperty().addListener((o, a, b) -> refreshTableOnly());

        extFilterCombo.getItems().add("Tüm Türler");
        extFilterCombo.setValue("Tüm Türler");
        MotionFx.enhanceComboBox(extFilterCombo);
        extFilterCombo.valueProperty().addListener((o, a, b) -> refreshTableOnly());

        listModeBtn.getStyleClass().add("segmented-btn");
        listModeBtn.setTooltip(new Tooltip("Liste Görünümü"));
        listModeBtn.setOnAction(e -> setViewMode(ViewMode.LIST));

        gridModeBtn.getStyleClass().add("segmented-btn");
        gridModeBtn.setTooltip(new Tooltip("Kart / Izgara Görünümü"));
        gridModeBtn.setOnAction(e -> setViewMode(ViewMode.GRID));

        viewSwitch = new MotionFx.SegmentedSwitch(2, listModeBtn, gridModeBtn);
        viewSwitch.setActive(viewMode == ViewMode.LIST ? listModeBtn : gridModeBtn, false);

        previewToggle.setSelected(inspectorVisible);
        MotionFx.attachPressSpring(previewToggle);
        previewToggle.setOnAction(e -> toggleInspector(previewToggle.isSelected()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(10, addMenuBtn, exportBtn, new Separator(Orientation.VERTICAL), searchField, globalSearchCheck, extFilterCombo, spacer, viewSwitch, previewToggle);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar-bar");
        updateToolbarIcons();
        return bar;
    }

    private VBox buildSidebar() {
        Label treeHeader = new Label("KLASÖRLER");
        treeHeader.getStyleClass().add("section-title");

        folderTree.setShowRoot(true);
        folderTree.setCellFactory(tv -> {
            TreeCell<VaultEntry> cell = new TreeCell<>() {
                @Override
                protected void updateItem(VaultEntry item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        setText(item.name());
                        setGraphic(SapphireIcons.folder(15, shell.accentIconColor()));
                    }
                }
            };

            cell.setOnDragOver(ev -> {
                VaultEntry targetFolder = cell.getItem();
                if (targetFolder != null && ev.getDragboard().hasContent(INTERNAL_ENTRY_IDS)) {
                    ev.acceptTransferModes(TransferMode.MOVE);
                    cell.getStyleClass().add("drop-target-active");
                }
                ev.consume();
            });
            cell.setOnDragExited(ev -> cell.getStyleClass().remove("drop-target-active"));
            cell.setOnDragDropped(ev -> {
                cell.getStyleClass().remove("drop-target-active");
                VaultEntry targetFolder = cell.getItem();
                Dragboard db = ev.getDragboard();
                boolean ok = false;
                if (targetFolder != null && db.hasContent(INTERNAL_ENTRY_IDS)) {
                    @SuppressWarnings("unchecked")
                    List<String> rawIds = (List<String>) db.getContent(INTERNAL_ENTRY_IDS);
                    List<UUID> ids = rawIds.stream().map(UUID::fromString).toList();
                    moveEntriesAsync(ids, targetFolder.id());
                    ok = true;
                }
                ev.setDropCompleted(ok);
                ev.consume();
            });
            return cell;
        });

        folderTree.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (newItem != null && newItem.getValue() != null) {
                currentFolderId = newItem.getValue().id();
                refreshTableAndBreadcrumbs();
            }
        });
        VBox.setVgrow(folderTree, Priority.ALWAYS);

        // Compact visual storage card
        Label statsHeader = new Label("DEPOLAMA");
        statsHeader.getStyleClass().add("section-title");
        storageHeaderSummary.getStyleClass().add("primary-text");
        storageHeaderSummary.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        Region stSp = new Region();
        HBox.setHgrow(stSp, Priority.ALWAYS);
        HBox storageHeadRow = new HBox(6, statsHeader, stSp, storageHeaderSummary);
        storageHeadRow.setAlignment(Pos.CENTER_LEFT);

        storageActiveBar.getStyleClass().add("storage-bar-active");
        storageActiveBar.setMinHeight(7);
        storageActiveBar.setPrefHeight(7);
        storageOrphanBar.getStyleClass().add("storage-bar-orphan");
        storageOrphanBar.setMinHeight(7);
        storageOrphanBar.setPrefHeight(7);

        storageBarTrack.getStyleClass().add("storage-bar-track");
        storageBarTrack.setMinHeight(7);
        storageBarTrack.setMaxHeight(7);
        storageBarTrack.getChildren().setAll(storageActiveBar, storageOrphanBar);
        storageBarTrack.widthProperty().addListener((o, a, b) -> refreshStorageStats());

        storageShortLegend.getStyleClass().add("muted-text");
        storageShortLegend.setStyle("-fx-font-size: 11px;");
        storageShortLegend.setWrapText(true);

        storageDetailsBox.getChildren().setAll(
                statRow("Dosya Boyutu:", logicalSizeVal),
                statRow("Kasa Dosyası:", physicalSizeVal),
                statRow("Kullanılan Alan:", activeBytesVal),
                statRow("Boşta Alan:", orphanBytesVal),
                statRow("Kazanılabilecek Alan:", reclaimableVal));
        storageDetailsBox.setVisible(false);
        storageDetailsBox.setManaged(false);
        storageDetailsBox.setPadding(new Insets(4, 0, 2, 0));

        Button compactBtn = new Button("Sıkıştır");
        compactBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(compactBtn, Priority.ALWAYS);
        MotionFx.attachPressSpring(compactBtn);
        compactBtn.setOnAction(e -> handleCompact());

        Button detailsToggleBtn = new Button("Detaylar");
        detailsToggleBtn.getStyleClass().add("btn-ghost");
        detailsToggleBtn.setOnAction(e -> {
            boolean show = !storageDetailsBox.isVisible();
            storageDetailsBox.setVisible(show);
            storageDetailsBox.setManaged(show);
            detailsToggleBtn.setText(show ? "Gizle" : "Detaylar");
            if (show) {
                MotionFx.playEntrance(storageDetailsBox, -6.0, 0.0, 180.0);
            }
        });

        HBox storageBtnRow = new HBox(6, compactBtn, detailsToggleBtn);
        storageBtnRow.setAlignment(Pos.CENTER_LEFT);

        VBox compactStorageCard = new VBox(8, storageHeadRow, storageBarTrack, storageShortLegend, storageDetailsBox, storageBtnRow);
        compactStorageCard.getStyleClass().add("panel-card");
        compactStorageCard.setPadding(new Insets(12));

        VBox sidebar = new VBox(10, treeHeader, folderTree, compactStorageCard);
        sidebar.getStyleClass().add("sidebar-pane");
        sidebar.setPadding(new Insets(12));
        sidebar.setMinWidth(230);
        sidebar.setPrefWidth(256);
        return sidebar;
    }

    private HBox statRow(String label, Label valueLabel) {
        Label k = new Label(label);
        k.getStyleClass().add("muted-text");
        k.setStyle("-fx-font-size: 11px;");
        valueLabel.getStyleClass().add("primary-text");
        valueLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        return new HBox(6, k, sp, valueLabel);
    }

    private VBox buildCenterArea() {
        breadcrumbBar.setAlignment(Pos.CENTER_LEFT);
        Button upBtn = new Button("Üst");
        upBtn.getStyleClass().add("btn-ghost");
        upBtn.setOnAction(e -> navigateUp());

        HBox navRow = new HBox(8, upBtn, new Separator(Orientation.VERTICAL), breadcrumbBar);
        navRow.setAlignment(Pos.CENTER_LEFT);

        configureTable();
        configureGrid();

        boolean isList = viewMode == ViewMode.LIST;
        entryTable.setVisible(isList);
        entryTable.setManaged(isList);
        gridScroll.setVisible(!isList);
        gridScroll.setManaged(!isList);
        contentSwitchPane.getChildren().setAll(entryTable, gridScroll);
        VBox.setVgrow(contentSwitchPane, Priority.ALWAYS);

        Label dropHint = new Label("İpucu: Masaüstünden dosya veya klasörleri doğrudan bu alana sürükleyip bırakabilirsiniz.");
        dropHint.getStyleClass().add("muted-text");
        dropHint.setStyle("-fx-font-size: 11px;");

        VBox center = new VBox(10, navRow, contentSwitchPane, dropHint);
        center.setPadding(new Insets(12));
        return center;
    }

    private void configureTable() {
        entryTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        Label emptyLbl = new Label("Bu klasör boş. Dosya ekleyin veya sürükleyip bırakın.");
        emptyLbl.getStyleClass().add("muted-text");
        entryTable.setPlaceholder(emptyLbl);

        TableColumn<EntryRow, EntryRow> nameCol = new TableColumn<>("Ad");
        nameCol.setPrefWidth(270);
        nameCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        nameCol.setComparator(Comparator.comparing((EntryRow r) -> r.entry().isFolder() ? 0 : 1)
                .thenComparing(r -> r.entry().name().toLowerCase(Locale.ROOT)));
        nameCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(EntryRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(row.entry().name());
                    setGraphic(row.entry().isFolder()
                            ? SapphireIcons.folder(16, shell.accentIconColor())
                            : SapphireIcons.file(16, shell.mutedIconColor()));
                }
            }
        });

        TableColumn<EntryRow, String> typeCol = new TableColumn<>("Tür");
        typeCol.setPrefWidth(90);
        typeCol.setCellValueFactory(data -> {
            VaultEntry e = data.getValue().entry();
            if (e.isFolder()) return new ReadOnlyStringWrapper("Klasör");
            String ext = e.extensionLower();
            return new ReadOnlyStringWrapper(ext.isEmpty() ? "Dosya" : ext.toUpperCase(Locale.ROOT));
        });

        TableColumn<EntryRow, Long> sizeCol = new TableColumn<>("Boyut");
        sizeCol.setPrefWidth(125);
        sizeCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().computedSize()));
        sizeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Long val, boolean empty) {
                super.updateItem(val, empty);
                setText(empty || val == null ? null : FormatUtils.formatBytes(val));
            }
        });

        TableColumn<EntryRow, Long> modifiedCol = new TableColumn<>("Değiştirilme");
        modifiedCol.setPrefWidth(145);
        modifiedCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().entry().modifiedAt()));
        modifiedCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Long val, boolean empty) {
                super.updateItem(val, empty);
                setText(empty || val == null ? null : FormatUtils.formatTimestamp(val));
            }
        });

        entryTable.getColumns().setAll(List.of(nameCol, typeCol, sizeCol, modifiedCol));

        entryTable.getSelectionModel().selectedItemProperty().addListener((o, oldRow, newRow) -> {
            updateInspector(newRow);
            refreshStatusCounts();
        });

        entryTable.setRowFactory(tv -> {
            TableRow<EntryRow> row = new TableRow<>() {
                @Override
                protected void updateItem(EntryRow item, boolean empty) {
                    super.updateItem(item, empty);
                    if (!empty && item != null && pendingAnimatedRowIds.remove(item.entry().id())) {
                        MotionFx.playEntrance(this, 8.0, 0.0, 210.0);
                    } else {
                        setOpacity(1.0);
                        setScaleX(1.0);
                        setScaleY(1.0);
                        setTranslateY(0.0);
                    }
                }
            };
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    openOrPreviewEntry(row.getItem().entry());
                }
            });
            row.setOnDragDetected(ev -> {
                List<EntryRow> selected = new ArrayList<>(entryTable.getSelectionModel().getSelectedItems());
                if (selected.isEmpty() && !row.isEmpty()) {
                    selected.add(row.getItem());
                }
                if (!selected.isEmpty()) {
                    Dragboard db = row.startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent content = new ClipboardContent();
                    content.put(INTERNAL_ENTRY_IDS, selected.stream().map(r -> r.entry().id().toString()).toList());
                    db.setContent(content);
                }
                ev.consume();
            });
            row.setOnDragOver(ev -> {
                if (!row.isEmpty() && row.getItem().entry().isFolder() && ev.getDragboard().hasContent(INTERNAL_ENTRY_IDS)) {
                    ev.acceptTransferModes(TransferMode.MOVE);
                    row.getStyleClass().add("drop-target-active");
                    ev.consume();
                }
            });
            row.setOnDragExited(ev -> row.getStyleClass().remove("drop-target-active"));
            row.setOnDragDropped(ev -> {
                row.getStyleClass().remove("drop-target-active");
                if (!row.isEmpty() && row.getItem().entry().isFolder() && ev.getDragboard().hasContent(INTERNAL_ENTRY_IDS)) {
                    @SuppressWarnings("unchecked")
                    List<String> rawIds = (List<String>) ev.getDragboard().getContent(INTERNAL_ENTRY_IDS);
                    moveEntriesAsync(rawIds.stream().map(UUID::fromString).toList(), row.getItem().entry().id());
                    ev.setDropCompleted(true);
                    ev.consume();
                }
            });
            return row;
        });

        configureExternalDrop(entryTable);
        entryTable.setContextMenu(buildContextMenu());
        attachKeyboardShortcuts(entryTable);
    }

    private void configureGrid() {
        gridFlow.setPadding(new Insets(12));
        gridFlow.setAlignment(Pos.TOP_LEFT);
        gridScroll.setFitToWidth(true);
        gridScroll.setContextMenu(buildContextMenu());
        gridScroll.setOnMouseClicked(ev -> {
            if (ev.getTarget() == gridFlow || ev.getTarget() == gridScroll) {
                gridSelectedIds.clear();
                updateGridSelectionStyles();
                updateInspector(null);
                refreshStatusCounts();
            }
        });
        configureExternalDrop(gridScroll);
        attachKeyboardShortcuts(gridScroll);
    }

    private void configureExternalDrop(Node dropNode) {
        dropNode.setOnDragOver(ev -> {
            if (ev.getDragboard().hasFiles()) {
                ev.acceptTransferModes(TransferMode.COPY);
                dropNode.getStyleClass().add("drop-target-active");
            }
            ev.consume();
        });
        dropNode.setOnDragExited(ev -> dropNode.getStyleClass().remove("drop-target-active"));
        dropNode.setOnDragDropped(ev -> {
            dropNode.getStyleClass().remove("drop-target-active");
            Dragboard db = ev.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                List<Path> paths = db.getFiles().stream().map(File::toPath).toList();
                importPathsAsync(paths, currentFolderId);
                success = true;
            }
            ev.setDropCompleted(success);
            ev.consume();
        });
    }

    private void attachKeyboardShortcuts(Node target) {
        KeyCombination ctrlC = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        KeyCombination ctrlX = new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN);
        KeyCombination ctrlV = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);
        target.setOnKeyPressed(ev -> {
            if (ev.getCode() == KeyCode.F2) {
                handleRenameSelected();
            } else if (ev.getCode() == KeyCode.DELETE) {
                handleDeleteSelected();
            } else if (ev.getCode() == KeyCode.ENTER) {
                EntryRow sel = primarySelectedRow();
                if (sel != null) {
                    openOrPreviewEntry(sel.entry());
                }
            } else if (ev.getCode() == KeyCode.BACK_SPACE) {
                navigateUp();
            } else if (ctrlC.match(ev)) {
                handleClipboard(ClipboardMode.COPY);
            } else if (ctrlX.match(ev)) {
                handleClipboard(ClipboardMode.CUT);
            } else if (ctrlV.match(ev)) {
                handlePaste();
            }
        });
    }

    private void setViewMode(ViewMode next) {
        if (next == viewMode) {
            return;
        }
        viewMode = next;
        boolean isList = viewMode == ViewMode.LIST;
        entryTable.setVisible(isList);
        entryTable.setManaged(isList);
        gridScroll.setVisible(!isList);
        gridScroll.setManaged(!isList);

        viewSwitch.setActive(isList ? listModeBtn : gridModeBtn, true);
        if (!isList) {
            rebuildGridCards();
        }
        MotionFx.playEntrance(isList ? entryTable : gridScroll, 8.0, 0.0, 190.0);
        updateToolbarIcons();
        refreshStatusCounts();
    }

    private void rebuildGridCards() {
        rebuildGridCards(Set.of());
    }

    private void rebuildGridCards(Set<UUID> newlyAddedIds) {
        gridFlow.getChildren().clear();
        if (currentDisplayedRows.isEmpty()) {
            Label emptyLbl = new Label("Bu klasör boş. Dosya ekleyin veya sürükleyip bırakın.");
            emptyLbl.getStyleClass().add("muted-text");
            emptyLbl.setPadding(new Insets(24));
            gridFlow.getChildren().add(emptyLbl);
            return;
        }

        int addedAnimIdx = 0;
        for (EntryRow row : currentDisplayedRows) {
            VaultEntry e = row.entry();
            boolean selected = gridSelectedIds.contains(e.id());

            Node icon = e.isFolder()
                    ? SapphireIcons.folder(38, shell.accentIconColor())
                    : SapphireIcons.file(38, shell.mutedIconColor());

            Label nameLbl = new Label(e.name());
            nameLbl.getStyleClass().add("primary-text");
            nameLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
            nameLbl.setMaxWidth(124);
            nameLbl.setAlignment(Pos.CENTER);

            String metaText = e.isFolder() ? "Klasör" : FormatUtils.formatBytes(row.computedSize());
            Label metaLbl = new Label(metaText);
            metaLbl.getStyleClass().add("muted-text");
            metaLbl.setStyle("-fx-font-size: 11px;");

            VBox card = new VBox(8, icon, nameLbl, metaLbl);
            card.setAlignment(Pos.CENTER);
            card.setPrefSize(148, 128);
            card.setPadding(new Insets(12));
            card.setUserData(e.id());
            card.getStyleClass().add(selected ? "grid-item-card-selected" : "grid-item-card");

            card.setOnMouseClicked(ev -> {
                gridScroll.requestFocus();
                if (ev.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                    if (!gridSelectedIds.contains(e.id())) {
                        gridSelectedIds.clear();
                        gridSelectedIds.add(e.id());
                        updateGridSelectionStyles();
                    }
                    updateInspector(row);
                    refreshStatusCounts();
                    return;
                }
                if (ev.isControlDown() || ev.isMetaDown()) {
                    if (gridSelectedIds.contains(e.id())) {
                        gridSelectedIds.remove(e.id());
                    } else {
                        gridSelectedIds.add(e.id());
                    }
                } else {
                    gridSelectedIds.clear();
                    gridSelectedIds.add(e.id());
                }
                updateGridSelectionStyles();
                updateInspector(primarySelectedRow());
                refreshStatusCounts();

                if (ev.getClickCount() == 2) {
                    openOrPreviewEntry(e);
                }
                ev.consume();
            });

            card.setOnDragDetected(ev -> {
                if (!gridSelectedIds.contains(e.id())) {
                    gridSelectedIds.clear();
                    gridSelectedIds.add(e.id());
                }
                Dragboard db = card.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.put(INTERNAL_ENTRY_IDS, gridSelectedIds.stream().map(UUID::toString).toList());
                db.setContent(content);
                ev.consume();
            });

            if (e.isFolder()) {
                card.setOnDragOver(ev -> {
                    if (ev.getDragboard().hasContent(INTERNAL_ENTRY_IDS)) {
                        ev.acceptTransferModes(TransferMode.MOVE);
                        card.getStyleClass().add("drop-target-active");
                        ev.consume();
                    }
                });
                card.setOnDragExited(ev -> card.getStyleClass().remove("drop-target-active"));
                card.setOnDragDropped(ev -> {
                    card.getStyleClass().remove("drop-target-active");
                    if (ev.getDragboard().hasContent(INTERNAL_ENTRY_IDS)) {
                        @SuppressWarnings("unchecked")
                        List<String> rawIds = (List<String>) ev.getDragboard().getContent(INTERNAL_ENTRY_IDS);
                        moveEntriesAsync(rawIds.stream().map(UUID::fromString).toList(), e.id());
                        ev.setDropCompleted(true);
                        ev.consume();
                    }
                });
            }

            MotionFx.attachHoverLift(card, -2.5, 1.02);
            if (newlyAddedIds != null && newlyAddedIds.contains(e.id()) && addedAnimIdx < 12) {
                playGridCardPopIn(card, addedAnimIdx * 22.0);
                addedAnimIdx++;
            }
            gridFlow.getChildren().add(card);
        }
    }

    private static void playGridCardPopIn(Node card, double delayMs) {
        card.setOpacity(0.0);
        card.setScaleX(0.88);
        card.setScaleY(0.88);
        card.setTranslateY(12.0);

        FadeTransition fade = new FadeTransition(Duration.millis(220), card);
        fade.setToValue(1.0);
        fade.setInterpolator(MotionFx.EASE_OUT_EXPO);

        ScaleTransition scale = new ScaleTransition(Duration.millis(220), card);
        scale.setToX(1.0);
        scale.setToY(1.0);
        scale.setInterpolator(MotionFx.EASE_OUT_BACK);

        TranslateTransition slide = new TranslateTransition(Duration.millis(220), card);
        slide.setToY(0.0);
        slide.setInterpolator(MotionFx.EASE_OUT_EXPO);

        ParallelTransition pt = new ParallelTransition(fade, scale, slide);
        if (delayMs > 0.0) {
            pt.setDelay(Duration.millis(delayMs));
        }
        pt.setOnFinished(e -> {
            card.setOpacity(1.0);
            card.setScaleX(1.0);
            card.setScaleY(1.0);
            card.setTranslateY(0.0);
        });
        pt.play();
    }

    private void updateGridSelectionStyles() {
        for (Node node : gridFlow.getChildren()) {
            if (node.getUserData() instanceof UUID id) {
                boolean selected = gridSelectedIds.contains(id);
                node.getStyleClass().removeAll("grid-item-card", "grid-item-card-selected");
                node.getStyleClass().add(selected ? "grid-item-card-selected" : "grid-item-card");
            }
        }
    }

    private List<EntryRow> currentSelectedRows() {
        if (viewMode == ViewMode.LIST) {
            return new ArrayList<>(entryTable.getSelectionModel().getSelectedItems());
        }
        List<EntryRow> out = new ArrayList<>();
        for (EntryRow r : currentDisplayedRows) {
            if (gridSelectedIds.contains(r.entry().id())) {
                out.add(r);
            }
        }
        return out;
    }

    private EntryRow primarySelectedRow() {
        if (viewMode == ViewMode.LIST) {
            return entryTable.getSelectionModel().getSelectedItem();
        }
        List<EntryRow> sel = currentSelectedRows();
        return sel.isEmpty() ? null : sel.get(0);
    }

    private ContextMenu buildContextMenu() {
        MenuItem previewItem = new MenuItem("Önizle / Aç");
        previewItem.setOnAction(e -> {
            EntryRow sel = primarySelectedRow();
            if (sel != null) openOrPreviewEntry(sel.entry());
        });

        MenuItem externalItem = new MenuItem("Harici Uygulamada Aç");
        externalItem.setOnAction(e -> handleOpenExternal());

        MenuItem exportItem = new MenuItem("Dışa Aktar...");
        exportItem.setOnAction(e -> handleExportSelected());

        MenuItem renameItem = new MenuItem("Yeniden Adlandır (F2)");
        renameItem.setOnAction(e -> handleRenameSelected());

        MenuItem copyItem = new MenuItem("Kopyala (Ctrl+C)");
        copyItem.setOnAction(e -> handleClipboard(ClipboardMode.COPY));

        MenuItem cutItem = new MenuItem("Kes / Taşı (Ctrl+X)");
        cutItem.setOnAction(e -> handleClipboard(ClipboardMode.CUT));

        MenuItem pasteItem = new MenuItem("Yapıştır (Ctrl+V)");
        pasteItem.setOnAction(e -> handlePaste());

        MenuItem deleteItem = new MenuItem("Sil (Del)");
        deleteItem.setOnAction(e -> handleDeleteSelected());

        ContextMenu menu = new ContextMenu(
                previewItem, externalItem, exportItem, new SeparatorMenuItem(),
                renameItem, copyItem, cutItem, pasteItem, new SeparatorMenuItem(), deleteItem);
        menu.setOnShowing(e -> {
            List<EntryRow> sel = currentSelectedRows();
            boolean hasSel = !sel.isEmpty();
            boolean singleFile = sel.size() == 1 && sel.get(0).entry().isFile();
            previewItem.setDisable(sel.size() != 1);
            externalItem.setDisable(!singleFile);
            exportItem.setDisable(!hasSel);
            renameItem.setDisable(sel.size() != 1);
            copyItem.setDisable(!hasSel);
            cutItem.setDisable(!hasSel);
            pasteItem.setDisable(clipboardEntryIds.isEmpty());
            deleteItem.setDisable(!hasSel);
        });
        return menu;
    }

    private void buildInspectorPane() {
        inspectorPane.getStyleClass().add("inspector-pane");
        inspectorPane.setPadding(new Insets(12));
        inspectorPane.setMinWidth(250);
        inspectorPane.setPrefWidth(315);

        Label header = new Label("ÖNİZLEME VE AYRINTILAR");
        header.getStyleClass().add("section-title");

        detailName.getStyleClass().add("heading-md");
        detailName.setWrapText(true);

        detailMeta.getStyleClass().add("muted-text");
        detailMeta.setWrapText(true);
        detailMeta.setStyle("-fx-font-size: 12px;");

        previewContainer.getStyleClass().add("preview-surface");
        previewContainer.setPadding(new Insets(10));
        previewContainer.setMinHeight(220);
        VBox.setVgrow(previewContainer, Priority.ALWAYS);
        Label placeholder = new Label("Önizleme alanı");
        placeholder.getStyleClass().add("muted-text");
        previewContainer.getChildren().setAll(placeholder);

        openExternalBtn.setMaxWidth(Double.MAX_VALUE);
        openExternalBtn.setDisable(true);
        openExternalBtn.setOnAction(e -> handleOpenExternal());

        exportSelectedBtn.setMaxWidth(Double.MAX_VALUE);
        exportSelectedBtn.setDisable(true);
        exportSelectedBtn.setOnAction(e -> handleExportSelected());

        Label extNote = new Label("Harici uygulamada açılan dosyadaki değişiklikler otomatik olarak kasaya geri kaydedilir; kasa kilitlendiğinde geçici kopya temizlenir.");
        extNote.getStyleClass().add("muted-text");
        extNote.setWrapText(true);
        extNote.setStyle("-fx-font-size: 11px;");

        inspectorPane.getChildren().addAll(
                header, detailName, detailMeta, previewContainer, openExternalBtn, exportSelectedBtn, extNote);
    }

    private Node buildIntegratedStatusBar() {
        statusCountLbl.getStyleClass().add("muted-text");
        statusCountLbl.setStyle("-fx-font-size: 11.5px;");

        statusEnvLbl.getStyleClass().add("muted-text");
        statusEnvLbl.setStyle("-fx-font-size: 11.5px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(10, statusCountLbl, spacer, statusEnvLbl);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("integrated-status-bar");
        return bar;
    }

    private void buildFloatingProgressPill() {
        pillProgressBar.setPrefWidth(130);
        pillProgressBar.setPrefHeight(7);
        pillStatusLbl.getStyleClass().add("primary-text");
        pillStatusLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");

        pillCancelBtn.getStyleClass().add("btn-danger");
        pillCancelBtn.setPadding(new Insets(3, 10, 3, 10));
        pillCancelBtn.setOnAction(e -> {
            CancellationToken tok = currentTaskToken.get();
            if (tok != null) {
                tok.cancel();
                pillStatusLbl.setText("İptal ediliyor...");
            }
        });

        floatingProgressPill.getChildren().setAll(pillProgressBar, pillStatusLbl, pillCancelBtn);
        floatingProgressPill.setAlignment(Pos.CENTER_LEFT);
        floatingProgressPill.getStyleClass().add("floating-progress-pill");
        floatingProgressPill.setPadding(new Insets(8, 14, 8, 14));
        floatingProgressPill.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        floatingProgressPill.setVisible(false);
        floatingProgressPill.setManaged(false);
    }

    private void toggleInspector(boolean show) {
        if (show == inspectorVisible) return;

        inspectorVisible = show;
        previewToggle.setSelected(show);
        if (show) {
            if (!mainSplit.getItems().contains(inspectorPane)) {
                mainSplit.getItems().add(inspectorPane);
                mainSplit.setDividerPositions(0.21, 0.74);
                MotionFx.playSlideSwitch(inspectorPane, 18.0, 200.0);
            }
            EntryRow sel = primarySelectedRow();
            if (sel != null) {
                updateInspector(sel);
            }
        } else {
            mainSplit.getItems().remove(inspectorPane);
            mainSplit.setDividerPositions(0.24);
        }
    }

    private void refreshAll() {
        try {
            VaultMetadata meta = session.getMetadataSnapshot();
            if (meta.getEntry(currentFolderId).isEmpty()) {
                currentFolderId = VaultMetadata.ROOT_ID;
            }
            rebuildFolderTree(meta);
            refreshExtFilter(meta);
            refreshTableAndBreadcrumbs(meta);
            refreshStorageStats();
            refreshBadges();
        } catch (Exception e) {
            shell.showErrorOverlay("Yenileme Hatası", "Kasa görünümü güncellenemedi.", e);
        }
    }

    private void refreshMetadataOnly() {
        refreshAll();
    }

    private void refreshThemeSensitiveUi() {
        updateToolbarIcons();
        folderTree.refresh();
        entryTable.refresh();
        if (viewMode == ViewMode.GRID) {
            rebuildGridCards();
        }
    }

    private void updateToolbarIcons() {
        exportBtn.setGraphic(SapphireIcons.download(14, shell.iconColor()));
        listModeBtn.setGraphic(SapphireIcons.list(14, viewMode == ViewMode.LIST ? "#FFFFFF" : shell.iconColor()));
        gridModeBtn.setGraphic(SapphireIcons.grid(14, viewMode == ViewMode.GRID ? "#FFFFFF" : shell.iconColor()));
    }

    private void rebuildFolderTree(VaultMetadata meta) {
        VaultEntry rootEntry = meta.getEntry(VaultMetadata.ROOT_ID).orElseThrow();
        TreeItem<VaultEntry> rootItem = buildFolderTreeItem(meta, rootEntry);
        rootItem.setExpanded(true);
        folderTree.setRoot(rootItem);
        selectTreeFolder(rootItem, currentFolderId);
    }

    private TreeItem<VaultEntry> buildFolderTreeItem(VaultMetadata meta, VaultEntry folder) {
        TreeItem<VaultEntry> item = new TreeItem<>(folder);
        item.setExpanded(true);
        for (VaultEntry child : meta.listChildren(folder.id())) {
            if (child.isFolder()) {
                item.getChildren().add(buildFolderTreeItem(meta, child));
            }
        }
        return item;
    }

    private boolean selectTreeFolder(TreeItem<VaultEntry> node, UUID targetId) {
        if (node.getValue() != null && targetId.equals(node.getValue().id())) {
            folderTree.getSelectionModel().select(node);
            return true;
        }
        for (TreeItem<VaultEntry> child : node.getChildren()) {
            if (selectTreeFolder(child, targetId)) return true;
        }
        return false;
    }

    private void refreshExtFilter(VaultMetadata meta) {
        String current = extFilterCombo.getValue();
        List<String> items = new ArrayList<>();
        items.add("Tüm Türler");
        meta.collectDistinctExtensions().stream().sorted().forEach(ext -> items.add("." + ext));
        extFilterCombo.getItems().setAll(items);
        extFilterCombo.setValue(items.contains(current) ? current : "Tüm Türler");
    }

    private void refreshTableAndBreadcrumbs() {
        try {
            refreshTableAndBreadcrumbs(session.getMetadataSnapshot());
        } catch (Exception e) {
            shell.showErrorOverlay("Hata", "Klasör listesi okunamadı.", e);
        }
    }

    private void refreshTableOnly() {
        try {
            VaultMetadata meta = session.getMetadataSnapshot();
            populateItems(meta);
        } catch (Exception e) {
            shell.showErrorOverlay("Arama Hatası", "Arama sırasında hata oluştu.", e);
        }
    }

    private void refreshTableAndBreadcrumbs(VaultMetadata meta) {
        rebuildBreadcrumbs(meta);
        populateItems(meta);
    }

    private void rebuildBreadcrumbs(VaultMetadata meta) {
        breadcrumbBar.getChildren().clear();
        List<VaultEntry> chain = new ArrayList<>();
        UUID cur = currentFolderId;
        int guard = 0;
        while (cur != null && guard++ < 1000) {
            Optional<VaultEntry> e = meta.getEntry(cur);
            if (e.isEmpty()) break;
            chain.add(e.get());
            cur = e.get().parentId();
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            VaultEntry node = chain.get(i);
            Button btn = new Button(node.name());
            btn.getStyleClass().add("btn-ghost");
            btn.setOnAction(e -> {
                currentFolderId = node.id();
                refreshAll();
            });
            breadcrumbBar.getChildren().add(btn);
            if (i > 0) {
                breadcrumbBar.getChildren().add(SapphireIcons.chevronRight(13, shell.mutedIconColor()));
            }
        }
    }

    private void populateItems(VaultMetadata meta) {
        String query = searchField.getText();
        String extVal = extFilterCombo.getValue();
        String ext = (extVal == null || "Tüm Türler".equals(extVal)) ? "" : extVal;
        boolean global = globalSearchCheck.isSelected();
        UUID scopeId = global ? VaultMetadata.ROOT_ID : currentFolderId;
        boolean recursive = global || (query != null && !query.isBlank()) || !ext.isEmpty();

        List<VaultEntry> matches = meta.search(scopeId, recursive, query, ext);
        List<EntryRow> rows = new ArrayList<>(matches.size());
        Set<UUID> currentIds = new LinkedHashSet<>(matches.size());
        for (VaultEntry e : matches) {
            long size = e.isFolder() ? meta.computeFolderLogicalSize(e.id()) : e.logicalSize();
            rows.add(new EntryRow(e, size));
            currentIds.add(e.id());
        }

        String filterSig = (query == null ? "" : query.trim()) + "|" + ext + "|" + global;
        boolean sameScope = Objects.equals(currentFolderId, lastDisplayedFolderId)
                && filterSig.equals(lastFilterSignature);
        Set<UUID> addedIds = new LinkedHashSet<>();
        if (sameScope) {
            for (UUID id : currentIds) {
                if (!lastDisplayedIds.contains(id)) {
                    addedIds.add(id);
                }
            }
        }
        lastDisplayedFolderId = currentFolderId;
        lastFilterSignature = filterSig;
        lastDisplayedIds = currentIds;

        pendingAnimatedRowIds.clear();
        if (!addedIds.isEmpty() && viewMode == ViewMode.LIST) {
            pendingAnimatedRowIds.addAll(addedIds);
        }

        currentDisplayedRows = rows;
        entryTable.getItems().setAll(rows);
        if (!entryTable.getSortOrder().isEmpty()) {
            entryTable.sort();
        }
        gridSelectedIds.retainAll(rows.stream().map(r -> r.entry().id()).toList());
        if (viewMode == ViewMode.GRID) {
            rebuildGridCards(addedIds);
        }
        refreshStatusCounts();
    }

    private void refreshStatusCounts() {
        int total = currentDisplayedRows.size();
        int selCount = currentSelectedRows().size();
        statusCountLbl.setText(selCount > 0 ? total + " öğe (" + selCount + " seçili)" : total + " öğe");
    }

    private void refreshStorageStats() {
        try {
            StorageStats st = session.getStorageStats();
            storageHeaderSummary.setText(FormatUtils.formatBytes(st.physicalFileSize()));
            logicalSizeVal.setText(FormatUtils.formatBytes(st.logicalSize()));
            physicalSizeVal.setText(FormatUtils.formatBytes(st.physicalFileSize()));
            activeBytesVal.setText(FormatUtils.formatBytes(st.activeEncryptedBytes()));
            orphanBytesVal.setText(FormatUtils.formatBytes(st.orphanBytes()));
            reclaimableVal.setText(FormatUtils.formatBytes(st.estimatedReclaimableBytes()));
            storageShortLegend.setText("Kullanılan: " + FormatUtils.formatBytes(st.activeEncryptedBytes())
                    + " • Boşta: " + FormatUtils.formatBytes(st.orphanBytes()));

            double trackW = storageBarTrack.getWidth() > 20 ? storageBarTrack.getWidth() : 206.0;
            long totalAllocated = st.activeEncryptedBytes() + st.orphanBytes();
            if (totalAllocated <= 0) {
                MotionFx.animatePrefWidth(storageActiveBar, 0, 260.0);
                MotionFx.animatePrefWidth(storageOrphanBar, 0, 260.0);
            } else {
                double activeW = Math.max(4.0, trackW * ((double) st.activeEncryptedBytes() / totalAllocated));
                double orphanW = st.orphanBytes() > 0 ? Math.max(4.0, trackW - activeW) : 0.0;
                if (activeW + orphanW > trackW) {
                    activeW = Math.max(0.0, trackW - orphanW);
                }
                MotionFx.animatePrefWidth(storageActiveBar, activeW, 260.0);
                MotionFx.animatePrefWidth(storageOrphanBar, orphanW, 260.0);
            }
        } catch (Exception ignored) {
        }
    }

    private void navigateUp() {
        try {
            VaultMetadata meta = session.getMetadataSnapshot();
            VaultEntry cur = meta.getEntry(currentFolderId).orElse(null);
            if (cur != null && cur.parentId() != null) {
                currentFolderId = cur.parentId();
                refreshAll();
            }
        } catch (Exception ignored) {
        }
    }

    private void updateInspector(EntryRow row) {
        if (row == null) {
            lastPreviewedId = null;
            detailName.setText("Öğe seçilmedi");
            detailMeta.setText("Önizleme veya ayrıntı için bir dosya/klasör seçin.");
            Label ph = new Label("Önizleme alanı");
            ph.getStyleClass().add("muted-text");
            previewContainer.getChildren().setAll(ph);
            openExternalBtn.setDisable(true);
            exportSelectedBtn.setDisable(true);
            return;
        }
        VaultEntry e = row.entry();
        exportSelectedBtn.setDisable(false);
        openExternalBtn.setDisable(!e.isFile());

        String pathStr = "/";
        try {
            pathStr = session.getMetadataSnapshot().buildPath(e.id());
        } catch (Exception ignored) {
        }

        detailName.setText(e.name());
        detailMeta.setText(String.format(
                "Yol: %s\nTür: %s | Boyut: %s\nOluşturma: %s\nDeğiştirilme: %s",
                pathStr,
                e.isFolder() ? "Klasör" : "Dosya",
                FormatUtils.formatBytes(row.computedSize()),
                FormatUtils.formatTimestamp(e.createdAt()),
                FormatUtils.formatTimestamp(e.modifiedAt())));

        if (e.isFolder()) {
            lastPreviewedId = null;
            Label openHint = new Label("Klasör içeriğini açmak için çift tıklayın.");
            openHint.getStyleClass().add("muted-text");
            openHint.setWrapText(true);
            VBox box = new VBox(8, SapphireIcons.folder(42, shell.accentIconColor()), openHint);
            box.setAlignment(Pos.CENTER);
            previewContainer.getChildren().setAll(box);
            MotionFx.playEntrance(box, 6.0, 0.0, 180.0);
            return;
        }

        String ext = e.extensionLower();
        if (TEXT_EXTENSIONS.contains(ext) && e.logicalSize() <= MAX_TEXT_PREVIEW_BYTES) {
            loadTextPreviewAsync(e);
        } else if (IMAGE_EXTENSIONS.contains(ext) && e.logicalSize() <= MAX_IMAGE_PREVIEW_BYTES) {
            loadImagePreviewAsync(e);
        } else {
            lastPreviewedId = null;
            Label lbl = new Label("Bu dosya türü veya boyutu için dahili önizleme yok.\n'Harici Uygulamada Aç' veya 'Dışa Aktar' kullanabilirsiniz.");
            lbl.setWrapText(true);
            lbl.getStyleClass().add("muted-text");
            previewContainer.getChildren().setAll(lbl);
            MotionFx.playEntrance(lbl, 6.0, 0.0, 180.0);
        }
    }

    private void loadTextPreviewAsync(VaultEntry entry) {
        lastPreviewedId = entry.id();
        Label loading = new Label("Metin önizlemesi çözülüyor...");
        loading.getStyleClass().add("muted-text");
        previewContainer.getChildren().setAll(loading);
        previewExecutor.submit(() -> {
            try {
                if (!entry.id().equals(lastPreviewedId)) return;
                byte[] bytes = session.readFileBytes(entry.id(), MAX_TEXT_PREVIEW_BYTES, CancellationToken.NONE);
                String text = new String(bytes, StandardCharsets.UTF_8);
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    TextArea area = new TextArea(text);
                    area.setEditable(false);
                    area.setWrapText(false);
                    previewContainer.getChildren().setAll(area);
                    MotionFx.playEntrance(area, 6.0, 0.0, 180.0);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    Label err = new Label("Önizleme hatası: " + ex.getMessage());
                    err.getStyleClass().add("muted-text");
                    err.setWrapText(true);
                    previewContainer.getChildren().setAll(err);
                });
            }
        });
    }

    private void loadImagePreviewAsync(VaultEntry entry) {
        lastPreviewedId = entry.id();
        Label loading = new Label("Görsel önizlemesi çözülüyor...");
        loading.getStyleClass().add("muted-text");
        previewContainer.getChildren().setAll(loading);
        previewExecutor.submit(() -> {
            try {
                if (!entry.id().equals(lastPreviewedId)) return;
                byte[] bytes = session.readFileBytes(entry.id(), MAX_IMAGE_PREVIEW_BYTES, CancellationToken.NONE);
                Image img = decodePreviewImage(bytes);
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    if (img == null) {
                        Label unsupported = new Label("Bu görsel biçimi dahili önizlemede desteklenmiyor.\n'Harici Uygulamada Aç' veya 'Dışa Aktar' kullanabilirsiniz.");
                        unsupported.setWrapText(true);
                        unsupported.getStyleClass().add("muted-text");
                        previewContainer.getChildren().setAll(unsupported);
                        MotionFx.playEntrance(unsupported, 6.0, 0.0, 180.0);
                        return;
                    }
                    ImageView view = new ImageView(img);
                    view.setPreserveRatio(true);
                    view.setSmooth(true);
                    view.fitWidthProperty().bind(Bindings.max(120.0, previewContainer.widthProperty().subtract(24.0)));
                    view.fitHeightProperty().bind(Bindings.max(120.0, previewContainer.heightProperty().subtract(24.0)));
                    StackPane frame = new StackPane(view);
                    frame.setAlignment(Pos.CENTER);
                    previewContainer.getChildren().setAll(frame);
                    MotionFx.playEntrance(frame, 6.0, 0.0, 180.0);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    Label err = new Label("Görsel yüklenemedi: " + ex.getMessage());
                    err.getStyleClass().add("muted-text");
                    err.setWrapText(true);
                    previewContainer.getChildren().setAll(err);
                });
            }
        });
    }

    private static Image decodePreviewImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Image fxImg = new Image(new ByteArrayInputStream(bytes));
        if (!fxImg.isError() && fxImg.getWidth() > 0 && fxImg.getHeight() > 0) {
            return fxImg;
        }
        BufferedImage fallback = decodeWithImageIo(bytes);
        if (fallback == null) {
            return null;
        }
        int w = fallback.getWidth();
        int h = fallback.getHeight();
        int[] pixels = fallback.getRGB(0, 0, w, h, null, 0, w);
        WritableImage writable = new WritableImage(w, h);
        PixelWriter pw = writable.getPixelWriter();
        pw.setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
        return writable;
    }

    static BufferedImage decodeWithImageIo(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
                return null;
            }
            if (decoded.getType() == BufferedImage.TYPE_INT_ARGB) {
                return decoded;
            }
            BufferedImage argb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = argb.createGraphics();
            try {
                g.drawImage(decoded, 0, 0, null);
            } finally {
                g.dispose();
            }
            return argb;
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    private void openOrPreviewEntry(VaultEntry entry) {
        if (entry.isFolder()) {
            currentFolderId = entry.id();
            refreshAll();
        } else {
            toggleInspector(true);
            String ext = entry.extensionLower();
            if (!(TEXT_EXTENSIONS.contains(ext) && entry.logicalSize() <= MAX_TEXT_PREVIEW_BYTES)
                    && !(IMAGE_EXTENSIONS.contains(ext) && entry.logicalSize() <= MAX_IMAGE_PREVIEW_BYTES)) {
                handleOpenExternal();
            }
        }
    }

    private void handleCreateFolder() {
        shell.showTextInputOverlay(
                "Yeni Klasör",
                "Bulunduğunuz dizinde yeni klasör oluşturun.",
                "Klasör adı",
                "",
                "Oluştur",
                nameOpt -> nameOpt.ifPresent(name -> runBackgroundTask("Klasör oluşturuluyor...", false, (cancel, progress) -> {
                    session.createFolder(currentFolderId, name);
                    return "Klasör oluşturuldu: " + name;
                })));
    }

    private void handleAddFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Kasaya Eklenecek Dosyaları Seçin");
        List<File> files = chooser.showOpenMultipleDialog(window());
        if (files != null && !files.isEmpty()) {
            importPathsAsync(files.stream().map(File::toPath).toList(), currentFolderId);
        }
    }

    private void handleAddDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Kasaya Eklenecek Klasörü Seçin");
        File dir = chooser.showDialog(window());
        if (dir != null) {
            importPathsAsync(List.of(dir.toPath()), currentFolderId);
        }
    }

    private void importPathsAsync(List<Path> paths, UUID targetFolderId) {
        runBackgroundTask("Dosyalar kasaya ekleniyor...", true, (cancel, progress) -> {
            int importedCount = 0;
            for (Path p : paths) {
                cancel.throwIfCancelled();
                if (Files.isDirectory(p)) {
                    session.importDirectory(p, targetFolderId, cancel, progress);
                    importedCount++;
                } else if (Files.isRegularFile(p)) {
                    String candidateName = p.getFileName().toString();
                    boolean overwrite = false;
                    while (true) {
                        cancel.throwIfCancelled();
                        VaultMetadata snap = session.getMetadataSnapshot();
                        Optional<VaultEntry> conflict = snap.findChildByName(targetFolderId, candidateName);
                        if (conflict.isEmpty()) {
                            break;
                        }
                        ConflictResolution res = askConflictOnFxThread(
                                candidateName, conflict.get().isFolder(), !conflict.get().isFolder());
                        if (res.action() == ConflictAction.CANCEL) {
                            candidateName = null;
                            break;
                        } else if (res.action() == ConflictAction.OVERWRITE && !conflict.get().isFolder()) {
                            overwrite = true;
                            break;
                        } else if (res.action() == ConflictAction.RENAME) {
                            candidateName = res.newName();
                        }
                    }
                    if (candidateName != null) {
                        session.importFile(p, targetFolderId, candidateName, overwrite, cancel, progress);
                        importedCount++;
                    }
                }
            }
            return importedCount > 0 ? importedCount + " öğe kasaya eklendi" : "İşlem tamamlandı";
        });
    }

    private void handleExportSelected() {
        List<EntryRow> selected = currentSelectedRows();
        if (selected.isEmpty()) {
            shell.showToast(ToastType.INFO, "Seçili Öğe Yok", "Dışa aktarmak için en az bir öğe seçin.");
            return;
        }
        if (selected.size() == 1 && selected.get(0).entry().isFile()) {
            VaultEntry file = selected.get(0).entry();
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Dosyayı Dışa Aktar");
            chooser.setInitialFileName(file.name());
            File dest = chooser.showSaveDialog(window());
            if (dest == null) return;
            runBackgroundTask(file.name() + " dışa aktarılıyor...", true, (cancel, progress) -> {
                session.exportFile(file.id(), dest.toPath(), cancel, progress);
                return file.name() + " dışa aktarıldı";
            });
        } else {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Dışa Aktarılacak Hedef Klasörü Seçin");
            File destDir = chooser.showDialog(window());
            if (destDir == null) return;
            Path targetDir = destDir.toPath();
            runBackgroundTask("Seçili öğeler dışa aktarılıyor...", true, (cancel, progress) -> {
                for (EntryRow r : selected) {
                    cancel.throwIfCancelled();
                    VaultEntry e = r.entry();
                    if (e.isFolder()) {
                        session.exportFolder(e.id(), targetDir, cancel, progress);
                    } else {
                        session.exportFile(e.id(), targetDir.resolve(e.name()), cancel, progress);
                    }
                }
                return selected.size() + " öğe dışa aktarıldı";
            });
        }
    }

    private void handleOpenExternal() {
        EntryRow sel = primarySelectedRow();
        if (sel == null || !sel.entry().isFile()) {
            return;
        }
        VaultEntry entry = sel.entry();
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            shell.showToast(ToastType.WARNING, "Desteklenmiyor", "Harici açma desteklenmiyor; 'Dışa Aktar' kullanın.");
            return;
        }
        runBackgroundTask(entry.name() + " hazırlanıyor...", true, (cancel, progress) -> {
            Path tempFile = session.tempPreviewManager().createTempFile(entry.name());
            session.exportFile(entry.id(), tempFile, cancel, progress);
            session.tempPreviewManager().hardenFile(tempFile);
            session.tempPreviewManager().watchExternalFile(tempFile, entry.id(), (entryId, modifiedPath) -> {
                VaultEntry updated = session.syncExternalFileEdit(entryId, modifiedPath);
                if (updated != null) {
                    Platform.runLater(() -> {
                        EntryRow currentPrimary = primarySelectedRow();
                        boolean wasSelectedInList = viewMode == ViewMode.LIST
                                && entryTable.getSelectionModel().getSelectedItems().stream()
                                .anyMatch(r -> updated.id().equals(r.entry().id()));
                        boolean shouldRefreshPreview = updated.id().equals(lastPreviewedId)
                                || wasSelectedInList
                                || gridSelectedIds.contains(updated.id())
                                || (currentPrimary != null && updated.id().equals(currentPrimary.entry().id()));

                        refreshAll();

                        EntryRow refreshedRow = currentDisplayedRows.stream()
                                .filter(r -> updated.id().equals(r.entry().id()))
                                .findFirst()
                                .orElse(new EntryRow(updated, updated.logicalSize()));

                        boolean inspectorUpdated = false;
                        if (wasSelectedInList) {
                            int idx = entryTable.getItems().indexOf(refreshedRow);
                            if (idx >= 0) {
                                entryTable.getSelectionModel().select(idx);
                                inspectorUpdated = true;
                            }
                        }
                        if (!inspectorUpdated && shouldRefreshPreview) {
                            updateInspector(refreshedRow);
                        }
                        shell.showToast(ToastType.SUCCESS, "Değişiklik Kasaya Kaydedildi", updated.name());
                    });
                }
            });
            Desktop.getDesktop().open(tempFile.toFile());
            return entry.name() + " harici uygulamada açıldı";
        });
    }

    private void handleRenameSelected() {
        EntryRow sel = primarySelectedRow();
        if (sel == null) return;
        VaultEntry entry = sel.entry();
        shell.showTextInputOverlay(
                "Yeniden Adlandır",
                "Seçili öğe için yeni bir ad girin.",
                "Yeni ad",
                entry.name(),
                "Kaydet",
                nameOpt -> nameOpt.ifPresent(newName -> {
                    if (!newName.equals(entry.name())) {
                        runBackgroundTask("Yeniden adlandırılıyor...", false, (cancel, progress) -> {
                            session.renameEntry(entry.id(), newName);
                            return "Öğe yeniden adlandırıldı: " + newName;
                        });
                    }
                }));
    }

    private void handleDeleteSelected() {
        List<EntryRow> selected = currentSelectedRows();
        if (selected.isEmpty()) return;
        List<UUID> ids = selected.stream().map(r -> r.entry().id()).toList();
        shell.showConfirmOverlay(
                selected.size() + " Öğe Silinsin mi?",
                "Kasa içinde geri dönüşüm kutusu yoktur. Silinen öğelerin kapladığı boş alan bir sonraki sıkıştırmada temizlenir.",
                "Sil",
                true,
                confirmed -> {
                    if (!confirmed) return;
                    Set<UUID> idSet = new HashSet<>(ids);
                    List<Node> nodesToAnimate = collectVisibleNodesForIds(idSet);
                    MotionFx.playExitRemove(nodesToAnimate, () ->
                            runBackgroundTask("Öğeler siliniyor...", false, (cancel, progress) -> {
                                session.deleteEntries(ids);
                                return ids.size() + " öğe silindi";
                            }));
                });
    }

    private List<Node> collectVisibleNodesForIds(Set<UUID> idSet) {
        List<Node> nodes = new ArrayList<>();
        if (idSet == null || idSet.isEmpty()) {
            return nodes;
        }
        if (viewMode == ViewMode.GRID) {
            for (Node child : gridFlow.getChildren()) {
                if (child.getUserData() instanceof UUID id && idSet.contains(id)) {
                    nodes.add(child);
                }
            }
        } else {
            for (Node cell : entryTable.lookupAll(".table-row-cell")) {
                if (cell instanceof TableRow<?> row && !row.isEmpty()
                        && row.getItem() instanceof EntryRow er
                        && idSet.contains(er.entry().id())) {
                    nodes.add(row);
                }
            }
        }
        return nodes;
    }

    private void handleClipboard(ClipboardMode mode) {
        List<EntryRow> selected = currentSelectedRows();
        if (selected.isEmpty()) return;
        clipboardEntryIds.clear();
        for (EntryRow r : selected) {
            clipboardEntryIds.add(r.entry().id());
        }
        clipboardMode = mode;
        shell.showToast(
                ToastType.INFO,
                mode == ClipboardMode.COPY ? "Panoya Kopyalandı" : "Taşınmak Üzere Kesildi",
                clipboardEntryIds.size() + " öğe seçildi");
    }

    private void handlePaste() {
        if (clipboardEntryIds.isEmpty() || clipboardMode == null) {
            return;
        }
        List<UUID> ids = new ArrayList<>(clipboardEntryIds);
        ClipboardMode mode = clipboardMode;
        UUID targetFolder = currentFolderId;

        if (mode == ClipboardMode.CUT) {
            clipboardEntryIds.clear();
            clipboardMode = null;
            moveEntriesAsync(ids, targetFolder);
            return;
        }

        runBackgroundTask("Öğeler kopyalanıyor...", false, (cancel, progress) -> {
            int copied = 0;
            for (UUID id : ids) {
                VaultMetadata snap = session.getMetadataSnapshot();
                Optional<VaultEntry> srcOpt = snap.getEntry(id);
                if (srcOpt.isEmpty()) continue;
                String candidateName = srcOpt.get().name();
                while (true) {
                    Optional<VaultEntry> conflict = snap.findChildByName(targetFolder, candidateName);
                    if (conflict.isEmpty()) break;
                    ConflictResolution res = askConflictOnFxThread(candidateName, conflict.get().isFolder(), false);
                    if (res.action() == ConflictAction.CANCEL) {
                        candidateName = null;
                        break;
                    } else if (res.action() == ConflictAction.RENAME) {
                        candidateName = res.newName();
                    }
                }
                if (candidateName != null) {
                    session.copyEntry(id, targetFolder, candidateName);
                    copied++;
                }
            }
            return copied + " öğe kopyalandı";
        });
    }

    private void moveEntriesAsync(List<UUID> ids, UUID targetFolderId) {
        runBackgroundTask("Öğeler taşınıyor...", false, (cancel, progress) -> {
            int moved = 0;
            for (UUID id : ids) {
                session.moveEntry(id, targetFolderId);
                moved++;
            }
            return moved + " öğe taşındı";
        });
    }

    private void handleCompact() {
        shell.showConfirmOverlay(
                "Kasayı Sıkıştır",
                "Kullanılmayan boş alan temizlenerek kasa dosyası yeniden düzenlenecek ve boyutu küçültülecek.",
                "Sıkıştırmayı Başlat",
                false,
                ok -> {
                    if (!ok) return;
                    runBackgroundTask("Kasa sıkıştırılıyor...", true, (cancel, progress) -> {
                        StorageStats after = session.compact(cancel, progress);
                        return "Sıkıştırma tamamlandı • Yeni boyut: " + FormatUtils.formatBytes(after.physicalFileSize());
                    });
                });
    }

    @FunctionalInterface
    private interface BackgroundVaultOp {
        String execute(CancellationToken cancel, ProgressListener progress) throws Exception;
    }

    private void runBackgroundTask(String startMsg, boolean cancellable, BackgroundVaultOp op) {
        CancellationToken token = new CancellationToken();
        currentTaskToken.set(token);
        pillProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        pillStatusLbl.setText(startMsg);
        pillCancelBtn.setVisible(cancellable);
        pillCancelBtn.setManaged(cancellable);
        floatingProgressPill.setManaged(true);
        floatingProgressPill.setVisible(true);
        MotionFx.playEntrance(floatingProgressPill, 16.0, 0.0, 200.0);

        ProgressListener throttledProgress = ProgressListener.throttled(100L, (done, total, msg) ->
                Platform.runLater(() -> {
                    if (total > 0) {
                        pillProgressBar.setProgress(Math.min(1.0, (double) done / (double) total));
                    }
                    if (msg != null && !msg.isBlank()) {
                        pillStatusLbl.setText(msg);
                    }
                }));

        AutoLockMonitor.ActivityHold hold = autoLockMonitor != null ? autoLockMonitor.beginActivityHold() : () -> {
        };
        try {
            bgExecutor.submit(() -> {
                try {
                    String resultMsg = op.execute(token, throttledProgress);
                    Platform.runLater(() -> {
                        hideProgressPill();
                        refreshAll();
                        shell.showToast(ToastType.SUCCESS, "İşlem Tamamlandı", resultMsg);
                    });
                } catch (VaultCancelledException cancelled) {
                    Platform.runLater(() -> {
                        hideProgressPill();
                        refreshAll();
                        shell.showToast(ToastType.WARNING, "İptal Edildi", "Devam eden işlem kullanıcı tarafından durduruldu.");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        hideProgressPill();
                        refreshAll();
                        shell.showErrorOverlay("İşlem Hatası", ex.getMessage(), ex);
                    });
                } finally {
                    hold.close();
                }
            });
        } catch (RuntimeException submitEx) {
            hold.close();
            throw submitEx;
        }
    }

    private void hideProgressPill() {
        currentTaskToken.set(null);
        pillProgressBar.setProgress(0);
        if (!floatingProgressPill.isVisible()) {
            floatingProgressPill.setManaged(false);
            return;
        }
        FadeTransition fade = new FadeTransition(Duration.millis(160), floatingProgressPill);
        fade.setToValue(0.0);
        fade.setInterpolator(MotionFx.EASE_OUT_EXPO);
        TranslateTransition slide = new TranslateTransition(Duration.millis(160), floatingProgressPill);
        slide.setToY(16.0);
        slide.setInterpolator(MotionFx.EASE_OUT_EXPO);
        ParallelTransition exit = new ParallelTransition(fade, slide);
        exit.setOnFinished(ev -> {
            floatingProgressPill.setOpacity(1.0);
            floatingProgressPill.setTranslateY(0.0);
            if (currentTaskToken.get() == null) {
                floatingProgressPill.setVisible(false);
                floatingProgressPill.setManaged(false);
            }
        });
        exit.play();
    }

    private ConflictResolution askConflictOnFxThread(
            String conflictingName, boolean existingIsFolder, boolean allowOverwrite)
            throws Exception {
        CompletableFuture<ConflictResolution> fut = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                shell.showConflictOverlay(conflictingName, existingIsFolder, allowOverwrite)
                        .whenComplete((res, err) -> {
                            if (err != null) {
                                fut.completeExceptionally(err);
                            } else {
                                fut.complete(res);
                            }
                        });
            } catch (Throwable t) {
                fut.completeExceptionally(t);
            }
        });
        return fut.get();
    }

    private Window window() {
        return getScene() != null ? getScene().getWindow() : null;
    }

    @Override
    public void close() {
        themeManager.removeListener(themeListener);
        CancellationToken tok = currentTaskToken.getAndSet(null);
        if (tok != null) {
            tok.cancel();
        }
        bgExecutor.shutdownNow();
        previewExecutor.shutdownNow();
    }
}
