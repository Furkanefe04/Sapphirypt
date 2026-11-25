package com.sapphircrypt.sapphirypt.ui;

import com.sapphircrypt.sapphirypt.format.VaultMetadata.VaultEntry;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fullscreen modal image lightbox supporting zoom, 90-degree rotation, mouse-wheel scaling,
 * and next/previous navigation across decrypted vault images.
 */
public final class ImageLightbox {

    private ImageLightbox() {
    }

    public static void show(
            UiShell shell,
            List<VaultEntry> entries,
            int initialIndex,
            BiConsumer<VaultEntry, Consumer<Image>> imageLoader) {
        Objects.requireNonNull(shell, "shell");
        Objects.requireNonNull(imageLoader, "imageLoader");
        if (entries == null || entries.isEmpty()) {
            return;
        }

        int safeStart = Math.max(0, Math.min(entries.size() - 1, initialIndex));
        int[] indexState = {safeStart};
        double[] zoomState = {1.0};
        double[] rotateState = {0.0};
        UUID[] activeLoadId = {null};
        Image[] currentImg = {null};

        Label titleLbl = new Label();
        titleLbl.getStyleClass().add("heading-md");
        Label counterLbl = new Label();
        counterLbl.getStyleClass().add("sapphire-badge");
        Label zoomLbl = new Label("100%");
        zoomLbl.getStyleClass().add("muted-text");
        zoomLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");

        Button closeBtn = new Button();
        closeBtn.getStyleClass().add("icon-btn");
        closeBtn.setGraphic(SapphireIcons.close(16, shell.iconColor()));
        closeBtn.setTooltip(new Tooltip(I18n.t("Kapat (Esc)", "Close (Esc)")));
        closeBtn.setOnAction(e -> shell.dismissOverlay());

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        HBox topBar = new HBox(10, titleLbl, counterLbl, topSpacer, zoomLbl, closeBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);

        ImageView imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(38, 38);
        Label errorLbl = new Label();
        errorLbl.getStyleClass().add("muted-text");
        errorLbl.setWrapText(true);
        errorLbl.setVisible(false);

        StackPane viewportCenter = new StackPane(imageView, spinner, errorLbl);
        viewportCenter.getStyleClass().add("preview-surface");
        viewportCenter.setAlignment(Pos.CENTER);

        ScrollPane imageScroll = new ScrollPane(viewportCenter);
        imageScroll.getStyleClass().add("transparent-scroll");
        imageScroll.setPannable(true);
        imageScroll.setFitToWidth(true);
        imageScroll.setFitToHeight(true);

        viewportCenter.minWidthProperty().bind(Bindings.max(200.0, imageScroll.widthProperty().subtract(6)));
        viewportCenter.minHeightProperty().bind(Bindings.max(200.0, imageScroll.heightProperty().subtract(6)));

        Runnable applyTransform = () -> {
            zoomLbl.setText(Math.round(zoomState[0] * 100.0) + "%");
            imageView.setRotate(rotateState[0]);
            Image img = currentImg[0];
            if (img == null) {
                return;
            }
            if (Math.abs(zoomState[0] - 1.0) < 0.01) {
                imageView.fitWidthProperty().bind(Bindings.max(160.0, imageScroll.widthProperty().subtract(36.0)));
                imageView.fitHeightProperty().bind(Bindings.max(160.0, imageScroll.heightProperty().subtract(36.0)));
            } else {
                imageView.fitWidthProperty().unbind();
                imageView.fitHeightProperty().unbind();
                double baseW = Math.max(180.0, imageScroll.getWidth() - 36.0);
                double baseH = Math.max(180.0, imageScroll.getHeight() - 36.0);
                double naturalScale = Math.min(baseW / Math.max(1.0, img.getWidth()), baseH / Math.max(1.0, img.getHeight()));
                double targetScale = naturalScale * zoomState[0];
                imageView.setFitWidth(img.getWidth() * targetScale);
                imageView.setFitHeight(img.getHeight() * targetScale);
            }
        };

        Button prevBtn = new Button("← " + I18n.t("Önceki", "Prev"));
        Button zoomOutBtn = new Button("−");
        Button resetBtn = new Button("100%");
        Button zoomInBtn = new Button("+");
        Button rotLeftBtn = new Button("↺");
        Button rotRightBtn = new Button("↻");
        Button nextBtn = new Button(I18n.t("Sonraki", "Next") + " →");

        for (Button b : new Button[]{prevBtn, zoomOutBtn, resetBtn, zoomInBtn, rotLeftBtn, rotRightBtn, nextBtn}) {
            b.getStyleClass().add("btn-ghost");
            MotionFx.attachPressSpring(b);
        }

        Runnable loadCurrent = () -> {
            VaultEntry target = entries.get(indexState[0]);
            activeLoadId[0] = target.id();
            currentImg[0] = null;
            zoomState[0] = 1.0;
            rotateState[0] = 0.0;
            titleLbl.setText(target.name());
            counterLbl.setText((indexState[0] + 1) + " / " + entries.size());
            prevBtn.setDisable(entries.size() <= 1);
            nextBtn.setDisable(entries.size() <= 1);

            imageView.setImage(null);
            errorLbl.setVisible(false);
            spinner.setVisible(true);
            applyTransform.run();

            UUID requestedId = target.id();
            imageLoader.accept(target, loaded -> Platform.runLater(() -> {
                if (!requestedId.equals(activeLoadId[0])) {
                    return;
                }
                spinner.setVisible(false);
                if (loaded == null || loaded.isError()) {
                    errorLbl.setText(I18n.t("Görsel önizlemesi çözülemedi.", "Could not decode image preview."));
                    errorLbl.setVisible(true);
                    return;
                }
                currentImg[0] = loaded;
                imageView.setImage(loaded);
                applyTransform.run();
                MotionFx.playEntrance(imageView, 0.0, 0.0, 160.0);
            }));
        };

        Runnable stepPrev = () -> {
            if (entries.size() <= 1) return;
            indexState[0] = (indexState[0] - 1 + entries.size()) % entries.size();
            loadCurrent.run();
        };
        Runnable stepNext = () -> {
            if (entries.size() <= 1) return;
            indexState[0] = (indexState[0] + 1) % entries.size();
            loadCurrent.run();
        };
        Runnable zoomIn = () -> {
            zoomState[0] = Math.min(4.0, zoomState[0] * 1.25);
            applyTransform.run();
        };
        Runnable zoomOut = () -> {
            zoomState[0] = Math.max(0.25, zoomState[0] / 1.25);
            applyTransform.run();
        };
        Runnable resetView = () -> {
            zoomState[0] = 1.0;
            rotateState[0] = 0.0;
            applyTransform.run();
        };

        prevBtn.setOnAction(e -> stepPrev.run());
        nextBtn.setOnAction(e -> stepNext.run());
        zoomInBtn.setOnAction(e -> zoomIn.run());
        zoomOutBtn.setOnAction(e -> zoomOut.run());
        resetBtn.setOnAction(e -> resetView.run());
        rotLeftBtn.setOnAction(e -> {
            rotateState[0] = (rotateState[0] - 90.0 + 360.0) % 360.0;
            applyTransform.run();
        });
        rotRightBtn.setOnAction(e -> {
            rotateState[0] = (rotateState[0] + 90.0) % 360.0;
            applyTransform.run();
        });

        imageScroll.addEventFilter(ScrollEvent.SCROLL, ev -> {
            if (ev.getDeltaY() > 0) {
                zoomIn.run();
            } else if (ev.getDeltaY() < 0) {
                zoomOut.run();
            }
            ev.consume();
        });

        HBox controlsBar = new HBox(8, prevBtn, zoomOutBtn, resetBtn, zoomInBtn, rotLeftBtn, rotRightBtn, nextBtn);
        controlsBar.setAlignment(Pos.CENTER);

        BorderPane card = new BorderPane();
        card.getStyleClass().add("overlay-card");
        card.setPadding(new Insets(16, 18, 16, 18));
        card.setPrefSize(1040, 680);
        card.setMaxSize(1240, 820);
        card.setTop(topBar);
        BorderPane.setMargin(topBar, new Insets(0, 0, 10, 0));
        card.setCenter(imageScroll);
        card.setBottom(controlsBar);
        BorderPane.setMargin(controlsBar, new Insets(10, 0, 0, 0));

        card.setFocusTraversable(true);
        card.setOnKeyPressed(ev -> {
            KeyCode code = ev.getCode();
            if (code == KeyCode.LEFT) {
                stepPrev.run();
                ev.consume();
            } else if (code == KeyCode.RIGHT) {
                stepNext.run();
                ev.consume();
            } else if (code == KeyCode.ADD || code == KeyCode.PLUS || code == KeyCode.EQUALS) {
                zoomIn.run();
                ev.consume();
            } else if (code == KeyCode.SUBTRACT || code == KeyCode.MINUS) {
                zoomOut.run();
                ev.consume();
            } else if (code == KeyCode.R) {
                rotateState[0] = (rotateState[0] + 90.0) % 360.0;
                applyTransform.run();
                ev.consume();
            } else if (code == KeyCode.DIGIT0 || code == KeyCode.NUMPAD0) {
                resetView.run();
                ev.consume();
            }
        });

        shell.showCustomOverlay(card, shell::dismissOverlay);
        Platform.runLater(card::requestFocus);
        loadCurrent.run();
    }
}
