package com.household.finance.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Runs the real Tesseract program on the synthetic bill photo. Skipped (not failed) on a machine without Tesseract,
 * so the build stays green there; the CI workflow installs it so the test runs for real.
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

    private byte[] fixture() throws IOException {
        try (var in = getClass().getResourceAsStream("/fixtures/bill_power_synthetic.png")) {
            return in.readAllBytes();
        }
    }

    @Test
    void readsTheSyntheticBillPhoto() throws Exception {
        Assumptions.assumeTrue(tesseractInstalled(), "Tesseract is not installed on this machine");
        String text = new TesseractOcrEngine("tesseract").read(fixture());
        DocumentExtractor.Extracted x = DocumentExtractor.extract(text);
        assertEquals(DocumentKind.BILL, x.kind());
        assertEquals("SYNTHETIC POWER CO", x.vendor());
        assertEquals(new BigDecimal("142.37"), x.total());
        assertEquals(LocalDate.of(2026, 10, 15), x.dueDate());
    }

    @Test
    void aMissingProgramGivesAPlainMessage() {
        OcrUnavailableException e = assertThrows(OcrUnavailableException.class,
                () -> new TesseractOcrEngine("no-such-ocr-program-xyz").read(new byte[] {1, 2, 3}));
        assertTrue(e.getMessage().contains("Tesseract was not found"), e.getMessage());
        assertTrue(e.getMessage().contains("Nothing was saved"), e.getMessage());
    }
}
