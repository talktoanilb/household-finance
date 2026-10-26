package com.household.finance.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A manual check, not a unit test: reads the real photos in the folder named by the HF_PHOTOS environment variable and
 * prints what the reader found. Skipped when HF_PHOTOS is not set, so it does nothing in the normal build or in CI.
 * The photos stay outside the repository and only a summary is printed: never the text, vendor or account details.
 * Run: HF_PHOTOS="/path/to/folder" mvn -Dtest=RealPhotosCheckTest test
 * To see what was read from ONE photo, also set HF_SHOW_TEXT to its number (1, 2, ...). That prints its text with long
 * number runs (8 or more digits, or groups such as 555-123-4567) and email addresses masked. Names and addresses are
 * NOT masked, so check the text before sharing it.
 */
class RealPhotosCheckTest {

    @Test
    void summarisesTheRealPhotosInTheFolder() throws Exception {
        String folder = System.getenv("HF_PHOTOS");
        Assumptions.assumeTrue(folder != null && !folder.isBlank(), "HF_PHOTOS is not set");
        TesseractOcrEngine engine = new TesseractOcrEngine(System.getenv().getOrDefault("HF_TESSERACT", "tesseract"));
        List<Path> files;
        try (Stream<Path> listing = Files.list(Path.of(folder))) {
            files = listing.filter(p -> {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
            }).sorted().toList();
        }
        int n = 0;
        for (Path file : files) {
            n++;
            long start = System.currentTimeMillis();
            try {
                List<String> readings = engine.readAll(Files.readAllBytes(file));
                String text = readings.get(0);
                DocumentExtractor.Extracted x = DocumentExtractor.extractAll(readings);
                System.out.println("photo " + n + ": kind=" + x.kind() + " vendorFound=" + (x.vendor() != null)
                        + " total=" + x.total() + " subtotal=" + x.subtotal() + " tax=" + x.tax()
                        + " date=" + x.documentDate() + " due=" + x.dueDate() + " notes=" + x.notes().size()
                        + " seconds=" + (System.currentTimeMillis() - start) / 1000);
                x.notes().forEach(note -> System.out.println("   note: " + note));
                if (String.valueOf(n).equals(System.getenv("HF_SHOW_TEXT"))) {
                    System.out.println("--- text of photo " + n + " (long numbers and emails masked) ---");
                    System.out.println(mask(text)
                            .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "EMAIL"));
                    System.out.println("--- end of text ---");
                }
            } catch (IOException e) {
                System.out.println("photo " + n + ": could not be read (" + e.getMessage() + ")");
            }
        }
        System.out.println("photos checked: " + n);
    }

    /** Masks long digit runs and digit groups joined by single spaces or hyphens (accounts, cards, phones). Dates and amounts stay. */
    static String mask(String text) {
        return text.replaceAll("\\b\\d{3,}(?:[ -]\\d{3,}){2,}\\b|\\b\\d{8,}\\b", "XXXX");
    }
}
