package com.sapphircrypt.sapphirypt.ui;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BrandingAssetsTest {

    private static final String ICON_DIR = "/com/sapphircrypt/sapphirypt/icons/";
    private static final int[] EXPECTED_SIZES = {16, 32, 64, 128, 256, 512};

    @Test
    void multiResolutionTransparentLogoPngsExistAndHaveCleanAlpha() throws Exception {
        for (int size : EXPECTED_SIZES) {
            String resourcePath = ICON_DIR + "sapphirypt-logo-" + size + ".png";
            try (InputStream in = BrandingAssetsTest.class.getResourceAsStream(resourcePath)) {
                assertNotNull(in, "Missing logo resource: " + resourcePath);
                BufferedImage img = ImageIO.read(in);
                assertNotNull(img, "Unreadable PNG resource: " + resourcePath);
                assertEquals(size, img.getWidth(), "Unexpected width for " + resourcePath);
                assertEquals(size, img.getHeight(), "Unexpected height for " + resourcePath);
                assertTrue(img.getColorModel().hasAlpha(), "Alpha channel missing in " + resourcePath);

                int cornerAlpha = (img.getRGB(0, 0) >>> 24) & 0xFF;
                assertEquals(0, cornerAlpha, "Top-left corner should be transparent for " + resourcePath);

                int centerAlpha = (img.getRGB(size / 2, size / 2) >>> 24) & 0xFF;
                assertEquals(255, centerAlpha, "Center gem pixel should be fully opaque for " + resourcePath);
            }
        }
    }

    @Test
    void windowsIcoContainsAllExpectedEntries() throws Exception {
        String icoPath = ICON_DIR + "sapphirypt.ico";
        try (InputStream in = BrandingAssetsTest.class.getResourceAsStream(icoPath)) {
            assertNotNull(in, "Missing ICO resource: " + icoPath);
            byte[] bytes = in.readAllBytes();
            assertTrue(bytes.length > 6 + 6 * 16, "ICO payload unexpectedly small");

            ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0, buf.getShort(0), "ICO reserved word must be 0");
            assertEquals(1, buf.getShort(2), "ICO resource type must be 1");
            int count = Short.toUnsignedInt(buf.getShort(4));
            assertTrue(count >= 6, "Expected at least 6 icon entries in sapphirypt.ico, found " + count);
        }
    }
}
