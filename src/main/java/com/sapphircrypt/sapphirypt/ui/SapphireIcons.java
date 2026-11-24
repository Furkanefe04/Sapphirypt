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

    public static Node sun(double size, String colorHex) {
        return svg("M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zm0-5a1 1 0 0 1 1 1v2a1 1 0 1 1-2 0V3a1 1 0 0 1 1-1zm0 16a1 1 0 0 1 1 1v2a1 1 0 1 1-2 0v-2a1 1 0 0 1 1-1zM4.22 4.22a1 1 0 0 1 1.41 0l1.42 1.42a1 1 0 1 1-1.42 1.41L4.22 5.64a1 1 0 0 1 0-1.42zm12.73 12.73a1 1 0 0 1 1.41 0l1.42 1.41a1 1 0 1 1-1.42 1.42l-1.41-1.42a1 1 0 0 1 0-1.41zM2 12a1 1 0 0 1 1-1h2a1 1 0 1 1 0 2H3a1 1 0 0 1-1-1zm16 0a1 1 0 0 1 1-1h2a1 1 0 1 1 0 2h-2a1 1 0 0 1-1-1zM5.64 16.95a1 1 0 0 1 1.41 1.41l-1.41 1.42a1 1 0 1 1-1.42-1.42l1.42-1.41zM18.36 4.22a1 1 0 0 1 1.42 1.42l-1.42 1.41a1 1 0 1 1-1.41-1.41l1.41-1.42z", size, colorHex);
    }

    public static Node moon(double size, String colorHex) {
        return svg("M12.3 3.1a8.5 8.5 0 1 0 8.6 11.4 7 7 0 0 1-8.6-11.4z", size, colorHex);
    }

    public static Node autoTheme(double size, String colorHex) {
        return svg("M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 2v16a8 8 0 0 1 0-16z", size, colorHex);
    }

    public static Node settings(double size, String colorHex) {
        return svg("M19.14 12.94c.04-.3.06-.61.06-.94s-.02-.64-.07-.94l2.03-1.58a.5.5 0 0 0 .12-.64l-1.92-3.32a.5.5 0 0 0-.61-.22l-2.39.96a7.1 7.1 0 0 0-1.63-.94l-.36-2.54a.49.49 0 0 0-.49-.42h-3.84a.49.49 0 0 0-.49.42l-.36 2.54a7.1 7.1 0 0 0-1.63.94l-2.39-.96a.49.49 0 0 0-.61.22L2.71 8.84a.5.5 0 0 0 .12.64l2.03 1.58c-.05.3-.08.62-.08.94s.03.64.08.94l-2.03 1.58a.5.5 0 0 0-.12.64l1.92 3.32c.13.22.39.31.61.22l2.39-.96c.5.38 1.05.7 1.63.94l.36 2.54c.04.24.25.42.49.42h3.84c.24 0 .45-.18.49-.42l.36-2.54c.58-.24 1.13-.56 1.63-.94l2.39.96c.22.09.48 0 .61-.22l1.92-3.32a.5.5 0 0 0-.12-.64l-2.01-1.58zM12 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z", size, colorHex);
    }

    public static Node search(double size, String colorHex) {
        return svg("M15.5 14h-.79l-.28-.27A6.5 6.5 0 1 0 14 15.5l.27.28v.79l5 5 1.5-1.5-5-5zm-6 0a4.5 4.5 0 1 1 0-9 4.5 4.5 0 0 1 0 9z", size, colorHex);
    }

    public static Node plus(double size, String colorHex) {
        return svg("M11 4h2v7h7v2h-7v7h-2v-7H4v-2h7V4z", size, colorHex);
    }

    public static Node grid(double size, String colorHex) {
        return svg("M3 3h8v8H3V3zm10 0h8v8h-8V3zM3 13h8v8H3v-8zm10 0h8v8h-8v-8z", size, colorHex);
    }

    public static Node list(double size, String colorHex) {
        return svg("M3 5h2v2H3V5zm4 0h14v2H7V5zM3 11h2v2H3v-2zm4 0h14v2H7v-2zm-4 6h2v2H3v-2zm4 0h14v2H7v-2z", size, colorHex);
    }

    public static Node eye(double size, String colorHex) {
        return svg("M12 5C6.5 5 2.1 8.4 1 12c1.1 3.6 5.5 7 11 7s9.9-3.4 11-7c-1.1-3.6-5.5-7-11-7zm0 11.5A4.5 4.5 0 1 1 12 7.5a4.5 4.5 0 0 1 0 9zm0-7a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z", size, colorHex);
    }

    public static Node eyeOff(double size, String colorHex) {
        return svg("M3.3 2L2 3.3l3.1 3.1C3.2 7.8 1.8 9.8 1 12c1.1 3.6 5.5 7 11 7 2 0 3.9-.5 5.5-1.3L20.7 21l1.3-1.3L3.3 2zM12 16.5a4.5 4.5 0 0 1-4.5-4.5c0-.6.1-1.2.4-1.8l5.9 5.9c-.6.3-1.2.4-1.8.4zm4.4-2.5l-6.4-6.4c.6-.1 1.3-.1 2-.1 5.5 0 9.9 3.4 11 7-.6 2-2.1 3.8-4 5.1l-2.6-2.6c.4-.9.4-2 0-3z", size, colorHex);
    }

    public static Node moreHorizontal(double size, String colorHex) {
        return svg("M6 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4z", size, colorHex);
    }

    public static Node close(double size, String colorHex) {
        return svg("M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7A1 1 0 1 0 5.7 7.11L10.59 12 5.7 16.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z", size, colorHex);
    }

    public static Node minimize(double size, String colorHex) {
        return svg("M5 11h14v2H5z", size, colorHex);
    }

    public static Node maximize(double size, String colorHex) {
        return svg("M5 5h14v14H5V5zm2 2v10h10V7H7z", size, colorHex);
    }

    public static Node restore(double size, String colorHex) {
        return svg("M7 7V4h13v13h-3v3H4V7h3zm2 0h8v8h1V6H9v1zm-3 2v9h9V9H6z", size, colorHex);
    }

    public static Node trash(double size, String colorHex) {
        return svg("M6 7h12v13a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V7zm3-4h6l1 2h4v2H4V5h4l1-2z", size, colorHex);
    }

    public static Node edit(double size, String colorHex) {
        return svg("M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zm17.71-10.21a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z", size, colorHex);
    }

    public static Node download(double size, String colorHex) {
        return svg("M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z", size, colorHex);
    }

    public static Node compress(double size, String colorHex) {
        return svg("M4 10h6V4H8v3.59L4.71 4.29 3.29 5.71 6.59 9H4v1zm16 4h-6v6h2v-3.59l3.29 3.3 1.42-1.42-3.3-3.29H20v-1zM4 14v1h2.59l-3.3 3.29 1.42 1.42 3.29-3.3V20h2v-6H4zm16-4V9h-2.59l3.3-3.29-1.42-1.42-3.29 3.3V4h-2v6h6z", size, colorHex);
    }

    public static Node check(double size, String colorHex) {
        return svg("M9 16.2L4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4L9 16.2z", size, colorHex);
    }

    public static Node warning(double size, String colorHex) {
        return svg("M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z", size, colorHex);
    }

    public static Node info(double size, String colorHex) {
        return svg("M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z", size, colorHex);
    }

    public static Node chevronRight(double size, String colorHex) {
        return svg("M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z", size, colorHex);
    }

    public static Node folderOpen(double size, String colorHex) {
        return svg("M20 6h-8l-2-2H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2zm0 12H4V8h16v10z", size, colorHex);
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
