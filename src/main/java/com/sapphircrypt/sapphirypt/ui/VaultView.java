package com.sapphircrypt.sapphirypt.ui;

import com.sapphircrypt.sapphirypt.format.VaultMetadata;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.StorageStats;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.VaultEntry;
import com.sapphircrypt.sapphirypt.storage.AppSettings;
import com.sapphircrypt.sapphirypt.storage.CancellationToken;
import com.sapphircrypt.sapphirypt.storage.ProgressListener;
import com.sapphircrypt.sapphirypt.storage.VaultCancelledException;
import com.sapphircrypt.sapphirypt.storage.VaultSession;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.ConflictAction;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.ConflictResolution;
import javafx.application.Platform;
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
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Main unlocked vault workspace providing folder tree navigation, file table, search/filter/sort,
 * drag-and-drop import & internal moves, built-in text/image preview, external open,
 * storage breakdown + compaction, key management, and live CPU profile settings.
 */
public final class VaultView extends BorderPane implements AutoCloseable {

    private static final DataFormat INTERNAL_ENTRY_IDS = new DataFormat("application/x-sapphirypt-entry-ids");
    private static final long MAX_TEXT_PREVIEW_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_IMAGE_PREVIEW_BYTES = 15L * 1024L * 1024L;

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "json", "xml", "csv", "log", "java", "kt", "kts", "gradle",
            "py", "js", "ts", "html", "css", "yml", "yaml", "properties", "ini",
            "sql", "sh", "bat", "conf", "cfg", "toml");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");

    private enum ClipboardMode {
        COPY, CUT
    }

    private record EntryRow(VaultEntry entry, long computedSize) { }

    private final VaultSession session;
    private final AppSettings settings;
    private final AutoLockMonitor autoLockMonitor;
    private final Runnable onLockRequested;
    private final Consumer<Path> onVaultRenamed;
    private final Runnable onVaultDeleted;
    private final ExecutorService bgExecutor;

    private UUID currentFolderId = VaultMetadata.ROOT_ID;
    private final AtomicReference<CancellationToken> currentTaskToken = new AtomicReference<>();
    private final List<UUID> clipboardEntryIds = new ArrayList<>();
    private ClipboardMode clipboardMode;

    // UI Components
    private final Label vaultNameLbl = new Label();
    private final Label commitBadge = new Label();
    private final Label cpuBadge = new Label();
    private final TreeView<VaultEntry> folderTree = new TreeView<>();
    private final HBox breadcrumbBar = new HBox(4);
    private final TextField searchField = new TextField();
    private final CheckBox globalSearchCheck = new CheckBox("Tüm kasada ara");
    private final ComboBox<String> extFilterCombo = new ComboBox<>();
    private final TableView<EntryRow> entryTable = new TableView<>();

    // Storage breakdown labels
    private final Label logicalSizeVal = new Label("-");
    private final Label physicalSizeVal = new Label("-");
    private final Label activeBytesVal = new Label("-");
    private final Label orphanBytesVal = new Label("-");
    private final Label reclaimableVal = new Label("-");
    private final Label blockSharingVal = new Label("-");

    // Inspector & Preview pane
    private final VBox inspectorPane = new VBox(10);
    private final Label detailName = new Label("Öğe seçilmedi");
    private final Label detailMeta = new Label("Önizleme veya ayrıntı için bir dosya/klasör seçin.");
    private final StackPane previewContainer = new StackPane();
    private final Button openExternalBtn = new Button("Harici Uygulamada Aç");
    private final Button exportSelectedBtn = new Button("Dışa Aktar");

    // Status bar
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("Hazır");
    private final Button cancelTaskBtn = new Button("İptal");

    private SplitPane mainSplit;
    private boolean inspectorVisible = true;
    private UUID lastPreviewedId;

    public VaultView(
            VaultSession session,
            AppSettings settings,
            AutoLockMonitor autoLockMonitor,
            Runnable onLockRequested,
            Consumer<Path> onVaultRenamed,
            Runnable onVaultDeleted) {
        this.session = session;
        this.settings = settings;
        this.autoLockMonitor = autoLockMonitor;
        this.onLockRequested = onLockRequested;
        this.onVaultRenamed = onVaultRenamed;
        this.onVaultDeleted = onVaultDeleted;
        this.bgExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sapphirypt-vault-ops");
            t.setDaemon(true);
            return t;
        });

        getStyleClass().add("app-surface");
        buildUi();
        refreshAll();
    }

    private void buildUi() {
        setTop(buildToolbar());
        setBottom(buildStatusBar());

        VBox leftSidebar = buildSidebar();
        VBox centerArea = buildCenterArea();
        buildInspectorPane();

        mainSplit = new SplitPane(leftSidebar, centerArea, inspectorPane);
        mainSplit.setOrientation(Orientation.HORIZONTAL);
        mainSplit.setDividerPositions(0.22, 0.74);
        setCenter(mainSplit);
    }

    private Node buildToolbar() {
        Node emblem = SapphireIcons.createSapphireVaultEmblem(28);
        vaultNameLbl.setText(session.vaultPath().getFileName().toString());
        vaultNameLbl.getStyleClass().add("heading-md");
        commitBadge.getStyleClass().add("sapphire-badge");

        Button addFilesBtn = new Button("Dosya Ekle");
        addFilesBtn.getStyleClass().add("btn-primary");
        addFilesBtn.setOnAction(e -> handleAddFiles());

        Button addFolderBtn = new Button("Klasör Ekle");
        addFolderBtn.setOnAction(e -> handleAddDirectory());

        Button newFolderBtn = new Button("Yeni Klasör");
        newFolderBtn.setOnAction(e -> handleCreateFolder());

        Button exportBtn = new Button("Dışa Aktar");
        exportBtn.setOnAction(e -> handleExportSelected());

        Button compactBtn = new Button("Sıkıştır");
        compactBtn.setOnAction(e -> handleCompact());

        ToggleButton previewToggle = new ToggleButton("Önizleme");
        previewToggle.setSelected(true);
        previewToggle.setOnAction(e -> toggleInspector(previewToggle.isSelected()));

        Button securityBtn = new Button("Güvenlik");
        securityBtn.setOnAction(e -> VaultDialogs.showSecurityDialog(
                window(),
                session,
                bgExecutor,
                this::refreshMetadataOnly,
                newPath -> {
                    vaultNameLbl.setText(newPath.getFileName().toString());
                    if (onVaultRenamed != null) {
                        onVaultRenamed.accept(newPath);
                    }
                    refreshBadges();
                },
                () -> {
                    if (onVaultDeleted != null) {
                        onVaultDeleted.run();
                    }
                }));

        Button settingsBtn = new Button("Ayarlar");
        settingsBtn.setOnAction(e -> VaultDialogs.showSettingsDialog(window(), settings, session, autoLockMonitor, this::refreshBadges));

        Button lockBtn = new Button("Kilitle");
        lockBtn.getStyleClass().add("btn-danger");
        lockBtn.setGraphic(SapphireIcons.lock(14, "#FCA5A5"));
        lockBtn.setOnAction(e -> onLockRequested.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, emblem, vaultNameLbl, commitBadge, new Separator(Orientation.VERTICAL), addFilesBtn, addFolderBtn, newFolderBtn, exportBtn, compactBtn, spacer, previewToggle, securityBtn, settingsBtn, lockBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar-bar");
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
                        setGraphic(SapphireIcons.folder(15, "#60A5FA"));
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

        Label statsHeader = new Label("DEPOLAMA ÖZETİ");
        statsHeader.getStyleClass().add("section-title");

        VBox statsCard = new VBox(5, statRow("Mantıksal Boyut:", logicalSizeVal), statRow("Kasa Dosyası (Fiziksel):", physicalSizeVal), statRow("Aktif Şifreli Veri:", activeBytesVal), statRow("Boşta (Orphan) Veri:", orphanBytesVal), statRow("Tahmini Kazanç:", reclaimableVal), statRow("Blok Paylaşımı:", blockSharingVal));
        statsCard.getStyleClass().add("panel-card");
        statsCard.setPadding(new Insets(10));

        Button quickCompactBtn = new Button("Boş Alanı Geri Kazan (Sıkıştır)");
        quickCompactBtn.setMaxWidth(Double.MAX_VALUE);
        quickCompactBtn.setOnAction(e -> handleCompact());

        VBox sidebar = new VBox(10, treeHeader, folderTree, statsHeader, statsCard, quickCompactBtn);
        sidebar.getStyleClass().add("sidebar-pane");
        sidebar.setPadding(new Insets(12));
        sidebar.setMinWidth(230);
        sidebar.setPrefWidth(260);
        return sidebar;
    }

    private HBox statRow(String label, Label valueLabel) {
        Label k = new Label(label);
        k.getStyleClass().add("muted-text");
        k.setStyle("-fx-font-size: 11.5px;");
        valueLabel.setStyle("-fx-font-size: 11.5px; -fx-font-weight: bold; -fx-text-fill: #E2E8F0;");
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

        searchField.setPromptText("Dosya veya klasör adına göre ara...");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((o, a, b) -> refreshTableOnly());

        globalSearchCheck.selectedProperty().addListener((o, a, b) -> refreshTableOnly());

        extFilterCombo.getItems().add("Tüm Türler");
        extFilterCombo.setValue("Tüm Türler");
        extFilterCombo.valueProperty().addListener((o, a, b) -> refreshTableOnly());

        HBox filterBar = new HBox(10, searchField, globalSearchCheck, extFilterCombo);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        configureTable();
        VBox.setVgrow(entryTable, Priority.ALWAYS);

        Label dropHint = new Label("İpucu: Masaüstünden dosya veya klasörleri doğrudan bu alana sürükleyip bırakabilirsiniz.");
        dropHint.getStyleClass().add("muted-text");
        dropHint.setStyle("-fx-font-size: 11px;");

        VBox center = new VBox(10, navRow, filterBar, entryTable, dropHint);
        center.setPadding(new Insets(12));
        return center;
    }

    private void configureTable() {
        entryTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        entryTable.setPlaceholder(new Label("Bu klasör boş. Dosya ekleyin veya sürükleyip bırakın."));

        TableColumn<EntryRow, EntryRow> nameCol = new TableColumn<>("Ad");
        nameCol.setPrefWidth(270);
        nameCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        nameCol.setComparator(Comparator.comparing((EntryRow r) -> r.entry().isFolder() ? 0 : 1).thenComparing(r -> r.entry().name().toLowerCase(Locale.ROOT)));
        nameCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(EntryRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(row.entry().name());
                    setGraphic(row.entry().isFolder() ? SapphireIcons.folder(16, "#60A5FA") : SapphireIcons.file(16, "#94A3B8"));
                }
            }
        });

        TableColumn<EntryRow, String> typeCol = new TableColumn<>("Tür");
        typeCol.setPrefWidth(95);
        typeCol.setCellValueFactory(data -> {
            VaultEntry e = data.getValue().entry();
            if (e.isFolder()) return new ReadOnlyStringWrapper("Klasör");

            String ext = e.extensionLower();
            return new ReadOnlyStringWrapper(ext.isEmpty() ? "Dosya" : ext.toUpperCase(Locale.ROOT));
        });

        TableColumn<EntryRow, Long> sizeCol = new TableColumn<>("Mantıksal Boyut");
        sizeCol.setPrefWidth(130);
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

        TableColumn<EntryRow, String> blocksCol = new TableColumn<>("Bloklar");
        blocksCol.setPrefWidth(80);
        blocksCol.setCellValueFactory(data -> {
            VaultEntry e = data.getValue().entry();
            return new ReadOnlyStringWrapper(e.isFolder() ? "-" : Integer.toString(e.blocks().size()));
        });

        entryTable.getColumns().setAll(List.of(nameCol, typeCol, sizeCol, modifiedCol, blocksCol));

        entryTable.getSelectionModel().selectedItemProperty().addListener((o, oldRow, newRow) -> updateInspector(newRow));

        // Row factory: double-click open/preview + internal drag/drop
        entryTable.setRowFactory(tv -> {
            TableRow<EntryRow> row = new TableRow<>();
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

        // OS external Drag-and-Drop onto table
        entryTable.setOnDragOver(ev -> {
            if (ev.getDragboard().hasFiles()) {
                ev.acceptTransferModes(TransferMode.COPY);
                entryTable.getStyleClass().add("drop-target-active");
            }
            ev.consume();
        });
        entryTable.setOnDragExited(ev -> entryTable.getStyleClass().remove("drop-target-active"));
        entryTable.setOnDragDropped(ev -> {
            entryTable.getStyleClass().remove("drop-target-active");
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

        entryTable.setContextMenu(buildContextMenu());

        // Keyboard shortcuts
        KeyCombination ctrlC = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        KeyCombination ctrlX = new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN);
        KeyCombination ctrlV = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);
        entryTable.setOnKeyPressed(ev -> {
            if (ev.getCode() == KeyCode.F2) {
                handleRenameSelected();
            } else if (ev.getCode() == KeyCode.DELETE) {
                handleDeleteSelected();
            } else if (ev.getCode() == KeyCode.ENTER) {
                EntryRow sel = entryTable.getSelectionModel().getSelectedItem();
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

    private ContextMenu buildContextMenu() {
        MenuItem previewItem = new MenuItem("Önizle / Aç");
        previewItem.setOnAction(e -> {
            EntryRow sel = entryTable.getSelectionModel().getSelectedItem();
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

        ContextMenu menu = new ContextMenu(previewItem, externalItem, exportItem, new SeparatorMenuItem(), renameItem, copyItem, cutItem, pasteItem, new SeparatorMenuItem(), deleteItem);
        menu.setOnShowing(e -> {
            List<EntryRow> sel = entryTable.getSelectionModel().getSelectedItems();
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
        inspectorPane.setPrefWidth(320);

        Label header = new Label("ÖNİZLEME VE AYRINTILAR");
        header.getStyleClass().add("section-title");

        detailName.getStyleClass().add("heading-md");
        detailName.setWrapText(true);

        detailMeta.getStyleClass().add("muted-text");
        detailMeta.setWrapText(true);
        detailMeta.setStyle("-fx-font-size: 12px;");

        previewContainer.getStyleClass().add("panel-card");
        previewContainer.setPadding(new Insets(10));
        previewContainer.setMinHeight(220);
        VBox.setVgrow(previewContainer, Priority.ALWAYS);
        previewContainer.getChildren().add(new Label("Önizleme alanı"));

        openExternalBtn.setMaxWidth(Double.MAX_VALUE);
        openExternalBtn.setDisable(true);
        openExternalBtn.setOnAction(e -> handleOpenExternal());

        exportSelectedBtn.setMaxWidth(Double.MAX_VALUE);
        exportSelectedBtn.setDisable(true);
        exportSelectedBtn.setOnAction(e -> handleExportSelected());

        Label extNote = new Label("Harici uygulama açılışında geçici düz metin ~/.sapphirypt/tmp dizinine yazılır ve kilitlemede temizlenir.");
        extNote.getStyleClass().add("muted-text");
        extNote.setWrapText(true);
        extNote.setStyle("-fx-font-size: 11px;");

        inspectorPane.getChildren().addAll(header, detailName, detailMeta, previewContainer, openExternalBtn, exportSelectedBtn, extNote);
    }

    private Node buildStatusBar() {
        progressBar.setPrefWidth(190);
        progressBar.setVisible(false);

        cancelTaskBtn.setVisible(false);
        cancelTaskBtn.setManaged(false);
        cancelTaskBtn.getStyleClass().add("btn-danger");
        cancelTaskBtn.setOnAction(e -> {
            CancellationToken tok = currentTaskToken.get();
            if (tok != null) {
                tok.cancel();
                statusLabel.setText("İptal ediliyor...");
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        cpuBadge.getStyleClass().add("sapphire-badge");

        HBox bar = new HBox(10, progressBar, statusLabel, cancelTaskBtn, spacer, cpuBadge);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void toggleInspector(boolean show) {
        if (show == inspectorVisible) return;

        inspectorVisible = show;
        if (show) {
            if (!mainSplit.getItems().contains(inspectorPane)) {
                mainSplit.getItems().add(inspectorPane);
                mainSplit.setDividerPositions(0.22, 0.74);
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
            VaultDialogs.showError(window(), "Yenileme Hatası", "Kasa görünümü güncellenemedi.", e);
        }
    }

    private void refreshMetadataOnly() {
        refreshAll();
    }

    private void refreshBadges() {
        commitBadge.setText("Commit #" + session.currentCommitSequence());
        cpuBadge.setText(session.cpuProfile().displayName() + " • " + session.workerCount() + " worker");
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
            VaultDialogs.showError(window(), "Hata", "Klasör listesi okunamadı.", e);
        }
    }

    private void refreshTableOnly() {
        try {
            VaultMetadata meta = session.getMetadataSnapshot();
            populateTable(meta);
        } catch (Exception e) {
            VaultDialogs.showError(window(), "Arama Hatası", "Arama sırasında hata oluştu.", e);
        }
    }

    private void refreshTableAndBreadcrumbs(VaultMetadata meta) {
        rebuildBreadcrumbs(meta);
        populateTable(meta);
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
                Label sep = new Label("/");
                sep.getStyleClass().add("muted-text");
                breadcrumbBar.getChildren().add(sep);
            }
        }
    }

    private void populateTable(VaultMetadata meta) {
        String query = searchField.getText();
        String extVal = extFilterCombo.getValue();
        String ext = (extVal == null || "Tüm Türler".equals(extVal)) ? "" : extVal;
        boolean global = globalSearchCheck.isSelected();
        UUID scopeId = global ? VaultMetadata.ROOT_ID : currentFolderId;
        boolean recursive = global || (query != null && !query.isBlank()) || !ext.isEmpty();

        List<VaultEntry> matches = meta.search(scopeId, recursive, query, ext);
        List<EntryRow> rows = new ArrayList<>(matches.size());
        for (VaultEntry e : matches) {
            long size = e.isFolder() ? meta.computeFolderLogicalSize(e.id()) : e.logicalSize();
            rows.add(new EntryRow(e, size));
        }
        entryTable.getItems().setAll(rows);
        if (!entryTable.getSortOrder().isEmpty()) {
            entryTable.sort();
        }
    }

    private void refreshStorageStats() {
        try {
            StorageStats st = session.getStorageStats();
            logicalSizeVal.setText(FormatUtils.formatBytes(st.logicalSize()));
            physicalSizeVal.setText(FormatUtils.formatBytes(st.physicalFileSize()));
            activeBytesVal.setText(FormatUtils.formatBytes(st.activeEncryptedBytes()));
            orphanBytesVal.setText(FormatUtils.formatBytes(st.orphanBytes()));
            reclaimableVal.setText(FormatUtils.formatBytes(st.estimatedReclaimableBytes()));
            blockSharingVal.setText(st.uniqueBlockCount() + " tekil / " + st.totalBlockRefCount() + " ref");
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
            previewContainer.getChildren().setAll(new Label("Önizleme alanı"));
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
        detailMeta.setText(String.format("Yol: %s\nTür: %s | Boyut: %s\nBlok Sayısı: %s\nOluşturma: %s\nDeğiştirilme: %s",
                pathStr, e.isFolder() ? "Klasör" : "Dosya", FormatUtils.formatBytes(row.computedSize()),
                e.isFolder() ? "-" : Integer.toString(e.blocks().size()), FormatUtils.formatTimestamp(e.createdAt()), FormatUtils.formatTimestamp(e.modifiedAt())));

        if (e.isFolder()) {
            lastPreviewedId = null;
            VBox box = new VBox(8, SapphireIcons.folder(42, "#60A5FA"), new Label("Klasör içeriğini açmak için çift tıklayın."));
            box.setAlignment(Pos.CENTER);
            previewContainer.getChildren().setAll(box);
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
        }
    }

    private void loadTextPreviewAsync(VaultEntry entry) {
        lastPreviewedId = entry.id();
        previewContainer.getChildren().setAll(new Label("Metin önizlemesi çözülüyor..."));
        bgExecutor.submit(() -> {
            try {
                byte[] bytes = session.readFileBytes(entry.id(), MAX_TEXT_PREVIEW_BYTES, CancellationToken.NONE);
                String text = new String(bytes, StandardCharsets.UTF_8);
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    TextArea area = new TextArea(text);
                    area.setEditable(false);
                    area.setWrapText(false);
                    previewContainer.getChildren().setAll(area);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    previewContainer.getChildren().setAll(new Label("Önizleme hatası: " + ex.getMessage()));
                });
            }
        });
    }

    private void loadImagePreviewAsync(VaultEntry entry) {
        lastPreviewedId = entry.id();
        previewContainer.getChildren().setAll(new Label("Görsel önizlemesi çözülüyor..."));
        bgExecutor.submit(() -> {
            try {
                byte[] bytes = session.readFileBytes(entry.id(), MAX_IMAGE_PREVIEW_BYTES, CancellationToken.NONE);
                Image img = new Image(new ByteArrayInputStream(bytes));
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    ImageView view = new ImageView(img);
                    view.setPreserveRatio(true);
                    view.setFitWidth(275);
                    view.setFitHeight(260);
                    ScrollPane scroll = new ScrollPane(new StackPane(view));
                    scroll.setFitToWidth(true);
                    scroll.setFitToHeight(true);
                    scroll.setStyle("-fx-background-color: transparent;");
                    previewContainer.getChildren().setAll(scroll);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    previewContainer.getChildren().setAll(new Label("Görsel yüklenemedi: " + ex.getMessage()));
                });
            }
        });
    }

    private void openOrPreviewEntry(VaultEntry entry) {
        if (entry.isFolder()) {
            currentFolderId = entry.id();
            refreshAll();
        } else {
            toggleInspector(true);
            String ext = entry.extensionLower();
            if (!(TEXT_EXTENSIONS.contains(ext) && entry.logicalSize() <= MAX_TEXT_PREVIEW_BYTES) && !(IMAGE_EXTENSIONS.contains(ext) && entry.logicalSize() <= MAX_IMAGE_PREVIEW_BYTES)) {
                handleOpenExternal();
            }
        }
    }

    private void handleCreateFolder() {
        VaultDialogs.showTextInputDialog(
                window(), "Yeni Klasör", "Bulunduğunuz dizinde yeni klasör oluşturun", "Klasör Adı:", "").ifPresent(name -> runBackgroundTask("Klasör oluşturuluyor...", false, (cancel, progress) -> {
                    session.createFolder(currentFolderId, name);
                    return "Klasör oluşturuldu: " + name;
                }));
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
        List<EntryRow> selected = new ArrayList<>(entryTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            VaultDialogs.showInfo(window(), "Bilgi", "Seçili öğe yok", "Lütfen dışa aktarmak için en az bir öğe seçin.");
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
        EntryRow sel = entryTable.getSelectionModel().getSelectedItem();
        if (sel == null || !sel.entry().isFile()) {
            return;
        }
        VaultEntry entry = sel.entry();
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            VaultDialogs.showInfo(window(), "Desteklenmiyor", "Bu platformda varsayılan uygulama başlatma desteklenmiyor.", "Lütfen dosyayı 'Dışa Aktar' ile kaydedip açın.");
            return;
        }
        runBackgroundTask(entry.name() + " harici uygulama için hazırlanıyor...", true, (cancel, progress) -> {
            Path tempFile = session.tempPreviewManager().createTempFile(entry.name());
            session.exportFile(entry.id(), tempFile, cancel, progress);
            Desktop.getDesktop().open(tempFile.toFile());
            return entry.name() + " harici uygulamada açıldı (geçici dosya: " + tempFile.getFileName() + ")";
        });
    }

    private void handleRenameSelected() {
        EntryRow sel = entryTable.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        VaultEntry entry = sel.entry();
        VaultDialogs.showTextInputDialog(
                window(), "Yeniden Adlandır", "Yeni ad girin", "Ad:", entry.name()).ifPresent(newName -> {
                    if (!newName.equals(entry.name())) {
                        runBackgroundTask("Yeniden adlandırılıyor...", false, (cancel, progress) -> {
                            session.renameEntry(entry.id(), newName);
                            return "Öğe yeniden adlandırıldı: " + newName;
                        });
                    }
                });
    }

    private void handleDeleteSelected() {
        List<EntryRow> selected = new ArrayList<>(entryTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) return;
        boolean confirmed = VaultDialogs.showConfirm(window(), "Silme Onayı", selected.size() + " öğe kalıcı olarak silinecek", "Kasa içinde geri dönüşüm kutusu yoktur. Referansı kalmayan şifreli bloklar bir sonraki sıkıştırmada temizlenir.");
        if (!confirmed) return;

        List<UUID> ids = selected.stream().map(r -> r.entry().id()).toList();
        runBackgroundTask("Öğeler siliniyor...", false, (cancel, progress) -> {
            session.deleteEntries(ids);
            return ids.size() + " öğe silindi";
        });
    }

    private void handleClipboard(ClipboardMode mode) {
        List<EntryRow> selected = new ArrayList<>(entryTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) return;
        clipboardEntryIds.clear();
        for (EntryRow r : selected) {
            clipboardEntryIds.add(r.entry().id());
        }
        clipboardMode = mode;
        statusLabel.setText(clipboardEntryIds.size() + " öğe " + (mode == ClipboardMode.COPY ? "kopyalandı (bloklar paylaşılır)" : "taşınmak üzere kesildi"));
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
        boolean ok = VaultDialogs.showConfirm(window(), "Kasayı Sıkıştır (Compaction)", "Orphan bloklar temizlenerek yeni kasa dosyası oluşturulacak", "Bu işlem aktif blokları yeni bir geçici .sapphirypt dosyasına kopyalar, doğrular ve güvenli biçimde değiştirir.");
        if (!ok) return;

        runBackgroundTask("Kasa sıkıştırılıyor...", true, (cancel, progress) -> {
            StorageStats after = session.compact(cancel, progress);
            return "Sıkıştırma tamamlandı • Yeni kasa boyutu: " + FormatUtils.formatBytes(after.physicalFileSize());
        });
    }

    @FunctionalInterface
    private interface BackgroundVaultOp {
        String execute(CancellationToken cancel, ProgressListener progress) throws Exception;
    }

    private void runBackgroundTask(String startMsg, boolean cancellable, BackgroundVaultOp op) {
        CancellationToken token = new CancellationToken();
        currentTaskToken.set(token);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setVisible(true);
        statusLabel.setText(startMsg);
        cancelTaskBtn.setVisible(cancellable);
        cancelTaskBtn.setManaged(cancellable);

        ProgressListener throttledProgress = ProgressListener.throttled(100L, (done, total, msg) ->
                Platform.runLater(() -> {
                    if (total > 0) {
                        progressBar.setProgress(Math.min(1.0, (double) done / (double) total));
                    }
                    if (msg != null && !msg.isBlank()) {
                        statusLabel.setText(msg);
                    }
                }));

        bgExecutor.submit(() -> {
            try {
                String resultMsg = op.execute(token, throttledProgress);
                Platform.runLater(() -> {
                    clearTaskUi(resultMsg);
                    refreshAll();
                });
            } catch (VaultCancelledException cancelled) {
                Platform.runLater(() -> {
                    clearTaskUi("İşlem iptal edildi");
                    refreshAll();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    clearTaskUi("İşlem başarısız oldu");
                    refreshAll();
                    VaultDialogs.showError(window(), "İşlem Hatası", ex.getMessage(), ex);
                });
            }
        });
    }

    private void clearTaskUi(String statusText) {
        currentTaskToken.set(null);
        progressBar.setVisible(false);
        progressBar.setProgress(0);
        cancelTaskBtn.setVisible(false);
        cancelTaskBtn.setManaged(false);
        statusLabel.setText(statusText);
    }

    private ConflictResolution askConflictOnFxThread(
            String conflictingName, boolean existingIsFolder, boolean allowOverwrite)
            throws Exception {
        CompletableFuture<ConflictResolution> fut = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                fut.complete(VaultDialogs.showConflictDialog(window(), conflictingName, existingIsFolder, allowOverwrite));
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
        CancellationToken tok = currentTaskToken.getAndSet(null);
        if (tok != null) {
            tok.cancel();
        }
        bgExecutor.shutdownNow();
    }
}
