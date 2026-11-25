package com.sapphircrypt.sapphirypt.ui;

import com.sapphircrypt.sapphirypt.format.VaultMetadata;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.FileVersion;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.StorageStats;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.VaultEntry;
import com.sapphircrypt.sapphirypt.storage.AppSettings;
import com.sapphircrypt.sapphirypt.storage.CancellationToken;
import com.sapphircrypt.sapphirypt.storage.ProgressListener;
import com.sapphircrypt.sapphirypt.storage.SecureFileShredder;
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
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ListCell;
import javafx.scene.shape.Rectangle;
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
import javafx.scene.control.Slider;
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
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.web.WebView;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final long MAX_PDF_PREVIEW_BYTES = 30L * 1024L * 1024L;
    private static final long MAX_MEDIA_PREVIEW_BYTES = 200L * 1024L * 1024L;
    private static final long MAX_DRAG_OUT_EXPORT_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_THUMBNAIL_CACHE = 120;

    private enum ClipboardMode {
        COPY, CUT
    }

    private enum ViewMode {
        LIST, GRID
    }

    private enum SortField {
        NAME, SIZE, MODIFIED, TYPE
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
    private final ExecutorService thumbnailExecutor;
    private final Map<String, Image> thumbnailCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                    return size() > MAX_THUMBNAIL_CACHE;
                }
            });
    private final Set<String> pendingThumbKeys = ConcurrentHashMap.newKeySet();
    private final Runnable themeListener = this::refreshThemeSensitiveUi;

    private UUID currentFolderId = VaultMetadata.ROOT_ID;
    private final AtomicReference<CancellationToken> currentTaskToken = new AtomicReference<>();
    private final List<UUID> clipboardEntryIds = new ArrayList<>();
    private ClipboardMode clipboardMode;

    // View & selection state
    private ViewMode viewMode = ViewMode.GRID;
    private SortField sortField = SortField.NAME;
    private boolean sortAscending = true;
    private List<EntryRow> currentDisplayedRows = List.of();
    private final Set<UUID> gridSelectedIds = new LinkedHashSet<>();
    private UUID lastGridAnchorId;
    private Set<UUID> lastDisplayedIds = new LinkedHashSet<>();
    private UUID lastDisplayedFolderId;
    private String lastFilterSignature = "";
    private final Set<UUID> pendingAnimatedRowIds = new HashSet<>();

    // Top action bar components
    private final TreeView<VaultEntry> folderTree = new TreeView<>();
    private final HBox breadcrumbBar = new HBox(4);
    private MenuButton addMenuBtn;
    private MenuItem addFilesItem;
    private MenuItem addFolderItem;
    private MenuItem newFolderItem;
    private MenuItem newNoteItem;
    private final TextField searchField = new TextField();
    private final CheckBox globalSearchCheck = new CheckBox();
    private final ComboBox<String> extFilterCombo = new ComboBox<>();
    private final ComboBox<String> tagFilterCombo = new ComboBox<>();
    private final ToggleButton favoritesFilterBtn = new ToggleButton("★");
    private final ComboBox<SortField> sortFieldCombo = new ComboBox<>();
    private final Button sortDirBtn = new Button("↑");
    private final Button listModeBtn = new Button();
    private final Button gridModeBtn = new Button();
    private MotionFx.SegmentedSwitch viewSwitch;
    private final Button exportBtn = new Button();
    private final ToggleButton previewToggle = new ToggleButton();

    // Sidebar / Center / Context UI labels
    private Label treeHeader;
    private Label statsHeader;
    private Label statLogicalKey;
    private Label statPhysicalKey;
    private Label statActiveKey;
    private Label statOrphanKey;
    private Label statReclaimKey;
    private Button compactBtn;
    private Button detailsToggleBtn;
    private Button upBtn;
    private Label dropHint;
    private Label tableEmptyLbl;
    private TableColumn<EntryRow, EntryRow> nameCol;
    private TableColumn<EntryRow, String> typeCol;
    private TableColumn<EntryRow, Long> sizeCol;
    private TableColumn<EntryRow, Long> modifiedCol;
    private MenuItem ctxPreviewItem;
    private MenuItem ctxExternalItem;
    private MenuItem ctxExportItem;
    private MenuItem ctxFavoriteItem;
    private MenuItem ctxTagsItem;
    private MenuItem ctxRenameItem;
    private MenuItem ctxCopyItem;
    private MenuItem ctxCutItem;
    private MenuItem ctxPasteItem;
    private MenuItem ctxDeleteItem;

    // Center content views
    private final TableView<EntryRow> entryTable = new TableView<>();
    private final FlowPane gridFlow = new FlowPane(12, 12);
    private final ScrollPane gridScroll = new ScrollPane(gridFlow);
    private final StackPane gridWrapper = new StackPane();
    private final Rectangle lassoRect = new Rectangle();
    private boolean lassoDragging;
    private boolean suppressNextGridClickClear;
    private double lassoStartX;
    private double lassoStartY;
    private boolean lassoAdditive;
    private Set<UUID> lassoBaselineIds = Set.of();
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
    private Label inspectorHeader;
    private Label extNote;
    private final Label detailName = new Label();
    private final Label detailMeta = new Label();
    private final Button inspectorFavBtn = new Button();
    private final Button inspectorTagsBtn = new Button();
    private final FlowPane inspectorTagsBox = new FlowPane(6, 6);
    private final VBox versionHistorySection = new VBox(6);
    private final Label versionHistoryHeader = new Label();
    private final Button versionClearAllBtn = new Button();
    private final VBox versionHistoryList = new VBox(6);
    private final ScrollPane versionHistoryScroll = new ScrollPane(versionHistoryList);
    private final StackPane previewContainer = new StackPane();
    private final Button openExternalBtn = new Button();
    private final Button exportSelectedBtn = new Button();

    // Integrated status bar & floating operation pill
    private final Label statusCountLbl = new Label();
    private final Label statusEnvLbl = new Label();
    private final HBox floatingProgressPill = new HBox(10);
    private final ProgressBar pillProgressBar = new ProgressBar(0);
    private final Label pillStatusLbl = new Label();
    private final Button pillCancelBtn = new Button();

    private SplitPane mainSplit;
    private boolean inspectorVisible = true;
    private volatile UUID lastPreviewedId;
    private MediaPlayer activeMediaPlayer;
    private volatile UUID pendingSelectEntryId;

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
        this.thumbnailExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "sapphirypt-thumbnails");
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
                ? I18n.t("Otomatik Kilit: Kapalı", "Auto-Lock: Off")
                : I18n.t("Kilit: ", "Lock: ") + settings.autoLockMinutes() + I18n.t(" dk", " min");
        statusEnvLbl.setText(I18n.cpuProfileLabel(session.cpuProfile()) + " • " + lockDesc);
    }

    public void applyViewSettings() {
        setViewMode(settings.defaultGridView() ? ViewMode.GRID : ViewMode.LIST);
        toggleInspector(settings.previewEnabledByDefault());
    }

    public void refreshLanguage() {
        addMenuBtn.setText(I18n.t("Ekle", "Add"));
        addFilesItem.setText(I18n.t("Dosya Ekle...", "Add Files..."));
        addFolderItem.setText(I18n.t("Klasör Ekle...", "Add Folder..."));
        newFolderItem.setText(I18n.t("Yeni Klasör", "New Folder"));
        if (newNoteItem != null) {
            newNoteItem.setText(I18n.t("Yeni Not...", "New Note..."));
        }
        exportBtn.setText(I18n.t("Dışa Aktar", "Export"));
        searchField.setPromptText(I18n.t("Kasada ara...", "Search vault..."));
        globalSearchCheck.setText(I18n.t("Tüm kasada", "Entire vault"));
        favoritesFilterBtn.setTooltip(new Tooltip(I18n.t("Yalnızca Favoriler", "Favorites Only")));
        sortDirBtn.setTooltip(new Tooltip(I18n.t("Sıralama Yönü", "Sort Direction")));
        configureSortComboCells();
        listModeBtn.setTooltip(new Tooltip(I18n.t("Liste Görünümü", "List View")));
        gridModeBtn.setTooltip(new Tooltip(I18n.t("Kart / Izgara Görünümü", "Grid View")));
        previewToggle.setText(I18n.t("Önizleme", "Preview"));

        treeHeader.setText(I18n.t("KLASÖRLER", "FOLDERS"));
        statsHeader.setText(I18n.t("DEPOLAMA", "STORAGE"));
        statLogicalKey.setText(I18n.t("Dosya Boyutu:", "Logical Size:"));
        statPhysicalKey.setText(I18n.t("Kasa Dosyası:", "Vault File:"));
        statActiveKey.setText(I18n.t("Kullanılan Alan:", "Used Space:"));
        statOrphanKey.setText(I18n.t("Boşta Alan:", "Unused Space:"));
        statReclaimKey.setText(I18n.t("Kazanılabilecek Alan:", "Reclaimable:"));
        compactBtn.setText(I18n.t("Sıkıştır", "Compact"));
        detailsToggleBtn.setText(storageDetailsBox.isVisible() ? I18n.t("Gizle", "Hide") : I18n.t("Detaylar", "Details"));

        upBtn.setText(I18n.t("Üst", "Up"));
        dropHint.setText(I18n.t(
                "İpucu: Masaüstünden dosya veya klasörleri kasaya sürükleyebilir, kasadan masaüstüne dışa sürükleyebilirsiniz.",
                "Tip: Drag files into the vault or drag items out to your desktop to export."));
        tableEmptyLbl.setText(emptyFolderText());
        nameCol.setText(I18n.t("Ad", "Name"));
        typeCol.setText(I18n.t("Tür", "Type"));
        sizeCol.setText(I18n.t("Boyut", "Size"));
        modifiedCol.setText(I18n.t("Değiştirilme", "Modified"));

        ctxPreviewItem.setText(I18n.t("Önizle / Aç", "Preview / Open"));
        ctxExternalItem.setText(I18n.t("Harici Uygulamada Aç", "Open in External App"));
        ctxExportItem.setText(I18n.t("Dışa Aktar...", "Export..."));
        ctxFavoriteItem.setText(I18n.t("Favori Ekle / Kaldır", "Toggle Favorite"));
        ctxTagsItem.setText(I18n.t("Etiketleri Düzenle...", "Edit Tags..."));
        ctxRenameItem.setText(I18n.t("Yeniden Adlandır (F2)", "Rename (F2)"));
        ctxCopyItem.setText(I18n.t("Kopyala (Ctrl+C)", "Copy (Ctrl+C)"));
        ctxCutItem.setText(I18n.t("Kes / Taşı (Ctrl+X)", "Cut / Move (Ctrl+X)"));
        ctxPasteItem.setText(I18n.t("Yapıştır (Ctrl+V)", "Paste (Ctrl+V)"));
        ctxDeleteItem.setText(I18n.t("Sil (Del)", "Delete (Del)"));

        inspectorHeader.setText(I18n.t("ÖNİZLEME VE AYRINTILAR", "PREVIEW & DETAILS"));
        inspectorTagsBtn.setText(I18n.t("Etiketler", "Tags"));
        versionClearAllBtn.setText(I18n.t("Temizle", "Clear"));
        openExternalBtn.setText(I18n.t("Harici Uygulamada Aç", "Open in External App"));
        exportSelectedBtn.setText(I18n.t("Dışa Aktar", "Export"));
        extNote.setText(I18n.t(
                "Harici uygulamada açılan dosyadaki değişiklikler otomatik olarak kasaya geri kaydedilir; kasa kilitlendiğinde geçici kopya temizlenir.",
                "Edits saved in an external app are synced back into the vault automatically; temporary copies are wiped when locked."));
        pillCancelBtn.setText(I18n.t("İptal", "Cancel"));

        refreshAll();
        updateInspector(primarySelectedRow());
    }

    private static String allTypesLabel() {
        return I18n.t("Tüm Türler", "All Types");
    }

    private static boolean isAllTypesFilter(String val) {
        return val == null || "Tüm Türler".equals(val) || "All Types".equals(val);
    }

    private static String allTagsLabel() {
        return I18n.t("Tüm Etiketler", "All Tags");
    }

    private static boolean isAllTagsFilter(String val) {
        return val == null || "Tüm Etiketler".equals(val) || "All Tags".equals(val);
    }

    private static String sortFieldLabel(SortField field) {
        if (field == null) {
            return I18n.t("Ad", "Name");
        }
        return switch (field) {
            case NAME -> I18n.t("Ad", "Name");
            case SIZE -> I18n.t("Boyut", "Size");
            case MODIFIED -> I18n.t("Tarih", "Date");
            case TYPE -> I18n.t("Tür", "Type");
        };
    }

    private void configureSortComboCells() {
        sortFieldCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SortField item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : sortFieldLabel(item));
            }
        });
        sortFieldCombo.setButtonCell(sortFieldCombo.getCellFactory().call(null));
    }

    private static String emptyFolderText() {
        return I18n.t(
                "Bu klasör boş. Dosya ekleyin veya sürükleyip bırakın.",
                "This folder is empty. Add files or drag and drop them here.");
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
        addFilesItem = new MenuItem(I18n.t("Dosya Ekle...", "Add Files..."));
        addFilesItem.setOnAction(e -> handleAddFiles());
        addFolderItem = new MenuItem(I18n.t("Klasör Ekle...", "Add Folder..."));
        addFolderItem.setOnAction(e -> handleAddDirectory());
        newFolderItem = new MenuItem(I18n.t("Yeni Klasör", "New Folder"));
        newFolderItem.setOnAction(e -> handleCreateFolder());
        newNoteItem = new MenuItem(I18n.t("Yeni Not...", "New Note..."));
        newNoteItem.setOnAction(e -> handleCreateNote());

        addMenuBtn = new MenuButton(
                I18n.t("Ekle", "Add"),
                SapphireIcons.plus(14, "#FFFFFF"),
                addFilesItem, addFolderItem, new SeparatorMenuItem(), newFolderItem, newNoteItem);
        addMenuBtn.getStyleClass().add("btn-primary");
        MotionFx.attachPressSpring(addMenuBtn);

        exportBtn.setText(I18n.t("Dışa Aktar", "Export"));
        MotionFx.attachPressSpring(exportBtn);
        exportBtn.setOnAction(e -> handleExportSelected());

        searchField.setPromptText(I18n.t("Kasada ara...", "Search vault..."));
        searchField.setPrefWidth(165);
        searchField.setMinWidth(110);
        searchField.textProperty().addListener((o, a, b) -> refreshTableOnly());

        globalSearchCheck.setText(I18n.t("Tüm kasada", "Entire vault"));
        globalSearchCheck.setMinWidth(Region.USE_PREF_SIZE);
        MotionFx.enhanceCheckBox(globalSearchCheck);
        globalSearchCheck.selectedProperty().addListener((o, a, b) -> refreshTableOnly());

        String allTypes = allTypesLabel();
        extFilterCombo.getItems().add(allTypes);
        extFilterCombo.setValue(allTypes);
        MotionFx.enhanceComboBox(extFilterCombo);
        extFilterCombo.valueProperty().addListener((o, a, b) -> refreshTableOnly());

        String allTags = allTagsLabel();
        tagFilterCombo.getItems().add(allTags);
        tagFilterCombo.setValue(allTags);
        MotionFx.enhanceComboBox(tagFilterCombo);
        tagFilterCombo.valueProperty().addListener((o, a, b) -> refreshTableOnly());

        favoritesFilterBtn.setTooltip(new Tooltip(I18n.t("Yalnızca Favoriler", "Favorites Only")));
        favoritesFilterBtn.setMinWidth(Region.USE_PREF_SIZE);
        MotionFx.attachPressSpring(favoritesFilterBtn);
        favoritesFilterBtn.selectedProperty().addListener((o, a, selected) -> {
            favoritesFilterBtn.getStyleClass().remove("favorite-star-active");
            if (Boolean.TRUE.equals(selected)) {
                favoritesFilterBtn.getStyleClass().add("favorite-star-active");
            }
            refreshTableOnly();
        });

        sortFieldCombo.getItems().addAll(SortField.values());
        sortFieldCombo.setValue(sortField);
        configureSortComboCells();
        MotionFx.enhanceComboBox(sortFieldCombo);
        sortFieldCombo.valueProperty().addListener((o, a, nextField) -> {
            if (nextField != null) {
                sortField = nextField;
                entryTable.getSortOrder().clear();
                refreshTableOnly();
            }
        });

        sortDirBtn.setTooltip(new Tooltip(I18n.t("Sıralama Yönü", "Sort Direction")));
        sortDirBtn.setMinWidth(Region.USE_PREF_SIZE);
        MotionFx.attachPressSpring(sortDirBtn);
        sortDirBtn.setOnAction(e -> {
            sortAscending = !sortAscending;
            sortDirBtn.setText(sortAscending ? "↑" : "↓");
            entryTable.getSortOrder().clear();
            refreshTableOnly();
        });

        listModeBtn.getStyleClass().add("segmented-btn");
        listModeBtn.setTooltip(new Tooltip(I18n.t("Liste Görünümü", "List View")));
        listModeBtn.setOnAction(e -> setViewMode(ViewMode.LIST));

        gridModeBtn.getStyleClass().add("segmented-btn");
        gridModeBtn.setTooltip(new Tooltip(I18n.t("Kart / Izgara Görünümü", "Grid View")));
        gridModeBtn.setOnAction(e -> setViewMode(ViewMode.GRID));

        viewSwitch = new MotionFx.SegmentedSwitch(2, listModeBtn, gridModeBtn);
        viewSwitch.setActive(viewMode == ViewMode.LIST ? listModeBtn : gridModeBtn, false);

        previewToggle.setText(I18n.t("Önizleme", "Preview"));
        previewToggle.setSelected(inspectorVisible);
        MotionFx.attachPressSpring(previewToggle);
        previewToggle.setOnAction(e -> toggleInspector(previewToggle.isSelected()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(
                8,
                addMenuBtn,
                exportBtn,
                new Separator(Orientation.VERTICAL),
                searchField,
                globalSearchCheck,
                extFilterCombo,
                tagFilterCombo,
                favoritesFilterBtn,
                sortFieldCombo,
                sortDirBtn,
                spacer,
                viewSwitch,
                previewToggle);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar-bar");
        updateToolbarIcons();
        return bar;
    }

    private VBox buildSidebar() {
        treeHeader = new Label(I18n.t("KLASÖRLER", "FOLDERS"));
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
        statsHeader = new Label(I18n.t("DEPOLAMA", "STORAGE"));
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

        statLogicalKey = new Label(I18n.t("Dosya Boyutu:", "Logical Size:"));
        statPhysicalKey = new Label(I18n.t("Kasa Dosyası:", "Vault File:"));
        statActiveKey = new Label(I18n.t("Kullanılan Alan:", "Used Space:"));
        statOrphanKey = new Label(I18n.t("Boşta Alan:", "Unused Space:"));
        statReclaimKey = new Label(I18n.t("Kazanılabilecek Alan:", "Reclaimable:"));
        storageDetailsBox.getChildren().setAll(
                statRow(statLogicalKey, logicalSizeVal),
                statRow(statPhysicalKey, physicalSizeVal),
                statRow(statActiveKey, activeBytesVal),
                statRow(statOrphanKey, orphanBytesVal),
                statRow(statReclaimKey, reclaimableVal));
        storageDetailsBox.setVisible(false);
        storageDetailsBox.setManaged(false);
        storageDetailsBox.setPadding(new Insets(4, 0, 2, 0));

        compactBtn = new Button(I18n.t("Sıkıştır", "Compact"));
        compactBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(compactBtn, Priority.ALWAYS);
        MotionFx.attachPressSpring(compactBtn);
        compactBtn.setOnAction(e -> handleCompact());

        detailsToggleBtn = new Button(I18n.t("Detaylar", "Details"));
        detailsToggleBtn.getStyleClass().add("btn-ghost");
        detailsToggleBtn.setOnAction(e -> {
            boolean show = !storageDetailsBox.isVisible();
            storageDetailsBox.setVisible(show);
            storageDetailsBox.setManaged(show);
            detailsToggleBtn.setText(show ? I18n.t("Gizle", "Hide") : I18n.t("Detaylar", "Details"));
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

    private HBox statRow(Label k, Label valueLabel) {
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
        upBtn = new Button(I18n.t("Üst", "Up"));
        upBtn.getStyleClass().add("btn-ghost");
        upBtn.setOnAction(e -> navigateUp());

        HBox navRow = new HBox(8, upBtn, new Separator(Orientation.VERTICAL), breadcrumbBar);
        navRow.setAlignment(Pos.CENTER_LEFT);

        configureTable();
        configureGrid();

        boolean isList = viewMode == ViewMode.LIST;
        entryTable.setVisible(isList);
        entryTable.setManaged(isList);
        gridWrapper.setVisible(!isList);
        gridWrapper.setManaged(!isList);
        contentSwitchPane.getChildren().setAll(entryTable, gridWrapper);
        VBox.setVgrow(contentSwitchPane, Priority.ALWAYS);

        dropHint = new Label(I18n.t(
                "İpucu: Masaüstünden dosya veya klasörleri kasaya sürükleyebilir, kasadan masaüstüne dışa sürükleyebilirsiniz.",
                "Tip: Drag files into the vault or drag items out to your desktop to export."));
        dropHint.getStyleClass().add("muted-text");
        dropHint.setStyle("-fx-font-size: 11px;");

        VBox center = new VBox(10, navRow, contentSwitchPane, dropHint);
        center.setPadding(new Insets(12));
        return center;
    }

    private void configureTable() {
        entryTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tableEmptyLbl = new Label(emptyFolderText());
        tableEmptyLbl.getStyleClass().add("muted-text");
        entryTable.setPlaceholder(tableEmptyLbl);

        nameCol = new TableColumn<>(I18n.t("Ad", "Name"));
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
                    VaultEntry item = row.entry();
                    Node icon = item.isFolder()
                            ? SapphireIcons.folder(16, shell.accentIconColor())
                            : SapphireIcons.file(16, shell.mutedIconColor());
                    Label textLbl = new Label(item.name());
                    textLbl.getStyleClass().add("primary-text");
                    HBox box = new HBox(6, icon, textLbl);
                    box.setAlignment(Pos.CENTER_LEFT);
                    if (item.favorite()) {
                        Label star = new Label("★");
                        star.getStyleClass().add("favorite-star-active");
                        box.getChildren().add(star);
                    }
                    int shownTags = 0;
                    for (String tag : item.tags()) {
                        if (shownTags >= 2) break;
                        Label pill = new Label(tag);
                        pill.getStyleClass().add("tag-pill");
                        box.getChildren().add(pill);
                        shownTags++;
                    }
                    setText(null);
                    setGraphic(box);
                }
            }
        });

        typeCol = new TableColumn<>(I18n.t("Tür", "Type"));
        typeCol.setPrefWidth(90);
        typeCol.setCellValueFactory(data -> {
            VaultEntry e = data.getValue().entry();
            if (e.isFolder()) return new ReadOnlyStringWrapper(I18n.t("Klasör", "Folder"));
            String ext = e.extensionLower();
            return new ReadOnlyStringWrapper(ext.isEmpty() ? I18n.t("Dosya", "File") : ext.toUpperCase(Locale.ROOT));
        });

        sizeCol = new TableColumn<>(I18n.t("Boyut", "Size"));
        sizeCol.setPrefWidth(125);
        sizeCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().computedSize()));
        sizeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Long val, boolean empty) {
                super.updateItem(val, empty);
                setText(empty || val == null ? null : FormatUtils.formatBytes(val));
            }
        });

        modifiedCol = new TableColumn<>(I18n.t("Değiştirilme", "Modified"));
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
                    Dragboard db = row.startDragAndDrop(TransferMode.COPY, TransferMode.MOVE);
                    ClipboardContent content = new ClipboardContent();
                    content.put(INTERNAL_ENTRY_IDS, selected.stream().map(r -> r.entry().id().toString()).toList());
                    List<File> dragOutFiles = prepareExternalDragFiles(selected);
                    if (!dragOutFiles.isEmpty()) {
                        content.putFiles(dragOutFiles);
                    }
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

        lassoRect.setManaged(false);
        lassoRect.setMouseTransparent(true);
        lassoRect.setVisible(false);
        lassoRect.getStyleClass().add("lasso-rect");

        gridWrapper.getChildren().setAll(gridScroll, lassoRect);

        gridScroll.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, ev -> {
            if (ev.getButton() != javafx.scene.input.MouseButton.PRIMARY || isInsideGridCard(ev.getTarget())) {
                lassoDragging = false;
                return;
            }
            javafx.geometry.Point2D pt = gridWrapper.sceneToLocal(ev.getSceneX(), ev.getSceneY());
            lassoStartX = pt.getX();
            lassoStartY = pt.getY();
            lassoAdditive = ev.isControlDown() || ev.isMetaDown();
            lassoBaselineIds = lassoAdditive ? new LinkedHashSet<>(gridSelectedIds) : Set.of();
            lassoDragging = true;
            suppressNextGridClickClear = false;
        });

        gridScroll.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, ev -> {
            if (!lassoDragging) {
                return;
            }
            javafx.geometry.Point2D pt = gridWrapper.sceneToLocal(ev.getSceneX(), ev.getSceneY());
            double curX = Math.max(0.0, Math.min(gridWrapper.getWidth(), pt.getX()));
            double curY = Math.max(0.0, Math.min(gridWrapper.getHeight(), pt.getY()));
            double minX = Math.min(lassoStartX, curX);
            double minY = Math.min(lassoStartY, curY);
            double w = Math.abs(curX - lassoStartX);
            double h = Math.abs(curY - lassoStartY);
            if (w < 5.0 && h < 5.0 && !lassoRect.isVisible()) {
                return;
            }
            suppressNextGridClickClear = true;
            lassoRect.setVisible(true);
            lassoRect.setX(minX);
            lassoRect.setY(minY);
            lassoRect.setWidth(w);
            lassoRect.setHeight(h);

            Bounds selectionBounds = lassoRect.getBoundsInParent();
            Set<UUID> hitIds = new LinkedHashSet<>(lassoBaselineIds);
            for (Node child : gridFlow.getChildren()) {
                if (child.getUserData() instanceof UUID id) {
                    Bounds cardBounds = gridWrapper.sceneToLocal(child.localToScene(child.getBoundsInLocal()));
                    if (cardBounds != null && cardBounds.intersects(selectionBounds)) {
                        hitIds.add(id);
                    }
                }
            }
            if (!hitIds.equals(gridSelectedIds)) {
                gridSelectedIds.clear();
                gridSelectedIds.addAll(hitIds);
                updateGridSelectionStyles();
                updateInspector(primarySelectedRow());
                refreshStatusCounts();
            }
            ev.consume();
        });

        gridScroll.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, ev -> {
            if (lassoDragging) {
                lassoDragging = false;
                if (lassoRect.isVisible()) {
                    lassoRect.setVisible(false);
                    ev.consume();
                }
            }
        });

        gridScroll.setOnMouseClicked(ev -> {
            if (suppressNextGridClickClear) {
                suppressNextGridClickClear = false;
                return;
            }
            if (!isInsideGridCard(ev.getTarget())) {
                gridSelectedIds.clear();
                lastGridAnchorId = null;
                updateGridSelectionStyles();
                updateInspector(null);
                refreshStatusCounts();
            }
        });
        configureExternalDrop(gridScroll);
        attachKeyboardShortcuts(gridScroll);
    }

    private static boolean isInsideGridCard(Object target) {
        if (!(target instanceof Node node)) {
            return false;
        }
        Node current = node;
        while (current != null) {
            if (current.getUserData() instanceof UUID) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private void configureExternalDrop(Node dropNode) {
        dropNode.setOnDragOver(ev -> {
            if (ev.getDragboard().hasFiles() && !ev.getDragboard().hasContent(INTERNAL_ENTRY_IDS)) {
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
            if (db.hasFiles() && !db.hasContent(INTERNAL_ENTRY_IDS)) {
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
        KeyCombination ctrlA = new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN);
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
            } else if (ctrlA.match(ev)) {
                selectAllVisibleItems();
                ev.consume();
            } else if (ctrlC.match(ev)) {
                handleClipboard(ClipboardMode.COPY);
            } else if (ctrlX.match(ev)) {
                handleClipboard(ClipboardMode.CUT);
            } else if (ctrlV.match(ev)) {
                handlePaste();
            }
        });
    }

    private void selectAllVisibleItems() {
        if (viewMode == ViewMode.LIST) {
            entryTable.getSelectionModel().selectAll();
            return;
        }
        gridSelectedIds.clear();
        for (EntryRow r : currentDisplayedRows) {
            gridSelectedIds.add(r.entry().id());
        }
        updateGridSelectionStyles();
        updateInspector(primarySelectedRow());
        refreshStatusCounts();
    }

    private void setViewMode(ViewMode next) {
        if (next == viewMode) {
            return;
        }
        viewMode = next;
        boolean isList = viewMode == ViewMode.LIST;
        entryTable.setVisible(isList);
        entryTable.setManaged(isList);
        gridWrapper.setVisible(!isList);
        gridWrapper.setManaged(!isList);

        viewSwitch.setActive(isList ? listModeBtn : gridModeBtn, true);
        if (!isList) {
            rebuildGridCards();
        }
        MotionFx.playEntrance(isList ? entryTable : gridWrapper, 8.0, 0.0, 190.0);
        updateToolbarIcons();
        refreshStatusCounts();
    }

    private void rebuildGridCards() {
        rebuildGridCards(Set.of());
    }

    private void rebuildGridCards(Set<UUID> newlyAddedIds) {
        gridFlow.getChildren().clear();
        if (currentDisplayedRows.isEmpty()) {
            Label emptyLbl = new Label(emptyFolderText());
            emptyLbl.getStyleClass().add("muted-text");
            emptyLbl.setPadding(new Insets(24));
            gridFlow.getChildren().add(emptyLbl);
            return;
        }

        int addedAnimIdx = 0;
        for (EntryRow row : currentDisplayedRows) {
            VaultEntry e = row.entry();
            boolean selected = gridSelectedIds.contains(e.id());

            Node baseIcon = e.isFolder()
                    ? SapphireIcons.folder(38, shell.accentIconColor())
                    : SapphireIcons.file(38, shell.mutedIconColor());
            StackPane iconPane = new StackPane(baseIcon);
            iconPane.setPrefSize(124, 62);
            iconPane.setMaxSize(124, 62);
            if (!e.isFolder()
                    && (MediaFormatSupport.isStandardImage(e.extensionLower()) || MediaFormatSupport.isHeic(e.extensionLower()))
                    && e.logicalSize() > 0L
                    && e.logicalSize() <= MAX_IMAGE_PREVIEW_BYTES) {
                attachGridThumbnailAsync(e, iconPane);
            }
            if (e.favorite()) {
                Label starBadge = new Label("★");
                starBadge.getStyleClass().add("favorite-star-active");
                starBadge.setStyle("-fx-font-size: 13px;");
                StackPane.setAlignment(starBadge, Pos.TOP_RIGHT);
                iconPane.getChildren().add(starBadge);
            }

            Label nameLbl = new Label(e.name());
            nameLbl.getStyleClass().add("primary-text");
            nameLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
            nameLbl.setMaxWidth(128);
            nameLbl.setAlignment(Pos.CENTER);

            String metaText = e.isFolder() ? I18n.t("Klasör", "Folder") : FormatUtils.formatBytes(row.computedSize());
            Label metaLbl = new Label(metaText);
            metaLbl.getStyleClass().add("muted-text");
            metaLbl.setStyle("-fx-font-size: 11px;");

            VBox card = new VBox(6, iconPane, nameLbl, metaLbl);
            if (!e.tags().isEmpty()) {
                HBox tagsRow = new HBox(4);
                tagsRow.setAlignment(Pos.CENTER);
                int maxTags = Math.min(2, e.tags().size());
                for (int t = 0; t < maxTags; t++) {
                    Label pill = new Label(e.tags().get(t));
                    pill.getStyleClass().add("tag-pill");
                    pill.setMaxWidth(58);
                    tagsRow.getChildren().add(pill);
                }
                card.getChildren().add(tagsRow);
            }
            card.setAlignment(Pos.CENTER);
            card.setPrefSize(152, 138);
            card.setPadding(new Insets(10, 12, 10, 12));
            card.setUserData(e.id());
            card.getStyleClass().add(selected ? "grid-item-card-selected" : "grid-item-card");

            card.setOnMouseClicked(ev -> {
                gridScroll.requestFocus();
                if (ev.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                    if (!gridSelectedIds.contains(e.id())) {
                        gridSelectedIds.clear();
                        gridSelectedIds.add(e.id());
                        lastGridAnchorId = e.id();
                        updateGridSelectionStyles();
                    }
                    updateInspector(row);
                    refreshStatusCounts();
                    return;
                }
                if (ev.isShiftDown() && lastGridAnchorId != null) {
                    selectGridRange(lastGridAnchorId, e.id(), ev.isControlDown() || ev.isMetaDown());
                } else if (ev.isControlDown() || ev.isMetaDown()) {
                    if (gridSelectedIds.contains(e.id())) {
                        gridSelectedIds.remove(e.id());
                    } else {
                        gridSelectedIds.add(e.id());
                    }
                    lastGridAnchorId = e.id();
                } else {
                    gridSelectedIds.clear();
                    gridSelectedIds.add(e.id());
                    lastGridAnchorId = e.id();
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
                    lastGridAnchorId = e.id();
                    updateGridSelectionStyles();
                }
                List<EntryRow> selectedRows = currentSelectedRows();
                Dragboard db = card.startDragAndDrop(TransferMode.COPY, TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.put(INTERNAL_ENTRY_IDS, gridSelectedIds.stream().map(UUID::toString).toList());
                List<File> dragOutFiles = prepareExternalDragFiles(selectedRows);
                if (!dragOutFiles.isEmpty()) {
                    content.putFiles(dragOutFiles);
                }
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

    private void attachGridThumbnailAsync(VaultEntry entry, StackPane iconPane) {
        String key = entry.id() + ":" + entry.modifiedAt();
        Image cached = thumbnailCache.get(key);
        if (cached != null) {
            installGridThumbnail(iconPane, cached);
            return;
        }
        if (!pendingThumbKeys.add(key)) {
            return;
        }
        thumbnailExecutor.submit(() -> {
            try {
                if (session.isClosed()) {
                    return;
                }
                byte[] bytes = session.readFileBytes(entry.id(), MAX_IMAGE_PREVIEW_BYTES, CancellationToken.NONE);
                byte[] sourceBytes = MediaFormatSupport.isHeic(entry.extensionLower())
                        ? MediaFormatSupport.extractEmbeddedJpegFromHeic(bytes)
                        : bytes;
                if (sourceBytes == null || sourceBytes.length == 0) {
                    return;
                }
                Image fxThumb = new Image(new ByteArrayInputStream(sourceBytes), 200, 120, true, true);
                Image resolved = (!fxThumb.isError() && fxThumb.getWidth() > 0 && fxThumb.getHeight() > 0)
                        ? fxThumb
                        : decodePreviewImage(sourceBytes);
                if (resolved != null && !resolved.isError()) {
                    thumbnailCache.put(key, resolved);
                    Platform.runLater(() -> {
                        if (iconPane.getScene() != null) {
                            installGridThumbnail(iconPane, resolved);
                        }
                    });
                }
            } catch (Exception ignored) {
            } finally {
                pendingThumbKeys.remove(key);
            }
        });
    }

    private static void installGridThumbnail(StackPane iconPane, Image img) {
        ImageView thumbView = new ImageView(img);
        thumbView.setFitWidth(114);
        thumbView.setFitHeight(60);
        thumbView.setPreserveRatio(true);
        thumbView.setSmooth(true);
        Rectangle clip = new Rectangle(114, 60);
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        thumbView.setClip(clip);
        // Replace center icon while preserving optional top-right favorite badge
        Node badge = null;
        if (iconPane.getChildren().size() > 1) {
            badge = iconPane.getChildren().get(iconPane.getChildren().size() - 1);
        }
        if (badge != null) {
            iconPane.getChildren().setAll(thumbView, badge);
        } else {
            iconPane.getChildren().setAll(thumbView);
        }
    }

    private void selectGridRange(UUID anchorId, UUID targetId, boolean additive) {
        int aIdx = -1;
        int tIdx = -1;
        for (int i = 0; i < currentDisplayedRows.size(); i++) {
            UUID id = currentDisplayedRows.get(i).entry().id();
            if (id.equals(anchorId)) aIdx = i;
            if (id.equals(targetId)) tIdx = i;
        }
        if (aIdx < 0 || tIdx < 0) {
            if (!additive) gridSelectedIds.clear();
            gridSelectedIds.add(targetId);
            return;
        }
        if (!additive) {
            gridSelectedIds.clear();
        }
        int start = Math.min(aIdx, tIdx);
        int end = Math.max(aIdx, tIdx);
        for (int i = start; i <= end; i++) {
            gridSelectedIds.add(currentDisplayedRows.get(i).entry().id());
        }
    }

    private List<File> prepareExternalDragFiles(List<EntryRow> selected) {
        if (selected == null || selected.isEmpty()) {
            return List.of();
        }
        long totalBytes = 0L;
        for (EntryRow r : selected) {
            totalBytes += Math.max(0L, r.computedSize());
            if (totalBytes > MAX_DRAG_OUT_EXPORT_BYTES) {
                return List.of();
            }
        }
        try {
            Path stagingDir = session.tempPreviewManager().createExportStagingDir();
            List<File> exportedFiles = new ArrayList<>(selected.size());
            for (EntryRow r : selected) {
                VaultEntry entry = r.entry();
                if (entry.isFolder()) {
                    session.exportFolder(entry.id(), stagingDir, CancellationToken.NONE, ProgressListener.NO_OP);
                    Path destFolder = stagingDir.resolve(entry.name());
                    if (Files.exists(destFolder)) {
                        exportedFiles.add(destFolder.toFile());
                    }
                } else {
                    Path destFile = stagingDir.resolve(entry.name());
                    session.exportFile(entry.id(), destFile, CancellationToken.NONE, ProgressListener.NO_OP);
                    session.tempPreviewManager().hardenFile(destFile);
                    if (Files.exists(destFile)) {
                        exportedFiles.add(destFile.toFile());
                    }
                }
            }
            return exportedFiles;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private ContextMenu buildContextMenu() {
        ctxPreviewItem = new MenuItem(I18n.t("Önizle / Aç", "Preview / Open"));
        ctxPreviewItem.setOnAction(e -> {
            EntryRow sel = primarySelectedRow();
            if (sel != null) openOrPreviewEntry(sel.entry());
        });

        ctxExternalItem = new MenuItem(I18n.t("Harici Uygulamada Aç", "Open in External App"));
        ctxExternalItem.setOnAction(e -> handleOpenExternal());

        ctxExportItem = new MenuItem(I18n.t("Dışa Aktar...", "Export..."));
        ctxExportItem.setOnAction(e -> handleExportSelected());

        ctxFavoriteItem = new MenuItem(I18n.t("Favori Ekle / Kaldır", "Toggle Favorite"));
        ctxFavoriteItem.setOnAction(e -> handleToggleFavoriteSelected());

        ctxTagsItem = new MenuItem(I18n.t("Etiketleri Düzenle...", "Edit Tags..."));
        ctxTagsItem.setOnAction(e -> handleEditTagsSelected());

        ctxRenameItem = new MenuItem(I18n.t("Yeniden Adlandır (F2)", "Rename (F2)"));
        ctxRenameItem.setOnAction(e -> handleRenameSelected());

        ctxCopyItem = new MenuItem(I18n.t("Kopyala (Ctrl+C)", "Copy (Ctrl+C)"));
        ctxCopyItem.setOnAction(e -> handleClipboard(ClipboardMode.COPY));

        ctxCutItem = new MenuItem(I18n.t("Kes / Taşı (Ctrl+X)", "Cut / Move (Ctrl+X)"));
        ctxCutItem.setOnAction(e -> handleClipboard(ClipboardMode.CUT));

        ctxPasteItem = new MenuItem(I18n.t("Yapıştır (Ctrl+V)", "Paste (Ctrl+V)"));
        ctxPasteItem.setOnAction(e -> handlePaste());

        ctxDeleteItem = new MenuItem(I18n.t("Sil (Del)", "Delete (Del)"));
        ctxDeleteItem.setOnAction(e -> handleDeleteSelected());

        ContextMenu menu = new ContextMenu(
                ctxPreviewItem, ctxExternalItem, ctxExportItem, new SeparatorMenuItem(),
                ctxFavoriteItem, ctxTagsItem, ctxRenameItem, ctxCopyItem, ctxCutItem, ctxPasteItem, new SeparatorMenuItem(), ctxDeleteItem);
        menu.setOnShowing(e -> {
            List<EntryRow> sel = currentSelectedRows();
            boolean hasSel = !sel.isEmpty();
            boolean singleFile = sel.size() == 1 && sel.get(0).entry().isFile();
            ctxPreviewItem.setDisable(sel.size() != 1);
            ctxExternalItem.setDisable(!singleFile);
            ctxExportItem.setDisable(!hasSel);
            ctxFavoriteItem.setDisable(!hasSel);
            ctxTagsItem.setDisable(sel.size() != 1);
            ctxRenameItem.setDisable(sel.size() != 1);
            ctxCopyItem.setDisable(!hasSel);
            ctxCutItem.setDisable(!hasSel);
            ctxPasteItem.setDisable(clipboardEntryIds.isEmpty());
            ctxDeleteItem.setDisable(!hasSel);
        });
        return menu;
    }

    private void buildInspectorPane() {
        inspectorPane.getStyleClass().add("inspector-pane");
        inspectorPane.setPadding(new Insets(12));
        inspectorPane.setMinWidth(250);
        inspectorPane.setPrefWidth(315);

        inspectorHeader = new Label(I18n.t("ÖNİZLEME VE AYRINTILAR", "PREVIEW & DETAILS"));
        inspectorHeader.getStyleClass().add("section-title");

        detailName.setText(I18n.t("Öğe seçilmedi", "No item selected"));
        detailName.getStyleClass().add("heading-md");
        detailName.setWrapText(true);

        detailMeta.setText(I18n.t("Önizleme veya ayrıntı için bir dosya/klasör seçin.", "Select a file or folder for preview or details."));
        detailMeta.getStyleClass().add("muted-text");
        detailMeta.setWrapText(true);
        detailMeta.setStyle("-fx-font-size: 12px;");

        inspectorFavBtn.setText(I18n.t("☆ Favori", "☆ Favorite"));
        inspectorFavBtn.getStyleClass().add("btn-ghost");
        inspectorFavBtn.setDisable(true);
        inspectorFavBtn.setOnAction(e -> handleToggleFavoriteSelected());
        MotionFx.attachPressSpring(inspectorFavBtn);

        inspectorTagsBtn.setText(I18n.t("Etiketler", "Tags"));
        inspectorTagsBtn.getStyleClass().add("btn-ghost");
        inspectorTagsBtn.setDisable(true);
        inspectorTagsBtn.setOnAction(e -> handleEditTagsSelected());
        MotionFx.attachPressSpring(inspectorTagsBtn);

        HBox inspectorQuickRow = new HBox(8, inspectorFavBtn, inspectorTagsBtn);
        inspectorQuickRow.setAlignment(Pos.CENTER_LEFT);

        inspectorTagsBox.setVisible(false);
        inspectorTagsBox.setManaged(false);

        previewContainer.getStyleClass().add("preview-surface");
        previewContainer.setPadding(new Insets(10));
        previewContainer.setMinHeight(190);
        VBox.setVgrow(previewContainer, Priority.ALWAYS);
        Label placeholder = new Label(I18n.t("Önizleme alanı", "Preview area"));
        placeholder.getStyleClass().add("muted-text");
        previewContainer.getChildren().setAll(placeholder);

        versionHistoryHeader.getStyleClass().add("section-title");
        versionClearAllBtn.setText(I18n.t("Temizle", "Clear"));
        versionClearAllBtn.getStyleClass().add("btn-ghost");
        versionClearAllBtn.setPadding(new Insets(2, 8, 2, 8));
        versionClearAllBtn.setStyle("-fx-font-size: 11px;");
        Region vhSpacer = new Region();
        HBox.setHgrow(vhSpacer, Priority.ALWAYS);
        HBox vhTop = new HBox(6, versionHistoryHeader, vhSpacer, versionClearAllBtn);
        vhTop.setAlignment(Pos.CENTER_LEFT);

        versionHistoryScroll.getStyleClass().add("transparent-scroll");
        versionHistoryScroll.setFitToWidth(true);
        versionHistoryScroll.setMaxHeight(150);

        versionHistorySection.getChildren().setAll(vhTop, versionHistoryScroll);
        versionHistorySection.setVisible(false);
        versionHistorySection.setManaged(false);

        openExternalBtn.setText(I18n.t("Harici Uygulamada Aç", "Open in External App"));
        openExternalBtn.setMaxWidth(Double.MAX_VALUE);
        openExternalBtn.setDisable(true);
        openExternalBtn.setOnAction(e -> handleOpenExternal());

        exportSelectedBtn.setText(I18n.t("Dışa Aktar", "Export"));
        exportSelectedBtn.setMaxWidth(Double.MAX_VALUE);
        exportSelectedBtn.setDisable(true);
        exportSelectedBtn.setOnAction(e -> handleExportSelected());

        extNote = new Label(I18n.t(
                "Harici uygulamada açılan dosyadaki değişiklikler otomatik olarak kasaya geri kaydedilir; kasa kilitlendiğinde geçici kopya temizlenir.",
                "Edits saved in an external app are synced back into the vault automatically; temporary copies are wiped when locked."));
        extNote.getStyleClass().add("muted-text");
        extNote.setWrapText(true);
        extNote.setStyle("-fx-font-size: 11px;");

        inspectorPane.getChildren().addAll(
                inspectorHeader,
                detailName,
                detailMeta,
                inspectorQuickRow,
                inspectorTagsBox,
                previewContainer,
                versionHistorySection,
                openExternalBtn,
                exportSelectedBtn,
                extNote);
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
        pillStatusLbl.setText(I18n.t("Hazır", "Ready"));
        pillStatusLbl.getStyleClass().add("primary-text");
        pillStatusLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");

        pillCancelBtn.setText(I18n.t("İptal", "Cancel"));
        pillCancelBtn.getStyleClass().add("btn-danger");
        pillCancelBtn.setPadding(new Insets(3, 10, 3, 10));
        pillCancelBtn.setOnAction(e -> {
            CancellationToken tok = currentTaskToken.get();
            if (tok != null) {
                tok.cancel();
                pillStatusLbl.setText(I18n.t("İptal ediliyor...", "Cancelling..."));
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
            disposeActiveMediaPlayer();
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
            refreshTagFilter(meta);
            refreshTableAndBreadcrumbs(meta);
            refreshStorageStats();
            refreshBadges();
        } catch (Exception e) {
            shell.showErrorOverlay(
                    I18n.t("Yenileme Hatası", "Refresh Error"),
                    I18n.t("Kasa görünümü güncellenemedi.", "Failed to refresh vault view."),
                    e);
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
        String allTypes = allTypesLabel();
        List<String> items = new ArrayList<>();
        items.add(allTypes);
        meta.collectDistinctExtensions().stream().sorted().forEach(ext -> items.add("." + ext));
        extFilterCombo.getItems().setAll(items);
        extFilterCombo.setValue(!isAllTypesFilter(current) && items.contains(current) ? current : allTypes);
    }

    private void refreshTagFilter(VaultMetadata meta) {
        String current = tagFilterCombo.getValue();
        String allTags = allTagsLabel();
        List<String> items = new ArrayList<>();
        items.add(allTags);
        items.addAll(meta.collectDistinctTags());
        tagFilterCombo.getItems().setAll(items);
        tagFilterCombo.setValue(!isAllTagsFilter(current) && items.contains(current) ? current : allTags);
    }

    private void refreshTableAndBreadcrumbs() {
        try {
            refreshTableAndBreadcrumbs(session.getMetadataSnapshot());
        } catch (Exception e) {
            shell.showErrorOverlay(
                    I18n.t("Hata", "Error"),
                    I18n.t("Klasör listesi okunamadı.", "Could not read folder contents."),
                    e);
        }
    }

    private void refreshTableOnly() {
        try {
            VaultMetadata meta = session.getMetadataSnapshot();
            populateItems(meta);
        } catch (Exception e) {
            shell.showErrorOverlay(
                    I18n.t("Arama Hatası", "Search Error"),
                    I18n.t("Arama sırasında hata oluştu.", "An error occurred while searching."),
                    e);
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
        Set<UUID> prevListSelection = new LinkedHashSet<>();
        if (viewMode == ViewMode.LIST) {
            for (EntryRow r : entryTable.getSelectionModel().getSelectedItems()) {
                if (r != null) prevListSelection.add(r.entry().id());
            }
        }

        String query = searchField.getText();
        String extVal = extFilterCombo.getValue();
        String ext = isAllTypesFilter(extVal) ? "" : extVal;
        String tagVal = tagFilterCombo.getValue();
        String tag = isAllTagsFilter(tagVal) ? "" : tagVal;
        boolean favOnly = favoritesFilterBtn.isSelected();
        boolean global = globalSearchCheck.isSelected();
        UUID scopeId = global ? VaultMetadata.ROOT_ID : currentFolderId;
        boolean recursive = global
                || (query != null && !query.isBlank())
                || !ext.isEmpty()
                || favOnly
                || !tag.isEmpty();

        List<VaultEntry> matches = meta.search(scopeId, recursive, query, ext, favOnly, tag);
        List<EntryRow> rows = new ArrayList<>(matches.size());
        Set<UUID> currentIds = new LinkedHashSet<>(matches.size());
        for (VaultEntry e : matches) {
            long size = e.isFolder() ? meta.computeFolderLogicalSize(e.id()) : e.logicalSize();
            rows.add(new EntryRow(e, size));
            currentIds.add(e.id());
        }
        sortEntryRows(rows);

        String filterSig = (query == null ? "" : query.trim()) + "|" + ext + "|" + tag + "|" + favOnly + "|" + global;
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
        if (viewMode == ViewMode.LIST && !prevListSelection.isEmpty()) {
            for (int i = 0; i < entryTable.getItems().size(); i++) {
                if (prevListSelection.contains(entryTable.getItems().get(i).entry().id())) {
                    entryTable.getSelectionModel().select(i);
                }
            }
        }
        gridSelectedIds.retainAll(rows.stream().map(r -> r.entry().id()).toList());
        if (viewMode == ViewMode.GRID) {
            rebuildGridCards(addedIds);
        }
        if (pendingSelectEntryId != null) {
            UUID targetSelect = pendingSelectEntryId;
            pendingSelectEntryId = null;
            if (viewMode == ViewMode.LIST) {
                for (int i = 0; i < entryTable.getItems().size(); i++) {
                    if (targetSelect.equals(entryTable.getItems().get(i).entry().id())) {
                        entryTable.getSelectionModel().clearAndSelect(i);
                        break;
                    }
                }
            } else {
                gridSelectedIds.clear();
                gridSelectedIds.add(targetSelect);
                lastGridAnchorId = targetSelect;
                updateGridSelectionStyles();
            }
        }
        updateInspector(primarySelectedRow());
        refreshStatusCounts();
    }

    private void sortEntryRows(List<EntryRow> rows) {
        Comparator<EntryRow> fieldComp = switch (sortField) {
            case NAME -> Comparator.comparing(r -> r.entry().name().toLowerCase(Locale.ROOT));
            case SIZE -> Comparator.comparingLong(EntryRow::computedSize);
            case MODIFIED -> Comparator.comparingLong(r -> r.entry().modifiedAt());
            case TYPE -> Comparator.comparing(r -> r.entry().isFolder() ? "" : r.entry().extensionLower());
        };
        if (!sortAscending) {
            fieldComp = fieldComp.reversed();
        }
        Comparator<EntryRow> full = Comparator
                .comparingInt((EntryRow r) -> r.entry().isFolder() ? 0 : 1)
                .thenComparing(fieldComp)
                .thenComparing(r -> r.entry().name().toLowerCase(Locale.ROOT));
        rows.sort(full);
    }

    private void refreshStatusCounts() {
        int total = currentDisplayedRows.size();
        int selCount = currentSelectedRows().size();
        statusCountLbl.setText(selCount > 0
                ? total + I18n.t(" öğe (", " items (") + selCount + I18n.t(" seçili)", " selected)")
                : total + I18n.t(" öğe", " items"));
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
            storageShortLegend.setText(I18n.t("Kullanılan: ", "Used: ") + FormatUtils.formatBytes(st.activeEncryptedBytes())
                    + I18n.t(" • Boşta: ", " • Unused: ") + FormatUtils.formatBytes(st.orphanBytes()));

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
        disposeActiveMediaPlayer();
        if (row == null) {
            lastPreviewedId = null;
            detailName.setText(I18n.t("Öğe seçilmedi", "No item selected"));
            detailMeta.setText(I18n.t("Önizleme veya ayrıntı için bir dosya/klasör seçin.", "Select a file or folder for preview or details."));
            inspectorFavBtn.setText(I18n.t("☆ Favori", "☆ Favorite"));
            inspectorFavBtn.getStyleClass().remove("favorite-star-active");
            inspectorFavBtn.setDisable(true);
            inspectorTagsBtn.setDisable(true);
            inspectorTagsBox.getChildren().clear();
            inspectorTagsBox.setVisible(false);
            inspectorTagsBox.setManaged(false);
            versionHistoryList.getChildren().clear();
            versionHistorySection.setVisible(false);
            versionHistorySection.setManaged(false);
            Label ph = new Label(I18n.t("Önizleme alanı", "Preview area"));
            ph.getStyleClass().add("muted-text");
            previewContainer.getChildren().setAll(ph);
            openExternalBtn.setDisable(true);
            exportSelectedBtn.setDisable(true);
            return;
        }
        VaultEntry e = row.entry();
        exportSelectedBtn.setDisable(false);
        openExternalBtn.setDisable(!e.isFile());
        inspectorFavBtn.setDisable(false);
        inspectorTagsBtn.setDisable(false);
        inspectorFavBtn.getStyleClass().remove("favorite-star-active");
        if (e.favorite()) {
            inspectorFavBtn.setText(I18n.t("★ Favori", "★ Favorite"));
            inspectorFavBtn.getStyleClass().add("favorite-star-active");
        } else {
            inspectorFavBtn.setText(I18n.t("☆ Favori", "☆ Favorite"));
        }

        inspectorTagsBox.getChildren().clear();
        for (String tag : e.tags()) {
            Label pill = new Label(tag);
            pill.getStyleClass().add("tag-pill");
            inspectorTagsBox.getChildren().add(pill);
        }
        boolean hasTags = !e.tags().isEmpty();
        inspectorTagsBox.setVisible(hasTags);
        inspectorTagsBox.setManaged(hasTags);

        updateVersionHistoryUi(e);

        String pathStr = "/";
        try {
            pathStr = session.getMetadataSnapshot().buildPath(e.id());
        } catch (Exception ignored) {
        }

        detailName.setText(e.name());
        detailMeta.setText(String.format(
                I18n.t("Yol: %s\nTür: %s | Boyut: %s\nOluşturma: %s\nDeğiştirilme: %s",
                        "Path: %s\nType: %s | Size: %s\nCreated: %s\nModified: %s"),
                pathStr,
                e.isFolder() ? I18n.t("Klasör", "Folder") : I18n.t("Dosya", "File"),
                FormatUtils.formatBytes(row.computedSize()),
                FormatUtils.formatTimestamp(e.createdAt()),
                FormatUtils.formatTimestamp(e.modifiedAt())));

        if (e.isFolder()) {
            lastPreviewedId = null;
            Label openHint = new Label(I18n.t("Klasör içeriğini açmak için çift tıklayın.", "Double-click to open folder."));
            openHint.getStyleClass().add("muted-text");
            openHint.setWrapText(true);
            VBox box = new VBox(8, SapphireIcons.folder(42, shell.accentIconColor()), openHint);
            box.setAlignment(Pos.CENTER);
            previewContainer.getChildren().setAll(box);
            MotionFx.playEntrance(box, 6.0, 0.0, 180.0);
            return;
        }

        String ext = e.extensionLower();
        if (MediaFormatSupport.isText(ext) && e.logicalSize() <= MAX_TEXT_PREVIEW_BYTES) {
            loadTextPreviewAsync(e);
        } else if (MediaFormatSupport.isStandardImage(ext) && e.logicalSize() <= MAX_IMAGE_PREVIEW_BYTES) {
            loadImagePreviewAsync(e);
        } else if (MediaFormatSupport.isWebViewImage(ext) && e.logicalSize() <= MAX_IMAGE_PREVIEW_BYTES) {
            loadWebViewImageAsync(e);
        } else if (MediaFormatSupport.isHeic(ext) && e.logicalSize() <= MAX_IMAGE_PREVIEW_BYTES) {
            loadHeicPreviewAsync(e);
        } else if (MediaFormatSupport.isPdf(ext) && e.logicalSize() <= MAX_PDF_PREVIEW_BYTES) {
            loadPdfPreviewAsync(e);
        } else if ((MediaFormatSupport.isAudio(ext) || MediaFormatSupport.isVideo(ext))
                && e.logicalSize() <= MAX_MEDIA_PREVIEW_BYTES) {
            loadMediaPreviewAsync(e, MediaFormatSupport.isVideo(ext));
        } else {
            lastPreviewedId = null;
            Label lbl = new Label(I18n.t(
                    "Bu dosya türü veya boyutu için dahili önizleme yok.\n'Harici Uygulamada Aç' veya 'Dışa Aktar' kullanabilirsiniz.",
                    "No built-in preview for this file type or size.\nUse 'Open in External App' or 'Export'."));
            lbl.setWrapText(true);
            lbl.getStyleClass().add("muted-text");
            previewContainer.getChildren().setAll(lbl);
            MotionFx.playEntrance(lbl, 6.0, 0.0, 180.0);
        }
    }

    private void updateVersionHistoryUi(VaultEntry entry) {
        versionHistoryList.getChildren().clear();
        if (!entry.isFile() || entry.versions().isEmpty()) {
            versionHistorySection.setVisible(false);
            versionHistorySection.setManaged(false);
            return;
        }
        versionHistoryHeader.setText(I18n.t("SÜRÜM GEÇMİŞİ (", "VERSION HISTORY (") + entry.versions().size() + ")");
        versionClearAllBtn.setOnAction(e -> handleClearVersions(entry));

        for (FileVersion ver : entry.versions()) {
            Label timeLbl = new Label(FormatUtils.formatTimestamp(ver.savedAt()));
            timeLbl.getStyleClass().add("primary-text");
            timeLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

            Label sizeLbl = new Label(FormatUtils.formatBytes(ver.logicalSize()));
            sizeLbl.getStyleClass().add("muted-text");
            sizeLbl.setStyle("-fx-font-size: 10.5px;");

            Button restoreBtn = new Button(I18n.t("Geri Yükle", "Restore"));
            restoreBtn.getStyleClass().add("btn-ghost");
            restoreBtn.setPadding(new Insets(2, 7, 2, 7));
            restoreBtn.setStyle("-fx-font-size: 10.5px;");
            restoreBtn.setOnAction(ev -> handleRestoreVersion(entry, ver));

            Button exportVerBtn = new Button(I18n.t("Dışa Aktar", "Export"));
            exportVerBtn.getStyleClass().add("btn-ghost");
            exportVerBtn.setPadding(new Insets(2, 7, 2, 7));
            exportVerBtn.setStyle("-fx-font-size: 10.5px;");
            exportVerBtn.setOnAction(ev -> handleExportVersion(entry, ver));

            Button deleteVerBtn = new Button(I18n.t("Sil", "Del"));
            deleteVerBtn.getStyleClass().add("btn-danger");
            deleteVerBtn.setPadding(new Insets(2, 7, 2, 7));
            deleteVerBtn.setStyle("-fx-font-size: 10.5px;");
            deleteVerBtn.setOnAction(ev -> handleDeleteVersion(entry.id(), ver.versionId()));

            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            HBox topRow = new HBox(6, timeLbl, sp, sizeLbl);
            topRow.setAlignment(Pos.CENTER_LEFT);
            HBox btnRow = new HBox(4, restoreBtn, exportVerBtn, deleteVerBtn);
            btnRow.setAlignment(Pos.CENTER_RIGHT);

            VBox card = new VBox(4, topRow, btnRow);
            card.getStyleClass().add("version-row-card");
            card.setPadding(new Insets(6, 8, 6, 8));
            versionHistoryList.getChildren().add(card);
        }
        versionHistorySection.setVisible(true);
        versionHistorySection.setManaged(true);
    }

    private void loadTextPreviewAsync(VaultEntry entry) {
        lastPreviewedId = entry.id();
        Label loading = new Label(I18n.t("Metin önizlemesi hazırlanıyor...", "Loading text preview..."));
        loading.getStyleClass().add("muted-text");
        previewContainer.getChildren().setAll(loading);
        previewExecutor.submit(() -> {
            try {
                if (!entry.id().equals(lastPreviewedId)) return;
                byte[] bytes = session.readFileBytes(entry.id(), MAX_TEXT_PREVIEW_BYTES, CancellationToken.NONE);
                String initialText = new String(bytes, StandardCharsets.UTF_8);
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    TextArea area = new TextArea(initialText);
                    area.setEditable(true);
                    area.setWrapText(false);
                    VBox.setVgrow(area, Priority.ALWAYS);

                    Label statusLbl = new Label(I18n.t("Kaydedildi", "Saved"));
                    statusLbl.getStyleClass().add("muted-text");
                    statusLbl.setStyle("-fx-font-size: 11px;");

                    Button saveBtn = new Button(I18n.t("Kaydet (Ctrl+S)", "Save (Ctrl+S)"));
                    saveBtn.getStyleClass().add("btn-primary");
                    saveBtn.setPadding(new Insets(3, 10, 3, 10));
                    saveBtn.setStyle("-fx-font-size: 11px;");
                    saveBtn.setDisable(true);

                    Button revertBtn = new Button(I18n.t("Geri Al", "Revert"));
                    revertBtn.getStyleClass().add("btn-ghost");
                    revertBtn.setPadding(new Insets(3, 8, 3, 8));
                    revertBtn.setStyle("-fx-font-size: 11px;");
                    revertBtn.setDisable(true);

                    area.textProperty().addListener((o, a, newVal) -> {
                        boolean dirty = !Objects.equals(initialText, newVal);
                        saveBtn.setDisable(!dirty);
                        revertBtn.setDisable(!dirty);
                        statusLbl.getStyleClass().removeAll("warning-badge", "muted-text");
                        if (dirty) {
                            statusLbl.setText(I18n.t("Değiştirildi", "Modified"));
                            statusLbl.getStyleClass().add("warning-badge");
                        } else {
                            statusLbl.setText(I18n.t("Kaydedildi", "Saved"));
                            statusLbl.getStyleClass().add("muted-text");
                        }
                    });

                    Runnable saveNote = () -> {
                        String textToSave = area.getText() == null ? "" : area.getText();
                        byte[] encoded = textToSave.getBytes(StandardCharsets.UTF_8);
                        saveBtn.setDisable(true);
                        pendingSelectEntryId = entry.id();
                        runBackgroundTask(I18n.t("Not kasaya kaydediliyor...", "Saving note to vault..."), false, (cancel, progress) -> {
                            session.writeFileBytes(entry.parentId(), entry.name(), encoded, true, cancel);
                            return entry.name() + I18n.t(" kaydedildi", " saved");
                        });
                    };

                    saveBtn.setOnAction(ev -> saveNote.run());
                    revertBtn.setOnAction(ev -> area.setText(initialText));
                    KeyCombination ctrlS = new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN);
                    area.setOnKeyPressed(ev -> {
                        if (ctrlS.match(ev) && !saveBtn.isDisable()) {
                            saveNote.run();
                            ev.consume();
                        }
                    });

                    Region sp = new Region();
                    HBox.setHgrow(sp, Priority.ALWAYS);
                    HBox editorBar = new HBox(6, statusLbl, sp, revertBtn, saveBtn);
                    editorBar.setAlignment(Pos.CENTER_LEFT);

                    VBox box = new VBox(8, editorBar, area);
                    VBox.setVgrow(area, Priority.ALWAYS);
                    previewContainer.getChildren().setAll(box);
                    MotionFx.playEntrance(box, 6.0, 0.0, 180.0);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    Label err = new Label(I18n.t("Önizleme hatası: ", "Preview error: ") + ex.getMessage());
                    err.getStyleClass().add("muted-text");
                    err.setWrapText(true);
                    previewContainer.getChildren().setAll(err);
                });
            }
        });
    }

    private void loadImagePreviewAsync(VaultEntry entry) {
        lastPreviewedId = entry.id();
        Label loading = new Label(I18n.t("Görsel önizlemesi hazırlanıyor...", "Loading image preview..."));
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
                        showUnsupportedPreviewMessage();
                        return;
                    }
                    renderInteractiveImagePreview(entry, img, null);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    Label err = new Label(I18n.t("Görsel yüklenemedi: ", "Could not load image: ") + ex.getMessage());
                    err.getStyleClass().add("muted-text");
                    err.setWrapText(true);
                    previewContainer.getChildren().setAll(err);
                });
            }
        });
    }

    private void loadHeicPreviewAsync(VaultEntry entry) {
        lastPreviewedId = entry.id();
        Label loading = new Label(I18n.t("HEIC önizlemesi hazırlanıyor...", "Loading HEIC preview..."));
        loading.getStyleClass().add("muted-text");
        previewContainer.getChildren().setAll(loading);
        previewExecutor.submit(() -> {
            try {
                if (!entry.id().equals(lastPreviewedId)) return;
                byte[] bytes = session.readFileBytes(entry.id(), MAX_IMAGE_PREVIEW_BYTES, CancellationToken.NONE);
                Image img = decodePreviewImage(bytes);
                String note = null;
                if (img == null) {
                    byte[] embeddedJpeg = MediaFormatSupport.extractEmbeddedJpegFromHeic(bytes);
                    if (embeddedJpeg != null) {
                        img = decodePreviewImage(embeddedJpeg);
                        if (img != null) {
                            note = I18n.t("HEIC gömülü önizleme", "HEIC embedded preview");
                        }
                    }
                }
                Image finalImg = img;
                String finalNote = note;
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    if (finalImg == null) {
                        showUnsupportedPreviewMessage();
                        return;
                    }
                    renderInteractiveImagePreview(entry, finalImg, finalNote);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    showUnsupportedPreviewMessage();
                });
            }
        });
    }

    private void loadWebViewImageAsync(VaultEntry entry) {
        lastPreviewedId = entry.id();
        Label loading = new Label(I18n.t("Görsel önizlemesi hazırlanıyor...", "Loading image preview..."));
        loading.getStyleClass().add("muted-text");
        previewContainer.getChildren().setAll(loading);
        previewExecutor.submit(() -> {
            try {
                if (!entry.id().equals(lastPreviewedId)) return;
                byte[] bytes = session.readFileBytes(entry.id(), MAX_IMAGE_PREVIEW_BYTES, CancellationToken.NONE);
                String html = MediaFormatSupport.buildWebViewImageHtml(bytes, entry.extensionLower(), themeManager.isDarkNow());
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    WebView webView = new WebView();
                    webView.setContextMenuEnabled(false);
                    webView.getEngine().loadContent(html, "text/html");
                    webView.prefWidthProperty().bind(Bindings.max(120.0, previewContainer.widthProperty().subtract(20.0)));
                    webView.prefHeightProperty().bind(Bindings.max(140.0, previewContainer.heightProperty().subtract(52.0)));

                    double[] zoom = {1.0};
                    Label zoomLbl = new Label("100%");
                    zoomLbl.getStyleClass().add("muted-text");
                    zoomLbl.setStyle("-fx-font-size: 11px;");
                    Button outBtn = new Button("−");
                    Button resetBtn = new Button("100%");
                    Button inBtn = new Button("+");
                    for (Button b : new Button[]{outBtn, resetBtn, inBtn}) {
                        b.getStyleClass().add("btn-ghost");
                        b.setPadding(new Insets(2, 8, 2, 8));
                    }
                    outBtn.setOnAction(ev -> {
                        zoom[0] = Math.max(0.25, zoom[0] / 1.2);
                        webView.setZoom(zoom[0]);
                        zoomLbl.setText(Math.round(zoom[0] * 100.0) + "%");
                    });
                    resetBtn.setOnAction(ev -> {
                        zoom[0] = 1.0;
                        webView.setZoom(1.0);
                        zoomLbl.setText("100%");
                    });
                    inBtn.setOnAction(ev -> {
                        zoom[0] = Math.min(4.0, zoom[0] * 1.2);
                        webView.setZoom(zoom[0]);
                        zoomLbl.setText(Math.round(zoom[0] * 100.0) + "%");
                    });

                    HBox bar = new HBox(4, outBtn, resetBtn, inBtn, zoomLbl);
                    bar.setAlignment(Pos.CENTER);
                    VBox box = new VBox(6, bar, webView);
                    VBox.setVgrow(webView, Priority.ALWAYS);
                    previewContainer.getChildren().setAll(box);
                    MotionFx.playEntrance(box, 6.0, 0.0, 180.0);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    showUnsupportedPreviewMessage();
                });
            }
        });
    }

    private void renderInteractiveImagePreview(VaultEntry entry, Image img, String optionalBadgeText) {
        double[] zoom = {1.0};
        double[] rotate = {0.0};

        ImageView view = new ImageView(img);
        view.setPreserveRatio(true);
        view.setSmooth(true);

        StackPane holder = new StackPane(view);
        holder.setAlignment(Pos.CENTER);

        ScrollPane scroll = new ScrollPane(holder);
        scroll.getStyleClass().add("transparent-scroll");
        scroll.setPannable(true);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Runnable applyTransform = () -> {
            view.setRotate(rotate[0]);
            if (Math.abs(zoom[0] - 1.0) < 0.01) {
                view.fitWidthProperty().bind(Bindings.max(100.0, scroll.widthProperty().subtract(16.0)));
                view.fitHeightProperty().bind(Bindings.max(100.0, scroll.heightProperty().subtract(16.0)));
            } else {
                view.fitWidthProperty().unbind();
                view.fitHeightProperty().unbind();
                double baseW = Math.max(100.0, scroll.getWidth() - 16.0);
                double baseH = Math.max(100.0, scroll.getHeight() - 16.0);
                double fitScale = Math.min(baseW / Math.max(1.0, img.getWidth()), baseH / Math.max(1.0, img.getHeight()));
                double targetScale = fitScale * zoom[0];
                view.setFitWidth(img.getWidth() * targetScale);
                view.setFitHeight(img.getHeight() * targetScale);
            }
        };

        Button zoomOutBtn = new Button("−");
        Button resetBtn = new Button("100%");
        Button zoomInBtn = new Button("+");
        Button rotateBtn = new Button("↻");
        Button lightboxBtn = new Button("⛶");
        for (Button b : new Button[]{zoomOutBtn, resetBtn, zoomInBtn, rotateBtn, lightboxBtn}) {
            b.getStyleClass().add("btn-ghost");
            b.setPadding(new Insets(2, 7, 2, 7));
            MotionFx.attachPressSpring(b);
        }
        lightboxBtn.setTooltip(new Tooltip(I18n.t("Tam Ekran", "Fullscreen")));

        zoomOutBtn.setOnAction(ev -> {
            zoom[0] = Math.max(0.25, zoom[0] / 1.25);
            resetBtn.setText(Math.round(zoom[0] * 100.0) + "%");
            applyTransform.run();
        });
        zoomInBtn.setOnAction(ev -> {
            zoom[0] = Math.min(4.0, zoom[0] * 1.25);
            resetBtn.setText(Math.round(zoom[0] * 100.0) + "%");
            applyTransform.run();
        });
        resetBtn.setOnAction(ev -> {
            zoom[0] = 1.0;
            rotate[0] = 0.0;
            resetBtn.setText("100%");
            applyTransform.run();
        });
        rotateBtn.setOnAction(ev -> {
            rotate[0] = (rotate[0] + 90.0) % 360.0;
            applyTransform.run();
        });
        Runnable openLightbox = () -> openLightboxFor(entry);
        lightboxBtn.setOnAction(ev -> openLightbox.run());
        view.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                openLightbox.run();
            }
        });
        scroll.addEventFilter(ScrollEvent.SCROLL, ev -> {
            if (ev.getDeltaY() > 0) {
                zoom[0] = Math.min(4.0, zoom[0] * 1.15);
            } else if (ev.getDeltaY() < 0) {
                zoom[0] = Math.max(0.25, zoom[0] / 1.15);
            }
            resetBtn.setText(Math.round(zoom[0] * 100.0) + "%");
            applyTransform.run();
            ev.consume();
        });

        HBox bar = new HBox(4, zoomOutBtn, resetBtn, zoomInBtn, rotateBtn, lightboxBtn);
        bar.setAlignment(Pos.CENTER);

        VBox box = new VBox(6);
        if (optionalBadgeText != null && !optionalBadgeText.isBlank()) {
            Label badge = new Label(optionalBadgeText);
            badge.getStyleClass().add("sapphire-badge");
            box.getChildren().add(badge);
        }
        box.getChildren().addAll(bar, scroll);
        applyTransform.run();
        previewContainer.getChildren().setAll(box);
        MotionFx.playEntrance(box, 6.0, 0.0, 180.0);
    }

    private void openLightboxFor(VaultEntry currentEntry) {
        List<VaultEntry> imageList = new ArrayList<>();
        int startIdx = 0;
        for (EntryRow r : currentDisplayedRows) {
            VaultEntry e = r.entry();
            if (e.isFile() && (MediaFormatSupport.isStandardImage(e.extensionLower()) || MediaFormatSupport.isHeic(e.extensionLower()))) {
                if (e.id().equals(currentEntry.id())) {
                    startIdx = imageList.size();
                }
                imageList.add(e);
            }
        }
        if (imageList.isEmpty()) {
            imageList.add(currentEntry);
            startIdx = 0;
        }
        ImageLightbox.show(shell, imageList, startIdx, this::loadImageForLightboxAsync);
    }

    private void loadImageForLightboxAsync(VaultEntry target, Consumer<Image> onLoaded) {
        previewExecutor.submit(() -> {
            Image img = null;
            try {
                byte[] bytes = session.readFileBytes(target.id(), MAX_IMAGE_PREVIEW_BYTES, CancellationToken.NONE);
                img = decodePreviewImage(bytes);
                if (img == null && MediaFormatSupport.isHeic(target.extensionLower())) {
                    byte[] embedded = MediaFormatSupport.extractEmbeddedJpegFromHeic(bytes);
                    if (embedded != null) {
                        img = decodePreviewImage(embedded);
                    }
                }
            } catch (Exception ignored) {
            }
            onLoaded.accept(img);
        });
    }

    private void loadPdfPreviewAsync(VaultEntry entry) {
        lastPreviewedId = entry.id();
        Label loading = new Label(I18n.t("PDF önizlemesi hazırlanıyor...", "Loading PDF preview..."));
        loading.getStyleClass().add("muted-text");
        previewContainer.getChildren().setAll(loading);
        previewExecutor.submit(() -> {
            try {
                if (!entry.id().equals(lastPreviewedId)) return;
                byte[] bytes = session.readFileBytes(entry.id(), MAX_PDF_PREVIEW_BYTES, CancellationToken.NONE);
                PdfPreviewParser.PdfSummary summary = PdfPreviewParser.parse(bytes);
                Image firstPageImg = (!summary.embeddedJpegs().isEmpty())
                        ? decodePreviewImage(summary.embeddedJpegs().get(0))
                        : null;
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    if (!summary.isValidPdf()) {
                        showUnsupportedPreviewMessage();
                        return;
                    }
                    StringBuilder info = new StringBuilder();
                    info.append("PDF v").append(summary.pdfVersion());
                    if (summary.pageCount() > 0) {
                        info.append(" • ").append(summary.pageCount()).append(I18n.t(" sayfa", " page(s)"));
                    }
                    Label badge = new Label(info.toString());
                    badge.getStyleClass().add("sapphire-badge");

                    VBox box = new VBox(8, badge);
                    if (!summary.title().isBlank() || !summary.author().isBlank()) {
                        String metaLine = (!summary.title().isBlank() ? summary.title() : "")
                                + (!summary.title().isBlank() && !summary.author().isBlank() ? " — " : "")
                                + (!summary.author().isBlank() ? summary.author() : "");
                        Label docMeta = new Label(metaLine);
                        docMeta.getStyleClass().add("muted-text");
                        docMeta.setWrapText(true);
                        docMeta.setStyle("-fx-font-size: 11px;");
                        box.getChildren().add(docMeta);
                    }
                    if (firstPageImg != null) {
                        ImageView iv = new ImageView(firstPageImg);
                        iv.setPreserveRatio(true);
                        iv.setSmooth(true);
                        iv.fitWidthProperty().bind(Bindings.max(100.0, previewContainer.widthProperty().subtract(28.0)));
                        iv.setFitHeight(140);
                        box.getChildren().add(iv);
                    }
                    if (!summary.extractedText().isBlank()) {
                        TextArea textView = new TextArea(summary.extractedText());
                        textView.setEditable(false);
                        textView.setWrapText(true);
                        VBox.setVgrow(textView, Priority.ALWAYS);
                        box.getChildren().add(textView);
                    } else if (firstPageImg == null) {
                        Label noText = new Label(I18n.t(
                                "Bu PDF görsel/taranmış içerik barındırıyor. Tam görünüm için 'Harici Uygulamada Aç' kullanın.",
                                "This PDF contains scanned/vector content. Use 'Open in External App' for full rendering."));
                        noText.getStyleClass().add("muted-text");
                        noText.setWrapText(true);
                        box.getChildren().add(noText);
                    }
                    previewContainer.getChildren().setAll(box);
                    MotionFx.playEntrance(box, 6.0, 0.0, 180.0);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    showUnsupportedPreviewMessage();
                });
            }
        });
    }

    private void loadMediaPreviewAsync(VaultEntry entry, boolean isVideo) {
        lastPreviewedId = entry.id();
        Label loading = new Label(I18n.t("Medya hazırlanıyor...", "Preparing media..."));
        loading.getStyleClass().add("muted-text");
        previewContainer.getChildren().setAll(loading);
        previewExecutor.submit(() -> {
            try {
                if (!entry.id().equals(lastPreviewedId)) return;
                Path tempFile = session.tempPreviewManager().createTempFile(entry.name());
                session.exportFile(entry.id(), tempFile, CancellationToken.NONE, ProgressListener.NO_OP);
                session.tempPreviewManager().hardenFile(tempFile);
                if (!entry.id().equals(lastPreviewedId)) return;

                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    disposeActiveMediaPlayer();
                    try {
                        Media media = new Media(tempFile.toUri().toString());
                        MediaPlayer player = new MediaPlayer(media);
                        activeMediaPlayer = player;
                        player.setVolume(0.85);

                        Button playPauseBtn = new Button("▶");
                        playPauseBtn.getStyleClass().add("btn-primary");
                        playPauseBtn.setPadding(new Insets(4, 12, 4, 12));

                        Slider seekSlider = new Slider(0, 1, 0);
                        HBox.setHgrow(seekSlider, Priority.ALWAYS);

                        Label timeLbl = new Label("00:00 / 00:00");
                        timeLbl.getStyleClass().add("muted-text");
                        timeLbl.setStyle("-fx-font-size: 11px;");

                        Slider volSlider = new Slider(0, 1, 0.85);
                        volSlider.setPrefWidth(68);

                        boolean[] playing = {false};
                        boolean[] userSeeking = {false};

                        player.setOnReady(() -> {
                            javafx.util.Duration total = media.getDuration();
                            double totalSec = total != null && !total.isUnknown() ? total.toSeconds() : 0.0;
                            seekSlider.setMax(Math.max(1.0, totalSec));
                            timeLbl.setText("00:00 / " + formatMediaSeconds(totalSec));
                        });
                        player.currentTimeProperty().addListener((o, a, cur) -> {
                            if (!userSeeking[0] && cur != null) {
                                seekSlider.setValue(cur.toSeconds());
                                double totalSec = seekSlider.getMax();
                                timeLbl.setText(formatMediaSeconds(cur.toSeconds()) + " / " + formatMediaSeconds(totalSec));
                            }
                        });
                        player.setOnEndOfMedia(() -> {
                            player.pause();
                            player.seek(javafx.util.Duration.ZERO);
                            playing[0] = false;
                            playPauseBtn.setText("▶");
                        });
                        player.setOnError(() -> showMediaCodecFallback(entry));
                        media.setOnError(() -> showMediaCodecFallback(entry));

                        playPauseBtn.setOnAction(ev -> {
                            if (playing[0]) {
                                player.pause();
                                playing[0] = false;
                                playPauseBtn.setText("▶");
                            } else {
                                player.play();
                                playing[0] = true;
                                playPauseBtn.setText("⏸");
                            }
                        });
                        seekSlider.setOnMousePressed(ev -> userSeeking[0] = true);
                        seekSlider.setOnMouseReleased(ev -> {
                            player.seek(javafx.util.Duration.seconds(seekSlider.getValue()));
                            userSeeking[0] = false;
                        });
                        volSlider.valueProperty().addListener((o, a, v) -> player.setVolume(v.doubleValue()));

                        HBox controls = new HBox(8, playPauseBtn, timeLbl, new Region(), volSlider);
                        HBox.setHgrow(controls.getChildren().get(2), Priority.ALWAYS);
                        controls.setAlignment(Pos.CENTER_LEFT);

                        VBox root = new VBox(8);
                        root.setAlignment(Pos.CENTER);
                        if (isVideo) {
                            MediaView mediaView = new MediaView(player);
                            mediaView.setPreserveRatio(true);
                            mediaView.fitWidthProperty().bind(Bindings.max(120.0, previewContainer.widthProperty().subtract(24.0)));
                            mediaView.fitHeightProperty().bind(Bindings.max(120.0, previewContainer.heightProperty().subtract(90.0)));
                            StackPane videoBox = new StackPane(mediaView);
                            videoBox.setStyle("-fx-background-color: #020617; -fx-background-radius: 8px;");
                            VBox.setVgrow(videoBox, Priority.ALWAYS);
                            root.getChildren().addAll(videoBox, seekSlider, controls);
                        } else {
                            Label badge = new Label("♪ " + entry.name());
                            badge.getStyleClass().add("sapphire-badge");
                            badge.setWrapText(true);
                            root.getChildren().addAll(badge, seekSlider, controls);
                        }
                        previewContainer.getChildren().setAll(root);
                        MotionFx.playEntrance(root, 6.0, 0.0, 180.0);
                    } catch (Throwable ex) {
                        showMediaCodecFallback(entry);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (!entry.id().equals(lastPreviewedId)) return;
                    showMediaCodecFallback(entry);
                });
            }
        });
    }

    private void showMediaCodecFallback(VaultEntry entry) {
        disposeActiveMediaPlayer();
        Label msg = new Label(I18n.t(
                "Bu medya biçimi dahili oynatıcıda çözülemedi.\n'Harici Uygulamada Aç' ile oynatabilirsiniz.",
                "This media format could not be decoded in built-in player.\nUse 'Open in External App' instead."));
        msg.getStyleClass().add("muted-text");
        msg.setWrapText(true);
        previewContainer.getChildren().setAll(msg);
    }

    private void showUnsupportedPreviewMessage() {
        Label unsupported = new Label(I18n.t(
                "Bu dosya biçimi dahili önizlemede desteklenmiyor.\n'Harici Uygulamada Aç' veya 'Dışa Aktar' kullanabilirsiniz.",
                "This format is not supported in built-in preview.\nUse 'Open in External App' or 'Export'."));
        unsupported.setWrapText(true);
        unsupported.getStyleClass().add("muted-text");
        previewContainer.getChildren().setAll(unsupported);
        MotionFx.playEntrance(unsupported, 6.0, 0.0, 180.0);
    }

    private void disposeActiveMediaPlayer() {
        MediaPlayer player = activeMediaPlayer;
        activeMediaPlayer = null;
        if (player != null) {
            try {
                player.stop();
                player.dispose();
            } catch (Exception ignored) {
            }
        }
    }

    private static String formatMediaSeconds(double totalSeconds) {
        if (Double.isNaN(totalSeconds) || totalSeconds < 0) {
            return "00:00";
        }
        long sec = Math.round(totalSeconds);
        long mins = sec / 60;
        long rem = sec % 60;
        return String.format(Locale.ROOT, "%02d:%02d", mins, rem);
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
            boolean supported = (MediaFormatSupport.isText(ext) && entry.logicalSize() <= MAX_TEXT_PREVIEW_BYTES)
                    || (MediaFormatSupport.isAnyImage(ext) && entry.logicalSize() <= MAX_IMAGE_PREVIEW_BYTES)
                    || (MediaFormatSupport.isPdf(ext) && entry.logicalSize() <= MAX_PDF_PREVIEW_BYTES)
                    || ((MediaFormatSupport.isAudio(ext) || MediaFormatSupport.isVideo(ext)) && entry.logicalSize() <= MAX_MEDIA_PREVIEW_BYTES);
            if (!supported) {
                handleOpenExternal();
            }
        }
    }

    private void handleCreateNote() {
        shell.showTextInputOverlay(
                I18n.t("Yeni Şifreli Not", "New Encrypted Note"),
                I18n.t("Bulunduğunuz klasörde doğrudan kasa içinde şifreli bir metin notu oluşturun.", "Create an encrypted text note inside the current folder."),
                I18n.t("not-adi.txt", "note-name.txt"),
                I18n.t("YeniNot.txt", "NewNote.txt"),
                I18n.t("Oluştur", "Create"),
                nameOpt -> nameOpt.ifPresent(rawName -> {
                    String resolved = rawName.contains(".") ? rawName : rawName + ".txt";
                    runBackgroundTask(I18n.t("Not oluşturuluyor...", "Creating note..."), false, (cancel, progress) -> {
                        VaultEntry created = session.writeFileBytes(currentFolderId, resolved, new byte[0], false, cancel);
                        pendingSelectEntryId = created.id();
                        Platform.runLater(() -> toggleInspector(true));
                        return I18n.t("Not oluşturuldu: ", "Note created: ") + created.name();
                    });
                }));
    }

    private void handleCreateFolder() {
        shell.showTextInputOverlay(
                I18n.t("Yeni Klasör", "New Folder"),
                I18n.t("Bulunduğunuz dizinde yeni klasör oluşturun.", "Create a new folder in the current directory."),
                I18n.t("Klasör adı", "Folder name"),
                "",
                I18n.t("Oluştur", "Create"),
                nameOpt -> nameOpt.ifPresent(name -> runBackgroundTask(I18n.t("Klasör oluşturuluyor...", "Creating folder..."), false, (cancel, progress) -> {
                    session.createFolder(currentFolderId, name);
                    return I18n.t("Klasör oluşturuldu: ", "Folder created: ") + name;
                })));
    }

    private void handleAddFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("Kasaya Eklenecek Dosyaları Seçin", "Select Files to Add to Vault"));
        List<File> files = chooser.showOpenMultipleDialog(window());
        if (files != null && !files.isEmpty()) {
            importPathsAsync(files.stream().map(File::toPath).toList(), currentFolderId);
        }
    }

    private void handleAddDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("Kasaya Eklenecek Klasörü Seçin", "Select Folder to Add to Vault"));
        File dir = chooser.showDialog(window());
        if (dir != null) {
            importPathsAsync(List.of(dir.toPath()), currentFolderId);
        }
    }

    private void importPathsAsync(List<Path> paths, UUID targetFolderId) {
        final boolean shredSources = settings.shredSourceAfterImport();
        runBackgroundTask(I18n.t("Dosyalar kasaya ekleniyor...", "Adding files to vault..."), true, (cancel, progress) -> {
            int importedCount = 0;
            int shreddedCount = 0;
            for (Path p : paths) {
                cancel.throwIfCancelled();
                if (Files.isDirectory(p)) {
                    session.importDirectory(p, targetFolderId, cancel, progress);
                    importedCount++;
                    if (shredSources && !cancel.isCancelled()) {
                        shreddedCount += SecureFileShredder.shredDirectory(p);
                    }
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
                        if (shredSources && !cancel.isCancelled() && SecureFileShredder.shredFile(p)) {
                            shreddedCount++;
                        }
                    }
                }
            }
            if (importedCount == 0) {
                return I18n.t("İşlem tamamlandı", "Done");
            }
            String base = importedCount + I18n.t(" öğe kasaya eklendi", " item(s) added to vault");
            if (shreddedCount > 0) {
                base += I18n.t(" • ", " • ") + shreddedCount + I18n.t(" kaynak dosya güvenli silindi", " source file(s) shredded");
            }
            return base;
        });
    }

    private void handleToggleFavoriteSelected() {
        List<EntryRow> selected = currentSelectedRows();
        if (selected.isEmpty()) {
            return;
        }
        boolean anyUnstarred = selected.stream().anyMatch(r -> !r.entry().favorite());
        List<UUID> targetIds = selected.stream().map(r -> r.entry().id()).toList();
        runBackgroundTask(I18n.t("Favori durumu güncelleniyor...", "Updating favorites..."), false, (cancel, progress) -> {
            for (UUID id : targetIds) {
                session.setFavorite(id, anyUnstarred);
            }
            return anyUnstarred
                    ? I18n.t("Favorilere eklendi", "Added to favorites")
                    : I18n.t("Favorilerden kaldırıldı", "Removed from favorites");
        });
    }

    private void handleEditTagsSelected() {
        EntryRow sel = primarySelectedRow();
        if (sel == null) {
            return;
        }
        VaultEntry entry = sel.entry();
        String existingText = String.join(", ", entry.tags());
        shell.showTextInputOverlay(
                I18n.t("Etiketleri Düzenle", "Edit Tags"),
                I18n.t(
                        "Virgülle ayırarak etiket girin (ör. iş, fatura, önemli). Tümünü temizlemek için '-' yazın.",
                        "Enter comma-separated tags (e.g. work, invoice, important). Enter '-' to clear all tags."),
                I18n.t("etiket1, etiket2", "tag1, tag2"),
                existingText,
                I18n.t("Kaydet", "Save"),
                opt -> opt.ifPresent(raw -> {
                    List<String> parsed = new ArrayList<>();
                    if (!"-".equals(raw.trim())) {
                        for (String part : raw.split(",")) {
                            String trimmed = part.trim();
                            if (!trimmed.isEmpty()) {
                                parsed.add(trimmed);
                            }
                        }
                    }
                    runBackgroundTask(I18n.t("Etiketler kaydediliyor...", "Saving tags..."), false, (cancel, progress) -> {
                        session.setTags(entry.id(), parsed);
                        return parsed.isEmpty()
                                ? I18n.t("Etiketler temizlendi", "Tags cleared")
                                : I18n.t("Etiketler güncellendi", "Tags updated");
                    });
                }));
    }

    private void handleRestoreVersion(VaultEntry entry, FileVersion version) {
        shell.showConfirmOverlay(
                I18n.t("Önceki Sürümü Geri Yükle?", "Restore Previous Version?"),
                I18n.t(
                        "Seçili sürüm etkin dosya haline getirilecek ve mevcut dosya içeriği de sürüm geçmişinde korunacaktır.",
                        "This version will become active and the current file will be kept in version history."),
                I18n.t("Geri Yükle", "Restore"),
                false,
                confirmed -> {
                    if (!confirmed) return;
                    runBackgroundTask(I18n.t("Sürüm geri yükleniyor...", "Restoring version..."), false, (cancel, progress) -> {
                        session.restoreFileVersion(entry.id(), version.versionId());
                        return entry.name() + I18n.t(" önceki sürüme döndürüldü", " restored to previous version");
                    });
                });
    }

    private void handleExportVersion(VaultEntry entry, FileVersion version) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("Eski Sürümü Dışa Aktar", "Export Previous Version"));
        chooser.setInitialFileName(entry.name());
        File dest = chooser.showSaveDialog(window());
        if (dest == null) return;
        runBackgroundTask(entry.name() + I18n.t(" sürümü dışa aktarılıyor...", " version exporting..."), true, (cancel, progress) -> {
            session.exportFileVersion(entry.id(), version.versionId(), dest.toPath(), cancel, progress);
            return entry.name() + I18n.t(" sürümü dışa aktarıldı", " version exported");
        });
    }

    private void handleDeleteVersion(UUID entryId, UUID versionId) {
        runBackgroundTask(I18n.t("Sürüm siliniyor...", "Deleting version..."), false, (cancel, progress) -> {
            session.deleteFileVersion(entryId, versionId);
            return I18n.t("Sürüm kaydı silindi", "Version entry deleted");
        });
    }

    private void handleClearVersions(VaultEntry entry) {
        shell.showConfirmOverlay(
                I18n.t("Sürüm Geçmişi Temizlensin mi?", "Clear Version History?"),
                I18n.t(
                        "Bu dosyanın kayıtlı tüm eski sürümleri kalıcı olarak kaldırılacaktır.",
                        "All archived previous versions for this file will be permanently removed."),
                I18n.t("Temizle", "Clear"),
                true,
                confirmed -> {
                    if (!confirmed) return;
                    runBackgroundTask(I18n.t("Sürüm geçmişi temizleniyor...", "Clearing version history..."), false, (cancel, progress) -> {
                        session.clearFileVersions(entry.id());
                        return I18n.t("Sürüm geçmişi temizlendi", "Version history cleared");
                    });
                });
    }

    private void handleExportSelected() {
        List<EntryRow> selected = currentSelectedRows();
        if (selected.isEmpty()) {
            shell.showToast(ToastType.INFO, I18n.t("Seçili Öğe Yok", "Nothing Selected"), I18n.t("Dışa aktarmak için en az bir öğe seçin.", "Select at least one item to export."));
            return;
        }
        if (selected.size() == 1 && selected.get(0).entry().isFile()) {
            VaultEntry file = selected.get(0).entry();
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("Dosyayı Dışa Aktar", "Export File"));
            chooser.setInitialFileName(file.name());
            File dest = chooser.showSaveDialog(window());
            if (dest == null) return;
            runBackgroundTask(file.name() + I18n.t(" dışa aktarılıyor...", " exporting..."), true, (cancel, progress) -> {
                session.exportFile(file.id(), dest.toPath(), cancel, progress);
                return file.name() + I18n.t(" dışa aktarıldı", " exported");
            });
        } else {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(I18n.t("Dışa Aktarılacak Hedef Klasörü Seçin", "Select Destination Folder"));
            File destDir = chooser.showDialog(window());
            if (destDir == null) return;
            Path targetDir = destDir.toPath();
            runBackgroundTask(I18n.t("Seçili öğeler dışa aktarılıyor...", "Exporting selected items..."), true, (cancel, progress) -> {
                for (EntryRow r : selected) {
                    cancel.throwIfCancelled();
                    VaultEntry e = r.entry();
                    if (e.isFolder()) {
                        session.exportFolder(e.id(), targetDir, cancel, progress);
                    } else {
                        session.exportFile(e.id(), targetDir.resolve(e.name()), cancel, progress);
                    }
                }
                return selected.size() + I18n.t(" öğe dışa aktarıldı", " item(s) exported");
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
            shell.showToast(ToastType.WARNING, I18n.t("Desteklenmiyor", "Unsupported"), I18n.t("Harici açma desteklenmiyor; 'Dışa Aktar' kullanın.", "External open not supported; use 'Export'."));
            return;
        }
        runBackgroundTask(entry.name() + I18n.t(" hazırlanıyor...", " preparing..."), true, (cancel, progress) -> {
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
                        shell.showToast(ToastType.SUCCESS, I18n.t("Değişiklik Kasaya Kaydedildi", "Change Saved to Vault"), updated.name());
                    });
                }
            });
            Desktop.getDesktop().open(tempFile.toFile());
            return entry.name() + I18n.t(" harici uygulamada açıldı", " opened externally");
        });
    }

    private void handleRenameSelected() {
        EntryRow sel = primarySelectedRow();
        if (sel == null) return;
        VaultEntry entry = sel.entry();
        shell.showTextInputOverlay(
                I18n.t("Yeniden Adlandır", "Rename"),
                I18n.t("Seçili öğe için yeni bir ad girin.", "Enter a new name for the selected item."),
                I18n.t("Yeni ad", "New name"),
                entry.name(),
                I18n.t("Kaydet", "Save"),
                nameOpt -> nameOpt.ifPresent(newName -> {
                    if (!newName.equals(entry.name())) {
                        runBackgroundTask(I18n.t("Yeniden adlandırılıyor...", "Renaming..."), false, (cancel, progress) -> {
                            session.renameEntry(entry.id(), newName);
                            return I18n.t("Öğe yeniden adlandırıldı: ", "Item renamed: ") + newName;
                        });
                    }
                }));
    }

    private void handleDeleteSelected() {
        List<EntryRow> selected = currentSelectedRows();
        if (selected.isEmpty()) return;
        List<UUID> ids = selected.stream().map(r -> r.entry().id()).toList();
        shell.showConfirmOverlay(
                selected.size() + I18n.t(" Öğe Silinsin mi?", " Item(s) Delete?"),
                I18n.t(
                        "Kasa içinde geri dönüşüm kutusu yoktur. Silinen öğelerin kapladığı boş alan bir sonraki sıkıştırmada temizlenir.",
                        "There is no recycle bin inside the vault. Freed space is reclaimed on the next compaction."),
                I18n.t("Sil", "Delete"),
                true,
                confirmed -> {
                    if (!confirmed) return;
                    Set<UUID> idSet = new HashSet<>(ids);
                    List<Node> nodesToAnimate = collectVisibleNodesForIds(idSet);
                    MotionFx.playExitRemove(nodesToAnimate, () ->
                            runBackgroundTask(I18n.t("Öğeler siliniyor...", "Deleting items..."), false, (cancel, progress) -> {
                                session.deleteEntries(ids);
                                return ids.size() + I18n.t(" öğe silindi", " item(s) deleted");
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
                mode == ClipboardMode.COPY ? I18n.t("Panoya Kopyalandı", "Copied") : I18n.t("Taşınmak Üzere Kesildi", "Cut for Move"),
                clipboardEntryIds.size() + I18n.t(" öğe seçildi", " item(s) selected"));
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

        runBackgroundTask(I18n.t("Öğeler kopyalanıyor...", "Copying items..."), false, (cancel, progress) -> {
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
            return copied + I18n.t(" öğe kopyalandı", " item(s) copied");
        });
    }

    private void moveEntriesAsync(List<UUID> ids, UUID targetFolderId) {
        runBackgroundTask(I18n.t("Öğeler taşınıyor...", "Moving items..."), false, (cancel, progress) -> {
            int moved = 0;
            for (UUID id : ids) {
                session.moveEntry(id, targetFolderId);
                moved++;
            }
            return moved + I18n.t(" öğe taşındı", " item(s) moved");
        });
    }

    private void handleCompact() {
        shell.showConfirmOverlay(
                I18n.t("Kasayı Sıkıştır", "Compact Vault"),
                I18n.t(
                        "Kullanılmayan boş alan temizlenerek kasa dosyası yeniden düzenlenecek ve boyutu küçültülecek.",
                        "Unused space will be cleaned up and the vault file will be compacted."),
                I18n.t("Sıkıştırmayı Başlat", "Start Compaction"),
                false,
                ok -> {
                    if (!ok) return;
                    runBackgroundTask(I18n.t("Kasa sıkıştırılıyor...", "Compacting vault..."), true, (cancel, progress) -> {
                        StorageStats after = session.compact(cancel, progress);
                        return I18n.t("Sıkıştırma tamamlandı • Yeni boyut: ", "Compaction completed • New size: ")
                                + FormatUtils.formatBytes(after.physicalFileSize());
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
                        shell.showToast(ToastType.SUCCESS, I18n.t("İşlem Tamamlandı", "Completed"), resultMsg);
                    });
                } catch (VaultCancelledException cancelled) {
                    Platform.runLater(() -> {
                        hideProgressPill();
                        refreshAll();
                        shell.showToast(ToastType.WARNING, I18n.t("İptal Edildi", "Cancelled"), I18n.t("Devam eden işlem kullanıcı tarafından durduruldu.", "Ongoing operation was stopped by user."));
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        hideProgressPill();
                        refreshAll();
                        shell.showErrorOverlay(I18n.t("İşlem Hatası", "Operation Error"), ex.getMessage(), ex);
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
        disposeActiveMediaPlayer();
        themeManager.removeListener(themeListener);
        CancellationToken tok = currentTaskToken.getAndSet(null);
        if (tok != null) {
            tok.cancel();
        }
        bgExecutor.shutdownNow();
        previewExecutor.shutdownNow();
        thumbnailExecutor.shutdownNow();
        thumbnailCache.clear();
    }
}
