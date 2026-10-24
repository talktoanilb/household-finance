package com.household.finance.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/**
 * Round trip: write the synthetic statement text into a real PDF with PDFBox, read it back with the production
 * extractor, and check it parses to exactly the same statement as the original text.
 */
class PdfBoxTextExtractorTest {

    private static byte[] pdfOf(String text) throws Exception {
        String[] lines = text.split("\\R");
        int longest = 0;
        for (String line : lines) {
            longest = Math.max(longest, line.length());
        }
        float fontSize = Math.min(8f, 540f / (longest * 0.6f)); // Courier characters are 0.6 em wide
        float leading = fontSize + 3f;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(document, page)) {
                out.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER), fontSize);
                float y = page.getMediaBox().getHeight() - 40f;
                for (String line : lines) {
                    out.beginText();
                    out.newLineAtOffset(20f, y);
                    out.showText(line);
                    out.endText();
                    y -= leading;
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    @Test
    void textExtractedFromAGeneratedPdfParsesLikeTheOriginal() throws Exception {
        String original = ChasePdfStatementParserTest.fixtureText();
        String extracted = new PdfBoxTextExtractor().extract(pdfOf(original));
        ChasePdfStatementParser parser = new ChasePdfStatementParser();
        assertEquals(parser.parse(original), parser.parse(extracted));
    }
}
