package com.household.finance.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Runs the real Tesseract program on the synthetic bill photo, also turned sideways and buried in a large noisy
 * photo the way phone pictures arrive. Tests that need Tesseract are skipped (not failed) on a machine without it, so
 * the build stays green there; the CI workflow installs it so they run for real.
 */
class TesseractOcrEngineTest {

    private static boolean tesseractInstalled() {
        try {
            Process p = new ProcessBuilder("tesseract", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private BufferedImage fixtureImage() throws IOException {
        try (var in = getClass().getResourceAsStream("/fixtures/bill_power_synthetic.png")) {
            return ImageIO.read(in);
        }
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** The bill, shrunk or grown to {@code billWidth} pixels wide and centred on a grey noisy canvas, like a bill lying on a table. */
    private static BufferedImage onBusyCanvas(BufferedImage bill, int canvasW, int canvasH, int billWidth) {
        BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_BYTE_GRAY);
        Random random = new Random(7);
        for (int y = 0; y < canvasH; y++) {
            for (int x = 0; x < canvasW; x++) {
                int v = 110 + random.nextInt(60);
                canvas.getRaster().setSample(x, y, 0, v);
            }
        }
        int billHeight = billWidth * bill.getHeight() / bill.getWidth();
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(bill, (canvasW - billWidth) / 2, (canvasH - billHeight) / 2, billWidth, billHeight, Color.WHITE, null);
        g.dispose();
        return canvas;
    }

    private static void assertReadsTheBill(String text) {
        DocumentExtractor.Extracted x = DocumentExtractor.extract(text);
        assertEquals(DocumentKind.BILL, x.kind());
        assertEquals(new BigDecimal("142.37"), x.total());
        assertEquals(LocalDate.of(2026, 10, 15), x.dueDate());
    }

    @Test
    void readsTheSyntheticBillPhoto() throws Exception {
        Assumptions.assumeTrue(tesseractInstalled(), "Tesseract is not installed on this machine");
        String text = new TesseractOcrEngine("tesseract").read(png(fixtureImage()));
        assertReadsTheBill(text);
        assertEquals("SYNTHETIC POWER CO", DocumentExtractor.extract(text).vendor());
    }

    /** Phone photos are often stored sideways with no rotation tag. Every quarter turn must still be read. */
    @Test
    void readsAPhotoThatIsSidewaysOrUpsideDown() throws Exception {
        Assumptions.assumeTrue(tesseractInstalled(), "Tesseract is not installed on this machine");
        TesseractOcrEngine engine = new TesseractOcrEngine("tesseract");
        for (int degrees : new int[] {90, 180, 270}) {
            assertReadsTheBill(engine.read(png(TesseractOcrEngine.rotate(fixtureImage(), degrees))));
        }
    }

    /** A 12-megapixel photo (4032 x 3024) with the bill covering about 60% of the width, turned sideways. */
    @Test
    void readsABillInsideALargeNoisyPhoto() throws Exception {
        Assumptions.assumeTrue(tesseractInstalled(), "Tesseract is not installed on this machine");
        BufferedImage photo = onBusyCanvas(fixtureImage(), 4032, 3024, 2400);
        TesseractOcrEngine engine = new TesseractOcrEngine("tesseract");
        assertReadsTheBill(engine.read(png(photo)));
        assertReadsTheBill(engine.read(png(TesseractOcrEngine.rotate(photo, 90))));
    }

    @Test
    void aBlankPageReadsAsNothing() throws Exception {
        Assumptions.assumeTrue(tesseractInstalled(), "Tesseract is not installed on this machine");
        BufferedImage blank = new BufferedImage(800, 600, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 600);
        g.dispose();
        String text = new TesseractOcrEngine("tesseract").read(png(blank));
        assertTrue(!DocumentExtractor.extract(text).hasAmountOrDate());
    }

    @Test
    void aMissingProgramGivesAPlainMessage() throws Exception {
        OcrUnavailableException e = assertThrows(OcrUnavailableException.class,
                () -> new TesseractOcrEngine("no-such-ocr-program-xyz").read(png(fixtureImage())));
        assertTrue(e.getMessage().contains("Tesseract was not found"), e.getMessage());
        assertTrue(e.getMessage().contains("Nothing was saved"), e.getMessage());
    }

    @Test
    void somethingThatIsNotAnImageIsRefusedWithAPlainMessage() {
        UnusablePhotoException e = assertThrows(UnusablePhotoException.class,
                () -> new TesseractOcrEngine("tesseract").read("not an image at all".getBytes()));
        assertTrue(e.getMessage().contains("could not be opened"), e.getMessage());
    }

    /** A PNG that only declares 9000 x 9000 pixels (81 megapixels). It must be refused before any pixel is decoded. */
    @Test
    void aHugeImageIsRefusedBeforeItIsDecoded() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(header);
        h.writeBytes("IHDR");
        h.writeInt(9000);
        h.writeInt(9000);
        h.write(new byte[] {8, 0, 0, 0, 0});
        CRC32 crc = new CRC32();
        crc.update(header.toByteArray());
        out.writeInt(13);
        out.write(header.toByteArray());
        out.writeInt((int) crc.getValue());
        UnusablePhotoException e = assertThrows(UnusablePhotoException.class,
                () -> new TesseractOcrEngine("tesseract").read(bytes.toByteArray()));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
    }

    @Test
    void countsOnlyConfidentWordsOfThreeOrMoreCharacters() {
        String tsv = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
                + "4\t1\t1\t1\t1\t0\t0\t0\t10\t10\t-1\t\n"
                + "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t96.5\tAmount\n"
                + "5\t1\t1\t1\t1\t2\t0\t0\t10\t10\t91.0\tdue:\n"
                + "5\t1\t1\t1\t1\t3\t0\t0\t10\t10\t95.0\t$1\n"
                + "5\t1\t1\t1\t1\t4\t0\t0\t10\t10\t40.0\tgarbage\n"
                + "5\t1\t1\t1\t1\t5\t0\t0\t10\t10\t88.0\tHi\n";
        assertEquals(2, TesseractOcrEngine.confidentWords(tsv));
    }
}
