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
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * Frameless translucent splash screen displaying the Concept 7 Sapphire Vault logo,
 * startup initialization progress, and smooth handoff into the main window.
 */
public final class SplashScreen {
    private static final String CSS_PATH = "/com/sapphircrypt/sapphirypt/sapphirypt.css";
    private static final double LOGO_DISPLAY_SIZE = 148.0;

    private final Stage stage;
    private final VBox card;
    private final ProgressBar progressBar;
    private final Label statusLabel;

    private Timeline progressTimeline;
    private boolean closed;

    public SplashScreen() {
        this.stage = new Stage(StageStyle.TRANSPARENT);
        this.stage.setTitle("Sapphirypt");
        var appIcons = SapphireIcons.getApplicationIcons();
        if (!appIcons.isEmpty()) {
            this.stage.getIcons().setAll(appIcons);
        }

        Node logoNode = buildLogoNode();

        Label titleLabel = new Label("Sapphirypt");
        titleLabel.getStyleClass().add("splash-title");

        Label subtitleLabel = new Label("Şifreli Dijital Kasa");
        subtitleLabel.getStyleClass().add("splash-subtitle");

        Label taglineLabel = new Label("AES-256-GCM • Argon2id • Çevrimdışı");
        taglineLabel.getStyleClass().add("splash-tagline");

        VBox textGroup = new VBox(3, titleLabel, subtitleLabel, taglineLabel);
        textGroup.setAlignment(Pos.CENTER);

        this.progressBar = new ProgressBar(0.08);
        this.progressBar.getStyleClass().add("splash-progress");
        this.progressBar.setPrefWidth(280);
        this.progressBar.setPrefHeight(6);
        this.progressBar.setMinHeight(6);
        this.progressBar.setMaxHeight(6);

        this.statusLabel = new Label("Başlatılıyor...");
        this.statusLabel.getStyleClass().add("splash-status");

        Label versionLabel = new Label("v0.1.0");
        versionLabel.getStyleClass().add("splash-version");

        VBox progressGroup = new VBox(7, progressBar, statusLabel, versionLabel);
        progressGroup.setAlignment(Pos.CENTER);

        this.card = new VBox(14, logoNode, textGroup, progressGroup);
        this.card.setAlignment(Pos.CENTER);
        this.card.getStyleClass().add("splash-card");
        this.card.setPrefWidth(468);
        this.card.setMinHeight(336);
        this.card.setPadding(new Insets(24, 32, 22, 32));

        StackPane root = new StackPane(this.card);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        var cssUrl = SplashScreen.class.getResource(CSS_PATH);
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        this.stage.setScene(scene);
    }

    private Node buildLogoNode() {
        Node logo;
        Image splashImage = SapphireIcons.getSplashLogoImage();
        if (splashImage != null) {
            ImageView view = new ImageView(splashImage);
            view.setFitWidth(LOGO_DISPLAY_SIZE);
            view.setFitHeight(LOGO_DISPLAY_SIZE);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            logo = view;
        } else {
            logo = SapphireIcons.createSapphireVaultEmblem(LOGO_DISPLAY_SIZE);
        }

        DropShadow sapphireGlow = new DropShadow();
        sapphireGlow.setColor(Color.rgb(59, 130, 246, 0.38));
        sapphireGlow.setRadius(24);
        sapphireGlow.setSpread(0.14);
        logo.setEffect(sapphireGlow);
        return logo;
    }

    /**
     * Shows the splash stage centered on screen with an entrance fade and scale transition.
     */
    public void show() {
        if (closed) {
            return;
        }
        card.setOpacity(0.0);
        card.setScaleX(0.94);
        card.setScaleY(0.94);

        stage.show();
        stage.centerOnScreen();

        FadeTransition fadeIn = new FadeTransition(Duration.millis(350), card);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);

        ScaleTransition scaleIn = new ScaleTransition(Duration.millis(350), card);
        scaleIn.setFromX(0.94);
        scaleIn.setFromY(0.94);
        scaleIn.setToX(1.0);
        scaleIn.setToY(1.0);

        new ParallelTransition(fadeIn, scaleIn).play();
    }

    /**
     * Smoothly animates the progress bar to the target progress and updates the status message.
     */
    public void updateProgress(double targetProgress, String message) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> updateProgress(targetProgress, message));
            return;
        }
        if (closed) {
            return;
        }
        if (message != null && !message.isBlank()) {
            statusLabel.setText(message);
        }
        double clamped = Math.max(0.0, Math.min(1.0, targetProgress));
        if (progressTimeline != null) {
            progressTimeline.stop();
        }
        progressTimeline = new Timeline(new KeyFrame(
                Duration.millis(180),
                new KeyValue(progressBar.progressProperty(), clamped)));
        progressTimeline.play();
    }

    /**
     * Fades out the splash screen and hands off visibility to the main window while guarding implicitExit.
     */
    public void transitionTo(Runnable showMainWindow) {
        Runnable task = () -> {
            if (closed) {
                if (showMainWindow != null) {
                    showMainWindow.run();
                }
                return;
            }
            FadeTransition fadeOut = new FadeTransition(Duration.millis(240), card);
            fadeOut.setFromValue(card.getOpacity());
            fadeOut.setToValue(0.0);
            fadeOut.setOnFinished(ev -> {
                closed = true;
                // Prevent JavaFX from exiting between closing the splash stage and showing the primary stage.
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
            fadeOut.play();
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
