package com.sapphircrypt.sapphirypt.ui;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * Minimal theme-aware splash screen showcasing the Concept 7 logo with breathing sapphire glow,
 * staggered entrance transitions, and sleek progress animation.
 */
public final class SplashScreen {
    private static final String CSS_PATH = "/com/sapphircrypt/sapphirypt/sapphirypt.css";
    private static final double LOGO_DISPLAY_SIZE = 148.0;

    private final Stage stage;
    private final VBox card;
    private final Node logoNode;
    private final Label titleLabel;
    private final ProgressBar progressBar;
    private final Timeline breathingGlowTimeline;

    private Timeline progressTimeline;
    private boolean closed;

    public SplashScreen() {
        this(true);
    }

    public SplashScreen(boolean darkTheme) {
        this.stage = new Stage(StageStyle.TRANSPARENT);
        this.stage.setTitle("Sapphirypt");
        var appIcons = SapphireIcons.getApplicationIcons();
        if (!appIcons.isEmpty()) {
            this.stage.getIcons().setAll(appIcons);
        }

        this.logoNode = buildLogoNode();
        this.breathingGlowTimeline = MotionFx.createSapphireBreathingGlow(this.logoNode, darkTheme);

        this.titleLabel = new Label("Sapphirypt");
        this.titleLabel.getStyleClass().add("splash-title");

        this.progressBar = new ProgressBar(0.08);
        this.progressBar.getStyleClass().add("splash-progress");
        this.progressBar.setPrefWidth(240);
        this.progressBar.setPrefHeight(4);
        this.progressBar.setMinHeight(4);
        this.progressBar.setMaxHeight(4);

        this.card = new VBox(18, logoNode, titleLabel, progressBar);
        this.card.setAlignment(Pos.CENTER);
        this.card.getStyleClass().add("splash-card");
        this.card.setPrefWidth(390);
        this.card.setMinHeight(296);
        this.card.setPadding(new Insets(30, 36, 28, 36));

        StackPane root = new StackPane(this.card);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: transparent;");
        ThemeManager.applyClasses(root, darkTheme);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        var cssUrl = SplashScreen.class.getResource(CSS_PATH);
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        this.stage.setScene(scene);
    }

    private Node buildLogoNode() {
        Image splashImage = SapphireIcons.getSplashLogoImage();
        if (splashImage != null) {
            ImageView view = new ImageView(splashImage);
            view.setFitWidth(LOGO_DISPLAY_SIZE);
            view.setFitHeight(LOGO_DISPLAY_SIZE);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            return view;
        }
        return SapphireIcons.createSapphireVaultEmblem(LOGO_DISPLAY_SIZE);
    }

    /**
     * Shows the splash stage centered on screen with staggered logo, title, and bar entrance.
     */
    public void show() {
        if (closed) {
            return;
        }
        card.setOpacity(0.0);
        card.setScaleX(0.94);
        card.setScaleY(0.94);

        logoNode.setOpacity(0.0);
        logoNode.setScaleX(0.84);
        logoNode.setScaleY(0.84);

        stage.show();
        stage.centerOnScreen();

        FadeTransition cardFade = new FadeTransition(Duration.millis(300), card);
        cardFade.setFromValue(0.0);
        cardFade.setToValue(1.0);
        cardFade.setInterpolator(MotionFx.EASE_OUT_EXPO);

        ScaleTransition cardScale = new ScaleTransition(Duration.millis(320), card);
        cardScale.setFromX(0.94);
        cardScale.setFromY(0.94);
        cardScale.setToX(1.0);
        cardScale.setToY(1.0);
        cardScale.setInterpolator(MotionFx.EASE_OUT_EXPO);

        FadeTransition logoFade = new FadeTransition(Duration.millis(320), logoNode);
        logoFade.setFromValue(0.0);
        logoFade.setToValue(1.0);
        logoFade.setInterpolator(MotionFx.EASE_OUT_EXPO);

        ScaleTransition logoPop = new ScaleTransition(Duration.millis(380), logoNode);
        logoPop.setFromX(0.84);
        logoPop.setFromY(0.84);
        logoPop.setToX(1.0);
        logoPop.setToY(1.0);
        logoPop.setInterpolator(MotionFx.EASE_OUT_BACK);

        new ParallelTransition(cardFade, cardScale, logoFade, logoPop).play();
        MotionFx.playEntrance(titleLabel, 10.0, 80.0, 280.0);
        MotionFx.playEntrance(progressBar, 8.0, 150.0, 280.0);
    }

    /**
     * Smoothly animates the progress bar to the target progress value.
     */
    public void updateProgress(double targetProgress) {
        updateProgress(targetProgress, null);
    }

    /**
     * Smoothly animates the progress bar to the target progress value.
     */
    public void updateProgress(double targetProgress, String ignoredMessage) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> updateProgress(targetProgress, ignoredMessage));
            return;
        }
        if (closed) {
            return;
        }
        double clamped = Math.max(0.0, Math.min(1.0, targetProgress));
        if (progressTimeline != null) {
            progressTimeline.stop();
        }
        progressTimeline = new Timeline(new KeyFrame(
                Duration.millis(200),
                new KeyValue(progressBar.progressProperty(), clamped, MotionFx.EASE_OUT_EXPO)));
        progressTimeline.play();
    }

    /**
     * Fades and zooms out the splash screen gently and hands off visibility to the main window.
     */
    public void transitionTo(Runnable showMainWindow) {
        Runnable task = () -> {
            if (closed) {
                if (showMainWindow != null) {
                    showMainWindow.run();
                }
                return;
            }
            breathingGlowTimeline.stop();
            FadeTransition fadeOut = new FadeTransition(Duration.millis(240), card);
            fadeOut.setFromValue(card.getOpacity());
            fadeOut.setToValue(0.0);
            fadeOut.setInterpolator(MotionFx.EASE_OUT_EXPO);

            ScaleTransition scaleOut = new ScaleTransition(Duration.millis(240), card);
            scaleOut.setToX(1.03);
            scaleOut.setToY(1.03);
            scaleOut.setInterpolator(MotionFx.EASE_OUT_EXPO);

            ParallelTransition out = new ParallelTransition(fadeOut, scaleOut);
            out.setOnFinished(ev -> {
                closed = true;
                Platform.setImplicitExit(false);
                try {
                    if (showMainWindow != null) {
                        showMainWindow.run();
                    }
                    stage.hide();
                    stage.close();
                } finally {
                    Platform.setImplicitExit(true);
                }
            });
            out.play();
        };

        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    /**
     * Immediately hides and closes the splash stage during abort or shutdown.
     */
    public void closeImmediately() {
        Runnable closeTask = () -> {
            closed = true;
            breathingGlowTimeline.stop();
            if (progressTimeline != null) {
                progressTimeline.stop();
            }
            if (stage.isShowing()) {
                stage.hide();
            }
            stage.close();
        };
        if (Platform.isFxApplicationThread()) {
            closeTask.run();
        } else {
            Platform.runLater(closeTask);
        }
    }
}
