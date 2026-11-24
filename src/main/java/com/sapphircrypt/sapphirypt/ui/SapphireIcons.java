package com.sapphircrypt.sapphirypt.ui;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.SVGPath;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimalist geometric SVG icons and the centralized Concept 7 Sapphire Vault logo loader.
 */
public final class SapphireIcons {
    private static final String ICON_DIR = "/com/sapphircrypt/sapphirypt/icons/";
    private static final int[] APP_ICON_SIZES = {16, 32, 64, 128, 256};

    private static final Map<Integer, Image> CACHED_SIZE_IMAGES = new HashMap<>();
    private static List<Image> cachedAppIcons;
    private static Image cachedSplashLogo;

    private SapphireIcons() {
    }

    /**
     * Returns the multi-resolution Concept 7 application icons for stages and dialogs.
     */
    public static synchronized List<Image> getApplicationIcons() {
        if (cachedAppIcons == null) {
            List<Image> icons = new ArrayList<>(APP_ICON_SIZES.length);
            for (int size : APP_ICON_SIZES) {
                Image img = loadSizedImage(size);
                if (img != null) {
                    icons.add(img);
                }
            }
            cachedAppIcons = List.copyOf(icons);
        }
        return cachedAppIcons;
    }

    /**
     * Returns the high-resolution 512x512 transparent Concept 7 logo for the splash screen.
     */
    public static synchronized Image getSplashLogoImage() {
        if (cachedSplashLogo == null) {
            cachedSplashLogo = loadSizedImage(512);
        }
        return cachedSplashLogo;
    }

    /**
     * Returns the Concept 7 Sapphire Vault emblem scaled cleanly for the requested size,
     * falling back to the minimalist geometric vector emblem if icon resources are unavailable.
     */
    public static Node createSapphireVaultEmblem(double size) {
        int resourceSize = size <= 40.0 ? 64 : (size <= 140.0 ? 256 : 512);
        Image image = loadSizedImage(resourceSize);
        if (image != null) {
            ImageView view = new ImageView(image);
            view.setFitWidth(size);
            view.setFitHeight(size);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            StackPane pane = new StackPane(view);
            pane.setPrefSize(size, size);
            pane.setMinSize(size, size);
            pane.setMaxSize(size, size);
            return pane;
        }
        return createFallbackVectorEmblem(size);
    }

    private static synchronized Image loadSizedImage(int resourceSize) {
        Image existing = CACHED_SIZE_IMAGES.get(resourceSize);
        if (existing != null) {
            return existing;
        }
        String resourcePath = ICON_DIR + "sapphirypt-logo-" + resourceSize + ".png";
        try (InputStream in = SapphireIcons.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            Image loaded = new Image(in);
            if (loaded.isError()) {
                return null;
            }
            CACHED_SIZE_IMAGES.put(resourceSize, loaded);
            return loaded;
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * Fallback geometric Sapphire + Vault emblem when PNG icon resources are unavailable.
     */
    private static Node createFallbackVectorEmblem(double size) {
        double half = size / 2.0;

        // Octagonal vault frame
        Polygon vaultRing = new Polygon();
        double rOuter = half * 0.94;
        double cut = rOuter * 0.414;
        vaultRing.getPoints().addAll(-cut, -rOuter, cut, -rOuter, rOuter, -cut, rOuter, cut, cut, rOuter, -cut, rOuter, -rOuter, cut, -rOuter, -cut);
        vaultRing.setFill(Color.web("#0F172A"));
        vaultRing.setStroke(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE, new Stop(0, Color.web("#60A5FA")), new Stop(1, Color.web("#1D4ED8"))));
        vaultRing.setStrokeWidth(Math.max(1.6, size * 0.055));

        // Faceted sapphire gem at the center
        Polygon gemOuter = new Polygon();
        double gw = half * 0.50;
        double gh = half * 0.56;
        gemOuter.getPoints().addAll(0.0, -gh, gw, -gh * 0.30, gw * 0.72, gh * 0.85, 0.0, gh, -gw * 0.72, gh * 0.85, -gw, -gh * 0.30);
        gemOuter.setFill(new LinearGradient(0, 0, 0.9, 1, true, CycleMethod.NO_CYCLE, new Stop(0, Color.web("#93C5FD")), new Stop(0.45, Color.web("#2563EB")), new Stop(1, Color.web("#1E3A8A"))));
        gemOuter.setStroke(Color.web("#BFDBFE"));
        gemOuter.setStrokeWidth(Math.max(0.9, size * 0.025));

        Polygon gemFacet = new Polygon();
        gemFacet.getPoints().addAll(0.0, -gh * 0.52, gw * 0.46, -gh * 0.14, 0.0, gh * 0.52, -gw * 0.46, -gh * 0.14);
        gemFacet.setFill(Color.web("#DBEAFE", 0.30));

        StackPane pane = new StackPane(vaultRing, gemOuter, gemFacet);
        pane.setPrefSize(size, size);
        pane.setMinSize(size, size);
        pane.setMaxSize(size, size);
        return pane;
    }

    public static Node folder(double size, String colorHex) {
        return svg("M2 5a2 2 0 0 1 2-2h5l2 2h9a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5z", size, colorHex);
    }

    public static Node file(double size, String colorHex) {
        return svg("M6 2h8l5 5v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zm7 1.5V8h4.5L13 3.5z", size, colorHex);
    }

    public static Node lock(double size, String colorHex) {
        return svg("M7 10V7a5 5 0 0 1 10 0v3h1a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h1zm2 0h6V7a3 3 0 0 0-6 0v3z", size, colorHex);
    }

    public static Node key(double size, String colorHex) {
        return svg("M14.5 2a6.5 6.5 0 0 0-6.1 8.7L2 17.1V22h4.9v-2.4h2.4v-2.4h2.4l1.6-1.6A6.5 6.5 0 1 0 14.5 2zm2 5a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z", size, colorHex);
    }

    public static Node shield(double size, String colorHex) {
        return svg("M12 2L4 5v6.1c0 5.1 3.4 9.7 8 10.9 4.6-1.2 8-5.8 8-10.9V5l-8-3z", size, colorHex);
    }

    private static Node svg(String pathData, double size, String colorHex) {
        SVGPath path = new SVGPath();
        path.setContent(pathData);
        path.setFill(Color.web(colorHex));
        double scale = size / 24.0;
        path.setScaleX(scale);
        path.setScaleY(scale);
        StackPane box = new StackPane(path);
        box.setPrefSize(size, size);
        box.setMinSize(size, size);
        box.setMaxSize(size, size);
        return box;
    }
}
