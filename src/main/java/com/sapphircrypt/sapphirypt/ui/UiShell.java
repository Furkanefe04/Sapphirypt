package com.sapphircrypt.sapphirypt.ui;

import com.sapphircrypt.sapphirypt.storage.ThemeMode;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.ConflictAction;
import com.sapphircrypt.sapphirypt.ui.VaultDialogs.ConflictResolution;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Transform;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Root application shell providing custom window chrome, responsive edge/corner resizing,
 * right-hand animated drawer hosting, modal in-app overlays, and toast notifications.
 */
public final class UiShell extends StackPane {

    public enum ToastType {
        SUCCESS, INFO, WARNING, ERROR
    }

    private static final double RESIZE_BORDER = 6.0;
    private static final double DRAWER_WIDTH = 472.0;

    private final Stage stage;
    private final ThemeManager themeManager;

    private final StackPane clippedSurface = new StackPane();
    private final Rectangle windowClip = new Rectangle();
    private final BorderPane windowShell = new BorderPane();
    private final HBox titleBar = new HBox(10);
    private final Label titleLabel = new Label("Sapphirypt");
    private final HBox contextActionsBox = new HBox(8);
    private final Button themeCycleBtn = new Button();
    private final Button settingsBtn = new Button();
    private final Button minBtn = new Button();
    private final Button maxBtn = new Button();
    private final Button closeBtn = new Button();
    private final StackPane contentHost = new StackPane();

    // Drawer host
    private final StackPane drawerLayer = new StackPane();
    private final Region drawerBackdrop = new Region();
    private final VBox drawerPanel = new VBox();
    private BooleanSupplier activeDrawerCanClose = () -> true;
    private Runnable activeDrawerOnClose;
    private boolean drawerOpen;
    private ParallelTransition activeDrawerAnim;

    // Overlay host
    private final StackPane overlayLayer = new StackPane();
    private final Region overlayBackdrop = new Region();
    private final StackPane overlayContent = new StackPane();
    private Runnable activeOverlayEscapeAction;

    // Toast host
    private final VBox toastLayer = new VBox(10);

    // Soft theme cross-fade ghost
    private ImageView themeGhost;
    private FadeTransition themeGhostFade;

    // Custom window drag / resize / maximize state
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean draggingWindow;
    private boolean maximized;
    private Rectangle2D restoreBounds;
    private Cursor resizeCursor = Cursor.DEFAULT;
    private boolean resizingWindow;
    private double resizeStartScreenX;
    private double resizeStartScreenY;
    private double resizeStartX;
    private double resizeStartY;
    private double resizeStartW;
    private double resizeStartH;

