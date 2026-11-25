package com.sapphircrypt.sapphirypt.ui;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class VaultImagePreviewDecoderTest {

    @Test
    void decodesValidPngAndJpegFallbackImages() throws Exception {
        BufferedImage sample = new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sample.createGraphics();
        try {
            g.setColor(new Color(59, 130, 246));
            g.fillRect(0, 0, 12, 8);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        ImageIO.write(sample, "png", pngOut);
        BufferedImage decodedPng = VaultView.decodeWithImageIo(pngOut.toByteArray());
        assertNotNull(decodedPng);
        assertEquals(12, decodedPng.getWidth());
        assertEquals(8, decodedPng.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, decodedPng.getType());

        ByteArrayOutputStream jpgOut = new ByteArrayOutputStream();
        ImageIO.write(sample, "jpg", jpgOut);
        BufferedImage decodedJpg = VaultView.decodeWithImageIo(jpgOut.toByteArray());
        assertNotNull(decodedJpg);
        assertEquals(12, decodedJpg.getWidth());
        assertEquals(8, decodedJpg.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, decodedJpg.getType());
    }

    @Test
    void returnsNullCleanlyForEmptyOrCorruptBytes() {
        assertNull(VaultView.decodeWithImageIo(null));
        assertNull(VaultView.decodeWithImageIo(new byte[0]));
        assertNull(VaultView.decodeWithImageIo(new byte[]{1, 2, 3, 4, 5}));
    }
}
