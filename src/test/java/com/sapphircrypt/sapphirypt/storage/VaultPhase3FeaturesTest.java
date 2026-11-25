package com.sapphircrypt.sapphirypt.storage;

import com.sapphircrypt.sapphirypt.crypto.Argon2Params;
import com.sapphircrypt.sapphirypt.format.VaultMetadata;
import com.sapphircrypt.sapphirypt.format.VaultMetadata.VaultEntry;
import com.sapphircrypt.sapphirypt.ui.MediaFormatSupport;
import com.sapphircrypt.sapphirypt.ui.PdfPreviewParser;
import com.sapphircrypt.sapphirypt.ui.PdfPreviewParser.PdfSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultPhase3FeaturesTest {

    @Test
    void writeFileBytesCreatesAndOverwritesInMemoryWithVersionHistory(@TempDir Path tempDir) throws Exception {
        Path vaultPath = tempDir.resolve("phase3-notes.sapphirypt");
        Path keyPath = tempDir.resolve("phase3-notes.key");
        char[] password = "Phase3NotePassword!".toCharArray();

        byte[] noteV1 = "First encrypted note".getBytes(StandardCharsets.UTF_8);
        byte[] noteV2 = "Updated note saved directly from memory!".getBytes(StandardCharsets.UTF_8);

        try (VaultSession session = VaultSession.createVault(
                vaultPath, password, Argon2Params.forTesting(), keyPath, CpuProfile.BALANCED, 0).session()) {
            VaultEntry created = session.writeFileBytes(
                    VaultMetadata.ROOT_ID, "journal.md", noteV1, false, CancellationToken.NONE);
            session.setFavorite(created.id(), true);
            session.setTags(created.id(), List.of("Journal", "Private"));

            assertArrayEquals(noteV1, session.readFileBytes(created.id(), 1024 * 1024, CancellationToken.NONE));

            VaultEntry updated = session.writeFileBytes(
                    VaultMetadata.ROOT_ID, "journal.md", noteV2, true, CancellationToken.NONE);
            assertEquals(created.id(), updated.id());
            assertTrue(updated.favorite());
            assertEquals(List.of("Journal", "Private"), updated.tags());
            assertEquals(1, updated.versions().size());
            assertEquals(noteV1.length, updated.versions().get(0).logicalSize());
            assertArrayEquals(noteV2, session.readFileBytes(updated.id(), 1024 * 1024, CancellationToken.NONE));
        }

        try (VaultSession reopened = VaultSession.openWithPassword(vaultPath, password, CpuProfile.BALANCED, 0)) {
            VaultEntry loaded = reopened.getMetadataSnapshot()
                    .findChildByName(VaultMetadata.ROOT_ID, "journal.md")
                    .orElseThrow();
            assertEquals(1, loaded.versions().size());
            assertArrayEquals(noteV2, reopened.readFileBytes(loaded.id(), 1024 * 1024, CancellationToken.NONE));
        }
    }

    @Test
    void pdfPreviewParserExtractsMetadataPagesFlateTextAndHandlesCorruptBytes() throws Exception {
        byte[] contentPlain = "BT /F1 12 Tf 72 720 Td (Hello Sapphirypt PDF) Tj ET".getBytes(StandardCharsets.ISO_8859_1);
        Deflater deflater = new Deflater();
        deflater.setInput(contentPlain);
        deflater.finish();
        ByteArrayOutputStream compressedOut = new ByteArrayOutputStream();
        byte[] tmp = new byte[256];
        while (!deflater.finished()) {
            int n = deflater.deflate(tmp);
            compressedOut.write(tmp, 0, n);
        }
        deflater.end();
        byte[] compressed = compressedOut.toByteArray();

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.write(("%PDF-1.7\n"
                + "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n"
                + "2 0 obj << /Type /Pages /Count 2 /Kids [3 0 R 4 0 R] >> endobj\n"
                + "3 0 obj << /Type /Page /Parent 2 0 R /Contents 5 0 R >> endobj\n"
                + "4 0 obj << /Type /Page /Parent 2 0 R >> endobj\n"
                + "6 0 obj << /Title (Vault Guide) /Author (Sapphirypt Team) >> endobj\n"
                + "5 0 obj << /Filter /FlateDecode /Length " + compressed.length + " >>\nstream\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        pdf.write(compressed);
        pdf.write("\nendstream endobj\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1));

        PdfSummary summary = PdfPreviewParser.parse(pdf.toByteArray());
        assertTrue(summary.isValidPdf());
        assertEquals("1.7", summary.pdfVersion());
        assertEquals(2, summary.pageCount());
        assertEquals("Vault Guide", summary.title());
        assertEquals("Sapphirypt Team", summary.author());
        assertTrue(summary.extractedText().contains("Hello Sapphirypt PDF"));

        PdfSummary invalid = PdfPreviewParser.parse(new byte[]{1, 2, 3, 4});
        assertFalse(invalid.isValidPdf());
        assertEquals(0, invalid.pageCount());
        PdfSummary empty = PdfPreviewParser.parse(null);
        assertFalse(empty.isValidPdf());
    }

    @Test
    void mediaFormatSupportClassifiesFormatsAndExtractsHeicEmbeddedJpeg() throws Exception {
        assertTrue(MediaFormatSupport.isWebViewImage("svg"));
        assertTrue(MediaFormatSupport.isWebViewImage("WEBP"));
        assertTrue(MediaFormatSupport.isHeic("heic"));
        assertTrue(MediaFormatSupport.isPdf("pdf"));
        assertTrue(MediaFormatSupport.isAudio("mp3"));
        assertTrue(MediaFormatSupport.isVideo("mp4"));
        assertTrue(MediaFormatSupport.isStandardImage("png"));
        assertTrue(MediaFormatSupport.isText("md"));

        String svgHtml = MediaFormatSupport.buildWebViewImageHtml("<svg/>".getBytes(StandardCharsets.UTF_8), "svg", true);
        assertTrue(svgHtml.contains("data:image/svg+xml;base64,"));

        // Synthetic ISOBMFF ftyp header + embedded JPEG payload (> 1 KiB)
        ByteArrayOutputStream heicOut = new ByteArrayOutputStream();
        heicOut.write(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0});
        heicOut.write(new byte[64]);
        ByteArrayOutputStream fakeJpeg = new ByteArrayOutputStream();
        fakeJpeg.write(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
        fakeJpeg.write(new byte[1400]);
        fakeJpeg.write(new byte[]{(byte) 0xFF, (byte) 0xD9});
        byte[] expectedJpeg = fakeJpeg.toByteArray();
        heicOut.write(expectedJpeg);
        heicOut.write(new byte[32]);

        byte[] extracted = MediaFormatSupport.extractEmbeddedJpegFromHeic(heicOut.toByteArray());
        assertNotNull(extracted);
        assertArrayEquals(expectedJpeg, extracted);

        assertNull(MediaFormatSupport.extractEmbeddedJpegFromHeic("not-a-heic-file".getBytes(StandardCharsets.UTF_8)));
    }
}
