package com.sapphircrypt.sapphirypt.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.control.ProgressBar;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized GPU-accelerated JavaFX animation utilities and smooth physics curves.
 */
public final class MotionFx {

    /**
     * Smooth exponential deceleration curve (0 -> 1).
     */
    public static final Interpolator EASE_OUT_EXPO = Interpolator.SPLINE(0.16, 1.0, 0.30, 1.0);

    /**
     * Balanced acceleration/deceleration curve (0 -> 1).
     */
    public static final Interpolator EASE_IN_OUT = Interpolator.SPLINE(0.40, 0.0, 0.20, 1.0);

    /**
     * Gentle spring-back overshoot curve (0 -> ~1.045 -> 1.0) without triggering SPLINE [0,1] bounds errors.
     */
    public static final Interpolator EASE_OUT_BACK = new Interpolator() {
        private static final double C1 = 1.15;
        private static final double C3 = C1 + 1.0;

        @Override
        protected double curve(double t) {
            if (t <= 0.0) {
                return 0.0;
            }
            if (t >= 1.0) {
                return 1.0;
            }
            double shifted = t - 1.0;
            return 1.0 + C3 * shifted * shifted * shifted + C1 * shifted * shifted;
        }
    };

    private MotionFx() {
    }

    /**
     * Attaches a subtle tactile press compression and elastic release spring on mouse press/release.
     */
    public static void attachPressSpring(Node node) {
        if (node == null) {
            return;
        }
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, ev -> {
            if (node.isDisabled()) {
                return;
            }
            ScaleTransition down = new ScaleTransition(Duration.millis(80), node);
            down.setToX(0.97);
            down.setToY(0.97);
            down.setInterpolator(EASE_OUT_EXPO);
            down.play();
        });
        node.addEventHandler(MouseEvent.MOUSE_RELEASED, ev -> playReleaseScale(node));
        node.addEventHandler(MouseEvent.MOUSE_EXITED, ev -> {
            if (node.getScaleX() < 0.995) {
                playReleaseScale(node);
            }
        });
    }

    private static void playReleaseScale(Node node) {
        ScaleTransition up = new ScaleTransition(Duration.millis(150), node);
        up.setToX(1.0);
        up.setToY(1.0);
        up.setInterpolator(EASE_OUT_BACK);
        up.play();
    }

    /**
     * Attaches a gentle hover lift and subtle scale expansion without altering existing event handlers.
     */
    public static void attachHoverLift(Node node, double liftY, double hoverScale) {
        if (node == null) {
            return;
        }
        node.addEventHandler(MouseEvent.MOUSE_ENTERED, ev -> {
            if (node.isDisabled()) {
                return;
            }
            TranslateTransition lift = new TranslateTransition(Duration.millis(150), node);
            lift.setToY(liftY);
            lift.setInterpolator(EASE_OUT_EXPO);

            ScaleTransition scale = new ScaleTransition(Duration.millis(150), node);
            scale.setToX(hoverScale);
            scale.setToY(hoverScale);
            scale.setInterpolator(EASE_OUT_EXPO);

            new ParallelTransition(lift, scale).play();
        });
        node.addEventHandler(MouseEvent.MOUSE_EXITED, ev -> {
            TranslateTransition resetY = new TranslateTransition(Duration.millis(160), node);
            resetY.setToY(0.0);
            resetY.setInterpolator(EASE_OUT_EXPO);

            ScaleTransition resetScale = new ScaleTransition(Duration.millis(160), node);
            resetScale.setToX(1.0);
            resetScale.setToY(1.0);
            resetScale.setInterpolator(EASE_OUT_EXPO);

            new ParallelTransition(resetY, resetScale).play();
        });
    }

    /**
     * Plays an entrance fade and vertical slide from {@code fromY} to rest (0).
     */
    public static void playEntrance(Node node, double fromY, double delayMs, double durationMs) {
        if (node == null) {
            return;
        }
        node.setOpacity(0.0);
        node.setTranslateY(fromY);

        FadeTransition fade = new FadeTransition(Duration.millis(durationMs), node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.setInterpolator(EASE_OUT_EXPO);

        TranslateTransition slide = new TranslateTransition(Duration.millis(durationMs), node);
        slide.setFromY(fromY);
        slide.setToY(0.0);
        slide.setInterpolator(EASE_OUT_EXPO);

        ParallelTransition pt = new ParallelTransition(fade, slide);
        if (delayMs > 0.0) {
            pt.setDelay(Duration.millis(delayMs));
        }
        pt.play();
    }

    /**
     * Plays a horizontal slide-and-fade transition suitable for tab or wizard step switches.
     */
    public static void playSlideSwitch(Node node, double fromX, double durationMs) {
        if (node == null) {
            return;
        }
        node.setOpacity(0.0);
        node.setTranslateX(fromX);

        FadeTransition fade = new FadeTransition(Duration.millis(durationMs), node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.setInterpolator(EASE_OUT_EXPO);

        TranslateTransition slide = new TranslateTransition(Duration.millis(durationMs), node);
        slide.setFromX(fromX);
        slide.setToX(0.0);
        slide.setInterpolator(EASE_OUT_EXPO);

        new ParallelTransition(fade, slide).play();
    }

    /**
     * Smoothly cross-fades between existing content in {@code host} and the {@code incoming} node.
     */
    public static void playCrossFadeSwap(StackPane host, Node incoming) {
        if (host == null || incoming == null) {
            return;
        }
        List<Node> previous = new ArrayList<>(host.getChildren());
        previous.remove(incoming);

        if (previous.isEmpty()) {
            host.getChildren().setAll(incoming);
            playEntrance(incoming, 10.0, 0.0, 220.0);
            return;
        }

        incoming.setOpacity(0.0);
        incoming.setTranslateY(10.0);
        host.getChildren().add(incoming);

        List<FadeTransition> fadeOuts = new ArrayList<>(previous.size());
        for (Node oldNode : previous) {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(160), oldNode);
            fadeOut.setFromValue(oldNode.getOpacity());
            fadeOut.setToValue(0.0);
            fadeOut.setInterpolator(EASE_OUT_EXPO);
            fadeOuts.add(fadeOut);
        }

        FadeTransition fadeIn = new FadeTransition(Duration.millis(230), incoming);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.setInterpolator(EASE_OUT_EXPO);

        TranslateTransition slideIn = new TranslateTransition(Duration.millis(230), incoming);
        slideIn.setFromY(10.0);
        slideIn.setToY(0.0);
        slideIn.setInterpolator(EASE_OUT_EXPO);

        ParallelTransition pt = new ParallelTransition();
        pt.getChildren().addAll(fadeOuts);
        pt.getChildren().addAll(fadeIn, slideIn);
        pt.setOnFinished(ev -> {
            host.getChildren().removeAll(previous);
            for (Node oldNode : previous) {
                oldNode.setOpacity(1.0);
                oldNode.setTranslateY(0.0);
            }
        });
        pt.play();
    }

    /**
     * Applies and starts a subtle breathing sapphire DropShadow aura on the target logo node.
     * Caller should invoke {@link Timeline#stop()} when the owner view/stage is closed.
     */
    public static Timeline createSapphireBreathingGlow(Node target, boolean darkTheme) {
        DropShadow glow = new DropShadow();
        glow.setColor(darkTheme
                ? Color.rgb(59, 130, 246, 0.44)
                : Color.rgb(37, 99, 235, 0.24));
        glow.setRadius(16.0);
        glow.setSpread(0.10);
        if (target != null) {
            target.setEffect(glow);
        }

        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(glow.radiusProperty(), 16.0, EASE_IN_OUT),
                        new KeyValue(glow.spreadProperty(), 0.10, EASE_IN_OUT)),
                new KeyFrame(Duration.seconds(2.6),
                        new KeyValue(glow.radiusProperty(), 28.0, EASE_IN_OUT),
                        new KeyValue(glow.spreadProperty(), 0.20, EASE_IN_OUT)));
        timeline.setAutoReverse(true);
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
        return timeline;
    }

    /**
     * Plays a crisp horizontal shake feedback animation on validation or authentication error.
     */
    public static void playErrorShake(Node node) {
        if (node == null) {
            return;
        }
        Timeline shake = new Timeline(
                new KeyFrame(Duration.millis(0), new KeyValue(node.translateXProperty(), 0.0)),
                new KeyFrame(Duration.millis(45), new KeyValue(node.translateXProperty(), -7.0)),
                new KeyFrame(Duration.millis(95), new KeyValue(node.translateXProperty(), 7.0)),
                new KeyFrame(Duration.millis(145), new KeyValue(node.translateXProperty(), -4.0)),
                new KeyFrame(Duration.millis(195), new KeyValue(node.translateXProperty(), 4.0)),
                new KeyFrame(Duration.millis(250), new KeyValue(node.translateXProperty(), 0.0)));
        shake.setOnFinished(ev -> node.setTranslateX(0.0));
        shake.play();
    }

    /**
     * Rotates an icon smoothly by 180 degrees when toggling state (such as theme switching).
     */
    public static void playIconSpin(Node node) {
        if (node == null) {
            return;
        }
        node.setRotate(0.0);
        Timeline spin = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(node.rotateProperty(), 0.0, EASE_OUT_EXPO)),
                new KeyFrame(Duration.millis(280), new KeyValue(node.rotateProperty(), 180.0, EASE_OUT_EXPO)));
        spin.setOnFinished(ev -> node.setRotate(0.0));
        spin.play();
    }

    /**
     * Smoothly animates a ProgressBar progress value.
     */
    public static void animateProgress(ProgressBar bar, double target, double durationMs) {
        if (bar == null) {
            return;
        }
        double clamped = Math.max(0.0, Math.min(1.0, target));
        Timeline tl = new Timeline(new KeyFrame(
                Duration.millis(durationMs),
                new KeyValue(bar.progressProperty(), clamped, EASE_OUT_EXPO)));
        tl.play();
    }

    /**
     * Smoothly animates the preferred width of a Region (such as visual storage ratio bars).
     */
    public static void animatePrefWidth(Region region, double targetWidth, double durationMs) {
        if (region == null) {
            return;
        }
        Timeline tl = new Timeline(new KeyFrame(
                Duration.millis(durationMs),
                new KeyValue(region.prefWidthProperty(), Math.max(0.0, targetWidth), EASE_OUT_EXPO)));
        tl.play();
    }
}