    public UiShell(Stage stage, ThemeManager themeManager, Runnable onOpenSettings) {
        this.stage = stage;
        this.themeManager = themeManager;

        setStyle("-fx-background-color: transparent;");
        buildTitleBar(onOpenSettings);
        buildDrawerLayer();
        buildOverlayLayer();
        buildToastLayer();

        windowShell.getStyleClass().add("window-shell");
        windowShell.setTop(titleBar);
        windowShell.setCenter(contentHost);

        windowClip.widthProperty().bind(clippedSurface.widthProperty());
        windowClip.heightProperty().bind(clippedSurface.heightProperty());
        windowClip.setArcWidth(40.0);
        windowClip.setArcHeight(40.0);
        clippedSurface.setClip(windowClip);
        clippedSurface.getChildren().addAll(windowShell, drawerLayer, overlayLayer, toastLayer);

        getChildren().add(clippedSurface);

        configureWindowResizeHandlers();
        addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                if (overlayLayer.isVisible()) {
                    if (activeOverlayEscapeAction != null) {
                        activeOverlayEscapeAction.run();
                    }
                    ev.consume();
                } else if (drawerOpen) {
                    closeDrawer();
                    ev.consume();
                }
            }
        });

        themeManager.registerRoot(this);
        themeManager.setTransitionHandler(this::playThemeCrossFade);
        themeManager.addListener(this::refreshChromeIcons);
        refreshChromeIcons();
    }

    private void playThemeCrossFade(Runnable applyChange) {
        finishThemeGhost();
        double w = clippedSurface.getWidth();
        double h = clippedSurface.getHeight();
        if (getScene() == null || getScene().getWindow() == null || !getScene().getWindow().isShowing() || w < 2 || h < 2) {
            applyChange.run();
            return;
        }
        double sx = getScene().getWindow().getOutputScaleX();
        double sy = getScene().getWindow().getOutputScaleY();
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        params.setTransform(Transform.scale(sx, sy));
        WritableImage img = clippedSurface.snapshot(params, null);
        ImageView ghost = new ImageView(img);
        ghost.setFitWidth(w);
        ghost.setFitHeight(h);
        ghost.setMouseTransparent(true);
        ghost.setManaged(false);
        ghost.relocate(0, 0);
        clippedSurface.getChildren().add(ghost);
        themeGhost = ghost;
        applyChange.run();
        FadeTransition fade = new FadeTransition(Duration.millis(450), ghost);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setInterpolator(MotionFx.EASE_IN_OUT);
        fade.setOnFinished(e -> finishThemeGhost());
        themeGhostFade = fade;
        fade.play();
    }

    private void finishThemeGhost() {
        if (themeGhostFade != null) {
            themeGhostFade.setOnFinished(null);
            themeGhostFade.stop();
            themeGhostFade = null;
        }
        if (themeGhost != null) {
            clippedSurface.getChildren().remove(themeGhost);
            themeGhost.setImage(null);
            themeGhost = null;
        }
    }

    private void buildTitleBar(Runnable onOpenSettings) {
        titleBar.getStyleClass().add("title-bar");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPrefHeight(44);
        titleBar.setMinHeight(44);
        titleBar.setPadding(new Insets(0, 10, 0, 14));

        Node emblem = SapphireIcons.createSapphireVaultEmblem(20);
        titleLabel.getStyleClass().add("title-bar-title");
        titleLabel.setMinWidth(0);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        contextActionsBox.setAlignment(Pos.CENTER_RIGHT);
        contextActionsBox.setMinWidth(Region.USE_PREF_SIZE);

        themeCycleBtn.getStyleClass().add("title-bar-btn");
        themeCycleBtn.setFocusTraversable(false);
        themeCycleBtn.setMinWidth(Region.USE_PREF_SIZE);
        themeCycleBtn.setOnAction(e -> {
            themeManager.cycleMode();
            MotionFx.playIconSpin(themeCycleBtn.getGraphic());
            showToast(ToastType.INFO, "Tema Değiştirildi", "Aktif mod: " + themeManager.mode().label());
        });

        settingsBtn.getStyleClass().add("title-bar-btn");
        settingsBtn.setFocusTraversable(false);
        settingsBtn.setMinWidth(Region.USE_PREF_SIZE);
        settingsBtn.setTooltip(new Tooltip("Ayarlar"));
        settingsBtn.setOnAction(e -> {
            if (onOpenSettings != null) {
                onOpenSettings.run();
            }
        });
        MotionFx.attachPressSpring(themeCycleBtn);
        MotionFx.attachPressSpring(settingsBtn);

        Separator sep = new Separator(Orientation.VERTICAL);
        sep.setPrefHeight(20);
        sep.setMaxHeight(20);

        minBtn.getStyleClass().add("title-bar-btn");
        minBtn.setFocusTraversable(false);
        minBtn.setMinWidth(Region.USE_PREF_SIZE);
        minBtn.setTooltip(new Tooltip("Küçült"));
        minBtn.setOnAction(e -> stage.setIconified(true));

        maxBtn.getStyleClass().add("title-bar-btn");
        maxBtn.setFocusTraversable(false);
        maxBtn.setMinWidth(Region.USE_PREF_SIZE);
        maxBtn.setTooltip(new Tooltip("Büyüt / Geri Yükle"));
        maxBtn.setOnAction(e -> toggleMaximize());

        closeBtn.getStyleClass().addAll("title-bar-btn", "title-bar-close");
        closeBtn.setFocusTraversable(false);
        closeBtn.setMinWidth(Region.USE_PREF_SIZE);
        closeBtn.setTooltip(new Tooltip("Kapat"));
        closeBtn.setOnAction(e -> stage.fireEvent(
                new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));

        titleBar.getChildren().addAll(
                emblem, titleLabel, spacer, contextActionsBox, themeCycleBtn, settingsBtn, sep, minBtn, maxBtn, closeBtn);

        titleBar.setOnMousePressed(ev -> {
            if (ev.getButton() != javafx.scene.input.MouseButton.PRIMARY || isInsideInteractiveControl(ev.getTarget())) {
                draggingWindow = false;
                return;
            }
            if (ev.getClickCount() == 2) {
                toggleMaximize();
                draggingWindow = false;
                return;
            }
            draggingWindow = true;
            dragOffsetX = ev.getScreenX() - stage.getX();
            dragOffsetY = ev.getScreenY() - stage.getY();
        });

        titleBar.setOnMouseDragged(ev -> {
            if (!draggingWindow) {
                return;
            }
            if (maximized && restoreBounds != null) {
                double ratio = ev.getSceneX() / Math.max(1.0, getWidth());
                maximized = false;
                setWindowRoundedCorners(true);
                refreshChromeIcons();
                stage.setWidth(restoreBounds.getWidth());
                stage.setHeight(restoreBounds.getHeight());
                dragOffsetX = restoreBounds.getWidth() * Math.max(0.15, Math.min(0.85, ratio));
            }
            stage.setX(ev.getScreenX() - dragOffsetX);
            stage.setY(ev.getScreenY() - dragOffsetY);
        });

        titleBar.setOnMouseReleased(ev -> draggingWindow = false);
    }

    private void buildDrawerLayer() {
        drawerLayer.setVisible(false);
        drawerLayer.setManaged(false);

        drawerBackdrop.getStyleClass().add("drawer-backdrop");
        drawerBackdrop.setOnMouseClicked(e -> closeDrawer());

        drawerPanel.getStyleClass().add("drawer-panel");
        drawerPanel.setPrefWidth(DRAWER_WIDTH);
        drawerPanel.setMaxWidth(DRAWER_WIDTH);
        drawerPanel.setMinWidth(400);
        StackPane.setAlignment(drawerPanel, Pos.CENTER_RIGHT);

        drawerLayer.getChildren().addAll(drawerBackdrop, drawerPanel);
    }

    private void buildOverlayLayer() {
        overlayLayer.setVisible(false);
        overlayLayer.setManaged(false);

        overlayBackdrop.getStyleClass().add("overlay-backdrop");
        overlayContent.setPickOnBounds(false);
        overlayContent.setPadding(new Insets(24));
        StackPane.setAlignment(overlayContent, Pos.CENTER);

        overlayLayer.getChildren().addAll(overlayBackdrop, overlayContent);
    }

    private void buildToastLayer() {
        toastLayer.setAlignment(Pos.BOTTOM_RIGHT);
        toastLayer.setPickOnBounds(false);
        toastLayer.setPadding(new Insets(0, 18, 22, 18));
        toastLayer.setMaxWidth(380);
        toastLayer.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(toastLayer, Pos.BOTTOM_RIGHT);
    }

    public void setTitleText(String title) {
        titleLabel.setText(title == null || title.isBlank() ? "Sapphirypt" : title);
        stage.setTitle(titleLabel.getText());
    }

    public void setContent(Node view) {
        contentHost.getChildren().setAll(view);
    }

    public void setContentAnimated(Node view) {
        MotionFx.playCrossFadeSwap(contentHost, view);
    }

    public void setContextActions(Node... nodes) {
        if (nodes == null || nodes.length == 0) {
            contextActionsBox.getChildren().clear();
        } else {
            contextActionsBox.getChildren().setAll(nodes);
        }
    }

    public String iconColor() {
        return themeManager.isDarkNow() ? "#CBD5E1" : "#1E3A5F";
    }

    public String mutedIconColor() {
        return themeManager.isDarkNow() ? "#94A3B8" : "#475569";
    }

    public String accentIconColor() {
        return themeManager.isDarkNow() ? "#60A5FA" : "#2563EB";
    }

    public void refreshChromeIcons() {
        String iconCol = iconColor();
        ThemeMode mode = themeManager.mode();
        Node themeGraphic = switch (mode) {
            case DARK -> SapphireIcons.moon(15, "#93C5FD");
            case LIGHT -> SapphireIcons.sun(15, "#2563EB");
            case AUTO -> SapphireIcons.autoTheme(15, accentIconColor());
        };
        themeCycleBtn.setGraphic(themeGraphic);
        themeCycleBtn.setTooltip(new Tooltip("Tema: " + mode.label() + " (değiştirmek için tıklayın)"));

        settingsBtn.setGraphic(SapphireIcons.settings(15, iconCol));
        minBtn.setGraphic(SapphireIcons.minimize(14, iconCol));
        maxBtn.setGraphic(maximized ? SapphireIcons.restore(14, iconCol) : SapphireIcons.maximize(14, iconCol));
        closeBtn.setGraphic(SapphireIcons.close(14, iconCol));
    }

    public void toggleMaximize() {
        if (!maximized) {
            restoreBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            List<Screen> screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            Rectangle2D vb = (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
            stage.setX(vb.getMinX());
            stage.setY(vb.getMinY());
            stage.setWidth(vb.getWidth());
            stage.setHeight(vb.getHeight());
            maximized = true;
            setWindowRoundedCorners(false);
        } else {
            maximized = false;
            setWindowRoundedCorners(true);
            if (restoreBounds != null) {
                stage.setX(restoreBounds.getMinX());
                stage.setY(restoreBounds.getMinY());
                stage.setWidth(restoreBounds.getWidth());
                stage.setHeight(restoreBounds.getHeight());
            }
        }
        refreshChromeIcons();
    }

    private void setWindowRoundedCorners(boolean rounded) {
        double arc = rounded ? 40.0 : 0.0;
        windowClip.setArcWidth(arc);
        windowClip.setArcHeight(arc);
        if (rounded) {
            windowShell.getStyleClass().remove("window-shell-maximized");
        } else if (!windowShell.getStyleClass().contains("window-shell-maximized")) {
            windowShell.getStyleClass().add("window-shell-maximized");
        }
    }

    // ========================= DRAWER HOST =========================

    public void openDrawer(
            String title,
            String subtitle,
            Node bodyContent,
            Node footerContent,
            BooleanSupplier canCloseGuard,
            Runnable onCloseCallback) {
        if (activeDrawerAnim != null) {
            activeDrawerAnim.setOnFinished(null);
            activeDrawerAnim.stop();
            activeDrawerAnim = null;
        }
        activeDrawerCanClose = canCloseGuard != null ? canCloseGuard : () -> true;
        activeDrawerOnClose = onCloseCallback;

        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("heading-md");
        Label subtitleLbl = new Label(subtitle == null ? "" : subtitle);
        subtitleLbl.getStyleClass().add("muted-text");
        subtitleLbl.setWrapText(true);
        subtitleLbl.setStyle("-fx-font-size: 12px;");

        VBox headText = new VBox(3, titleLbl);
        if (subtitle != null && !subtitle.isBlank()) {
            headText.getChildren().add(subtitleLbl);
        }

        Button drawerCloseBtn = new Button();
        drawerCloseBtn.getStyleClass().add("icon-btn");
        drawerCloseBtn.setGraphic(SapphireIcons.close(15, iconColor()));
        drawerCloseBtn.setTooltip(new Tooltip("Kapat (Esc)"));
        drawerCloseBtn.setOnAction(e -> closeDrawer());

        Region hSpacer = new Region();
        HBox.setHgrow(hSpacer, Priority.ALWAYS);

        HBox header = new HBox(12, headText, hSpacer, drawerCloseBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("drawer-header");
        header.setPadding(new Insets(18, 20, 16, 22));

        ScrollPane bodyScroll = new ScrollPane(bodyContent);
        bodyScroll.getStyleClass().add("transparent-scroll");
        bodyScroll.setFitToWidth(true);
        bodyScroll.setPadding(new Insets(18, 22, 18, 22));
        VBox.setVgrow(bodyScroll, Priority.ALWAYS);

        drawerPanel.getChildren().clear();
        drawerPanel.getChildren().addAll(header, bodyScroll);

        if (footerContent != null) {
            StackPane footerWrap = new StackPane(footerContent);
            footerWrap.getStyleClass().add("drawer-footer");
            footerWrap.setPadding(new Insets(14, 22, 16, 22));
            drawerPanel.getChildren().add(footerWrap);
        }

        drawerOpen = true;
        drawerLayer.setManaged(true);
        drawerLayer.setVisible(true);

        drawerBackdrop.setOpacity(0.0);
        drawerPanel.setTranslateX(DRAWER_WIDTH + 24.0);

        FadeTransition fadeBg = new FadeTransition(Duration.millis(220), drawerBackdrop);
        fadeBg.setFromValue(0.0);
        fadeBg.setToValue(1.0);
        fadeBg.setInterpolator(MotionFx.EASE_OUT_EXPO);

        TranslateTransition slideIn = new TranslateTransition(Duration.millis(260), drawerPanel);
        slideIn.setFromX(DRAWER_WIDTH + 24.0);
        slideIn.setToX(0.0);
        slideIn.setInterpolator(MotionFx.EASE_OUT_EXPO);

        ParallelTransition inAnim = new ParallelTransition(fadeBg, slideIn);
        inAnim.setOnFinished(e -> {
            if (activeDrawerAnim == inAnim) {
                activeDrawerAnim = null;
            }
        });
        activeDrawerAnim = inAnim;
        inAnim.play();
        MotionFx.playEntrance(bodyContent, 8.0, 45.0, 220.0);
    }

    public void closeDrawer() {
        closeDrawer(false);
    }

    public void closeDrawer(boolean force) {
        if (!drawerOpen) {
            return;
        }
        if (!force && activeDrawerCanClose != null && !activeDrawerCanClose.getAsBoolean()) {
            return;
        }
        if (activeDrawerAnim != null) {
            activeDrawerAnim.setOnFinished(null);
            activeDrawerAnim.stop();
            activeDrawerAnim = null;
        }
        drawerOpen = false;
        Runnable onClose = activeDrawerOnClose;
        activeDrawerOnClose = null;
        activeDrawerCanClose = () -> true;

        FadeTransition fadeBg = new FadeTransition(Duration.millis(180), drawerBackdrop);
        fadeBg.setFromValue(drawerBackdrop.getOpacity());
        fadeBg.setToValue(0.0);
        fadeBg.setInterpolator(MotionFx.EASE_OUT_EXPO);

        TranslateTransition slideOut = new TranslateTransition(Duration.millis(190), drawerPanel);
        slideOut.setFromX(drawerPanel.getTranslateX());
        slideOut.setToX(DRAWER_WIDTH + 24.0);
        slideOut.setInterpolator(MotionFx.EASE_OUT_EXPO);

        ParallelTransition out = new ParallelTransition(fadeBg, slideOut);
        out.setOnFinished(e -> {
            if (activeDrawerAnim == out) {
                activeDrawerAnim = null;
            }
            if (drawerOpen) {
                return;
            }
            drawerLayer.setVisible(false);
            drawerLayer.setManaged(false);
            drawerPanel.getChildren().clear();
            if (onClose != null) {
                onClose.run();
            }
        });
        activeDrawerAnim = out;
        out.play();
    }

    // ========================= OVERLAY HOST =========================

    public void showConfirmOverlay(
            String title,
            String message,
            String confirmLabel,
            boolean danger,
            Consumer<Boolean> callback) {
        VBox card = createOverlayCard(440);
        HBox titleRow = new HBox(10,
                danger ? SapphireIcons.warning(20, "#F87171") : SapphireIcons.info(20, accentIconColor()),
                headingLabel(title));
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label msgLbl = bodyLabel(message);

        Button cancelBtn = new Button("Vazgeç");
        Button confirmBtn = new Button(confirmLabel != null ? confirmLabel : "Onayla");
        confirmBtn.getStyleClass().add(danger ? "btn-danger" : "btn-primary");

        HBox actions = new HBox(10, cancelBtn, confirmBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        Runnable dismissFalse = () -> {
            hideOverlay();
            if (callback != null) callback.accept(false);
        };
        cancelBtn.setOnAction(e -> dismissFalse.run());
        confirmBtn.setOnAction(e -> {
            hideOverlay();
            if (callback != null) callback.accept(true);
        });

        card.getChildren().addAll(titleRow, msgLbl, actions);
        showOverlayCard(card, dismissFalse);
        Platform.runLater(confirmBtn::requestFocus);
    }

    public void showErrorOverlay(String title, String userMessage, Throwable error) {
        VBox card = createOverlayCard(500);
        HBox titleRow = new HBox(10,
                SapphireIcons.warning(20, "#F87171"),
                headingLabel(title != null ? title : "İşlem Hatası"));
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label msgLbl = bodyLabel(userMessage == null || userMessage.isBlank()
                ? "Beklenmeyen bir hata oluştu." : userMessage);

        card.getChildren().addAll(titleRow, msgLbl);

        if (error != null) {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            TextArea traceArea = new TextArea(sw.toString());
            traceArea.setEditable(false);
            traceArea.setWrapText(false);
            traceArea.setPrefRowCount(7);
            traceArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 11px;");
            traceArea.setVisible(false);
            traceArea.setManaged(false);

            Button detailsBtn = new Button("Teknik Ayrıntıları Göster");
            detailsBtn.getStyleClass().add("btn-ghost");
            detailsBtn.setOnAction(e -> {
                boolean show = !traceArea.isVisible();
                traceArea.setVisible(show);
                traceArea.setManaged(show);
                detailsBtn.setText(show ? "Teknik Ayrıntıları Gizle" : "Teknik Ayrıntıları Göster");
            });

            Button copyBtn = new Button("Kopyala");
            copyBtn.getStyleClass().add("btn-ghost");
            copyBtn.setOnAction(e -> {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(sw.toString());
                Clipboard.getSystemClipboard().setContent(cc);
                showToast(ToastType.INFO, "Kopyalandı", "Hata ayrıntısı panoya kopyalandı.");
            });

            HBox toolsRow = new HBox(8, detailsBtn, copyBtn);
            card.getChildren().addAll(toolsRow, traceArea);
        }

        Button closeOverlayBtn = new Button("Tamam");
        closeOverlayBtn.getStyleClass().add("btn-primary");
        closeOverlayBtn.setOnAction(e -> hideOverlay());
        HBox actions = new HBox(closeOverlayBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().add(actions);
        showOverlayCard(card, this::hideOverlay);
        Platform.runLater(closeOverlayBtn::requestFocus);
    }

    public void showTextInputOverlay(
            String title,
            String message,
            String prompt,
            String initialValue,
            String confirmLabel,
            Consumer<Optional<String>> callback) {
        VBox card = createOverlayCard(440);
        Label titleLbl = headingLabel(title);
        Label msgLbl = bodyLabel(message);

        TextField input = new TextField(initialValue == null ? "" : initialValue);
        input.setPromptText(prompt == null ? "" : prompt);

        Label errLbl = new Label();
        errLbl.getStyleClass().add("inline-error-banner");
        errLbl.setPadding(new Insets(6, 10, 6, 10));
        errLbl.setMaxWidth(Double.MAX_VALUE);
        errLbl.setVisible(false);
        errLbl.setManaged(false);

        Button cancelBtn = new Button("İptal");
        Button okBtn = new Button(confirmLabel != null ? confirmLabel : "Kaydet");
        okBtn.getStyleClass().add("btn-primary");

        Runnable cancelAction = () -> {
            hideOverlay();
            if (callback != null) callback.accept(Optional.empty());
        };
        Runnable submitAction = () -> {
            String trimmed = input.getText() == null ? "" : input.getText().trim();
            if (trimmed.isEmpty()) {
                errLbl.setText("Bu alan boş bırakılamaz.");
                errLbl.setVisible(true);
                errLbl.setManaged(true);
                MotionFx.playErrorShake(errLbl);
                return;
            }
            hideOverlay();
            if (callback != null) callback.accept(Optional.of(trimmed));
        };

        cancelBtn.setOnAction(e -> cancelAction.run());
        okBtn.setOnAction(e -> submitAction.run());
        input.setOnAction(e -> submitAction.run());

        HBox actions = new HBox(10, cancelBtn, okBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(titleLbl, msgLbl, input, errLbl, actions);
        showOverlayCard(card, cancelAction);
        Platform.runLater(() -> {
            input.requestFocus();
            input.selectAll();
        });
    }

    public CompletableFuture<ConflictResolution> showConflictOverlay(
            String conflictingName, boolean existingIsFolder, boolean allowOverwrite) {
        CompletableFuture<ConflictResolution> future = new CompletableFuture<>();
        VBox card = createOverlayCard(470);

        HBox titleRow = new HBox(10,
                SapphireIcons.warning(20, "#FBBF24"),
                headingLabel("İsim Çakışması"));
        titleRow.setAlignment(Pos.CENTER_LEFT);

        String noteText;
        if (existingIsFolder) {
            noteText = "Hedef klasörde \"" + conflictingName + "\" adlı bir klasör zaten var. Üzerine yazılamaz; yeni ad verebilir veya iptal edebilirsiniz.";
        } else if (!allowOverwrite) {
            noteText = "Hedef klasörde \"" + conflictingName + "\" adlı bir öğe zaten var. Kopyalama için yeni ad verebilir veya iptal edebilirsiniz.";
        } else {
            noteText = "Hedef klasörde \"" + conflictingName + "\" zaten var. Üzerine yazarsanız mevcut dosya yeni dosya ile güncellenir.";
        }
        Label msgLbl = bodyLabel(noteText);

        TextField newNameField = new TextField(conflictingName);
        newNameField.setPromptText("Yeni ad girin");

        Label errLbl = new Label();
        errLbl.getStyleClass().add("inline-error-banner");
        errLbl.setPadding(new Insets(6, 10, 6, 10));
        errLbl.setMaxWidth(Double.MAX_VALUE);
        errLbl.setVisible(false);
        errLbl.setManaged(false);

        Button cancelBtn = new Button("İptal");
        Button renameBtn = new Button("Yeniden Adlandır");
        renameBtn.getStyleClass().add("btn-primary");

        HBox actions = new HBox(8, cancelBtn, renameBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        Runnable completeCancel = () -> {
            hideOverlay();
            future.complete(new ConflictResolution(ConflictAction.CANCEL, null));
        };
        cancelBtn.setOnAction(e -> completeCancel.run());

        Runnable submitRename = () -> {
            String candidate = newNameField.getText() == null ? "" : newNameField.getText().trim();
            if (candidate.isEmpty() || candidate.equalsIgnoreCase(conflictingName)
                    || candidate.indexOf('/') >= 0 || candidate.indexOf('\\') >= 0) {
                errLbl.setText("Lütfen mevcut isimden farklı ve geçerli bir ad girin.");
                errLbl.setVisible(true);
                errLbl.setManaged(true);
                MotionFx.playErrorShake(errLbl);
                return;
            }
            hideOverlay();
            future.complete(new ConflictResolution(ConflictAction.RENAME, candidate));
        };
        renameBtn.setOnAction(e -> submitRename.run());
        newNameField.setOnAction(e -> submitRename.run());

        if (allowOverwrite && !existingIsFolder) {
            Button overwriteBtn = new Button("Üzerine Yaz");
            overwriteBtn.getStyleClass().add("btn-danger");
            overwriteBtn.setOnAction(e -> {
                hideOverlay();
                future.complete(new ConflictResolution(ConflictAction.OVERWRITE, conflictingName));
            });
            actions.getChildren().add(1, overwriteBtn);
        }

        card.getChildren().addAll(titleRow, msgLbl, bodyLabel("Yeniden adlandırma için yeni ad:"), newNameField, errLbl, actions);
        showOverlayCard(card, completeCancel);
        Platform.runLater(() -> {
            newNameField.requestFocus();
            newNameField.selectAll();
        });
        return future;
    }

    private VBox createOverlayCard(double maxWidth) {
        VBox card = new VBox(14);
        card.getStyleClass().add("overlay-card");
        card.setPadding(new Insets(22, 24, 20, 24));
        card.setMaxWidth(maxWidth);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        return card;
    }

    private Label headingLabel(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("heading-md");
        lbl.setWrapText(true);
        return lbl;
    }

    private Label bodyLabel(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("muted-text");
        lbl.setWrapText(true);
        lbl.setStyle("-fx-font-size: 13px;");
        return lbl;
    }

    private void showOverlayCard(Node card, Runnable onEscape) {
        activeOverlayEscapeAction = onEscape;
        card.setScaleX(0.93);
        card.setScaleY(0.93);
        overlayContent.getChildren().setAll(card);
        overlayLayer.setManaged(true);
        overlayLayer.setVisible(true);
        overlayLayer.setOpacity(0.0);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(210), overlayLayer);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.setInterpolator(MotionFx.EASE_OUT_EXPO);

        ScaleTransition popIn = new ScaleTransition(Duration.millis(220), card);
        popIn.setFromX(0.93);
        popIn.setFromY(0.93);
        popIn.setToX(1.0);
        popIn.setToY(1.0);
        popIn.setInterpolator(MotionFx.EASE_OUT_BACK);

        new ParallelTransition(fadeIn, popIn).play();
    }

    private void hideOverlay() {
        activeOverlayEscapeAction = null;
        FadeTransition fadeOut = new FadeTransition(Duration.millis(140), overlayLayer);
        fadeOut.setFromValue(overlayLayer.getOpacity());
        fadeOut.setToValue(0.0);
        fadeOut.setInterpolator(MotionFx.EASE_OUT_EXPO);

        ParallelTransition out = new ParallelTransition(fadeOut);
        if (!overlayContent.getChildren().isEmpty()) {
            Node card = overlayContent.getChildren().get(0);
            ScaleTransition shrink = new ScaleTransition(Duration.millis(140), card);
            shrink.setToX(0.96);
            shrink.setToY(0.96);
            shrink.setInterpolator(MotionFx.EASE_OUT_EXPO);
            out.getChildren().add(shrink);
        }
        out.setOnFinished(e -> {
            overlayLayer.setVisible(false);
            overlayLayer.setManaged(false);
            overlayContent.getChildren().clear();
        });
        out.play();
    }

    // ========================= TOAST HOST =========================

    public void showToast(ToastType type, String title, String detail) {
        Runnable task = () -> {
            ToastType effective = type == null ? ToastType.INFO : type;
            String accentHex = switch (effective) {
                case SUCCESS -> "#22C55E";
                case INFO -> accentIconColor();
                case WARNING -> "#F59E0B";
                case ERROR -> "#EF4444";
            };
            String styleClass = switch (effective) {
                case SUCCESS -> "toast-success";
                case INFO -> "toast-info";
                case WARNING -> "toast-warning";
                case ERROR -> "toast-error";
            };
            Node icon = switch (effective) {
                case SUCCESS -> SapphireIcons.check(17, accentHex);
                case INFO -> SapphireIcons.info(17, accentHex);
                case WARNING, ERROR -> SapphireIcons.warning(17, accentHex);
            };

            Label titleLbl = new Label(title == null ? "" : title);
            titleLbl.getStyleClass().add("primary-text");
            titleLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 12.5px;");

            VBox textCol = new VBox(2, titleLbl);
            if (detail != null && !detail.isBlank()) {
                Label detailLbl = new Label(detail);
                detailLbl.getStyleClass().add("muted-text");
                detailLbl.setWrapText(true);
                detailLbl.setMaxWidth(270);
                detailLbl.setStyle("-fx-font-size: 11.5px;");
                textCol.getChildren().add(detailLbl);
            }

            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);

            Button closeToastBtn = new Button();
            closeToastBtn.getStyleClass().add("icon-btn");
            closeToastBtn.setGraphic(SapphireIcons.close(12, mutedIconColor()));
            closeToastBtn.setPadding(new Insets(2));

            HBox card = new HBox(10, icon, textCol, sp, closeToastBtn);
            card.setAlignment(Pos.TOP_LEFT);
            card.getStyleClass().addAll("toast-card", styleClass);
            card.setPadding(new Insets(11, 12, 11, 14));
            card.setMaxWidth(360);

            PauseTransition hold = new PauseTransition(Duration.seconds(3.8));
            Runnable removeToast = () -> {
                hold.stop();
                FadeTransition fadeOut = new FadeTransition(Duration.millis(180), card);
                fadeOut.setFromValue(card.getOpacity());
                fadeOut.setToValue(0.0);
                fadeOut.setInterpolator(MotionFx.EASE_OUT_EXPO);

                TranslateTransition slideRight = new TranslateTransition(Duration.millis(180), card);
                slideRight.setToX(24.0);
                slideRight.setInterpolator(MotionFx.EASE_OUT_EXPO);

                ParallelTransition out = new ParallelTransition(fadeOut, slideRight);
                out.setOnFinished(ev -> toastLayer.getChildren().remove(card));
                out.play();
            };
            closeToastBtn.setOnAction(e -> removeToast.run());
            hold.setOnFinished(e -> removeToast.run());

            while (toastLayer.getChildren().size() >= 4) {
                toastLayer.getChildren().remove(0);
            }
            card.setOpacity(0.0);
            card.setTranslateY(12.0);
            toastLayer.getChildren().add(card);

            FadeTransition fadeIn = new FadeTransition(Duration.millis(210), card);
            fadeIn.setFromValue(0.0);
            fadeIn.setToValue(1.0);
            fadeIn.setInterpolator(MotionFx.EASE_OUT_EXPO);

            TranslateTransition slideUp = new TranslateTransition(Duration.millis(210), card);
            slideUp.setFromY(12.0);
            slideUp.setToY(0.0);
            slideUp.setInterpolator(MotionFx.EASE_OUT_EXPO);
            new ParallelTransition(fadeIn, slideUp).play();

            hold.play();
        };

        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    // ========================= WINDOW RESIZING =========================

    private void configureWindowResizeHandlers() {
        addEventFilter(MouseEvent.MOUSE_MOVED, ev -> {
            if (maximized || overlayLayer.isVisible() || drawerOpen) {
                if (resizeCursor != Cursor.DEFAULT) {
                    resizeCursor = Cursor.DEFAULT;
                    setCursor(Cursor.DEFAULT);
                }
                return;
            }
            Cursor next = detectResizeCursor(ev.getX(), ev.getY(), getWidth(), getHeight());
            if (next != resizeCursor) {
                resizeCursor = next;
                setCursor(next);
            }
        });

        addEventFilter(MouseEvent.MOUSE_PRESSED, ev -> {
            if (resizeCursor != Cursor.DEFAULT && ev.getButton() == javafx.scene.input.MouseButton.PRIMARY && !maximized) {
                resizingWindow = true;
                resizeStartScreenX = ev.getScreenX();
                resizeStartScreenY = ev.getScreenY();
                resizeStartX = stage.getX();
                resizeStartY = stage.getY();
                resizeStartW = stage.getWidth();
                resizeStartH = stage.getHeight();
                ev.consume();
            }
        });

        addEventFilter(MouseEvent.MOUSE_DRAGGED, ev -> {
            if (!resizingWindow) {
                return;
            }
            double dx = ev.getScreenX() - resizeStartScreenX;
            double dy = ev.getScreenY() - resizeStartScreenY;
            double minW = Math.max(600, stage.getMinWidth());
            double minH = Math.max(480, stage.getMinHeight());

            if (resizeCursor == Cursor.E_RESIZE || resizeCursor == Cursor.NE_RESIZE || resizeCursor == Cursor.SE_RESIZE) {
                stage.setWidth(Math.max(minW, resizeStartW + dx));
            }
            if (resizeCursor == Cursor.S_RESIZE || resizeCursor == Cursor.SW_RESIZE || resizeCursor == Cursor.SE_RESIZE) {
                stage.setHeight(Math.max(minH, resizeStartH + dy));
            }
            if (resizeCursor == Cursor.W_RESIZE || resizeCursor == Cursor.NW_RESIZE || resizeCursor == Cursor.SW_RESIZE) {
                double nextW = resizeStartW - dx;
                if (nextW >= minW) {
                    stage.setWidth(nextW);
                    stage.setX(resizeStartX + dx);
                }
            }
            if (resizeCursor == Cursor.N_RESIZE || resizeCursor == Cursor.NW_RESIZE || resizeCursor == Cursor.NE_RESIZE) {
                double nextH = resizeStartH - dy;
                if (nextH >= minH) {
                    stage.setHeight(nextH);
                    stage.setY(resizeStartY + dy);
                }
            }
            ev.consume();
        });

        addEventFilter(MouseEvent.MOUSE_RELEASED, ev -> {
            if (resizingWindow) {
                resizingWindow = false;
                ev.consume();
            }
        });
    }

    private static Cursor detectResizeCursor(double x, double y, double w, double h) {
        boolean left = x >= 0 && x <= RESIZE_BORDER;
        boolean right = x >= w - RESIZE_BORDER && x <= w;
        boolean top = y >= 0 && y <= RESIZE_BORDER;
        boolean bottom = y >= h - RESIZE_BORDER && y <= h;

        if (top && left) return Cursor.NW_RESIZE;
        if (top && right) return Cursor.NE_RESIZE;
        if (bottom && left) return Cursor.SW_RESIZE;
        if (bottom && right) return Cursor.SE_RESIZE;
        if (left) return Cursor.W_RESIZE;
        if (right) return Cursor.E_RESIZE;
        if (top) return Cursor.N_RESIZE;
        if (bottom) return Cursor.S_RESIZE;
        return Cursor.DEFAULT;
    }

    private static boolean isInsideInteractiveControl(Object target) {
        if (!(target instanceof Node node)) {
            return false;
        }
        Parent current = node instanceof Parent p ? p : node.getParent();
        while (current != null) {
            if (current instanceof javafx.scene.control.ButtonBase
                    || current instanceof javafx.scene.control.TextInputControl
                    || current instanceof javafx.scene.control.ComboBoxBase<?>) {
                return true;
            }
            if (current.getStyleClass().contains("title-bar")) {
                return false;
            }
            current = current.getParent();
        }
        return false;
    }
}
