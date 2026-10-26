package com.household.finance.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The extractor only proposes. These tests pin down what it reads and, just as important, what it refuses to guess.
 * All text below is synthetic.
 */
class DocumentExtractorTest {

    /** What Tesseract printed for fixtures/bill_power_synthetic.png. */
    private static final String POWER_BILL = """
            SYNTHETIC POWER CO

            Account: TEST-0000

            Service period: 09/01/2026 - 09/30/2026
            Due date: 10/15/2026

            Amount due: $142.37
            """;

    /** Hand-checked: 4.19 + 3.49 = 7.68; 8.25% of 7.68 = 0.6336, shown as 0.63; 7.68 + 0.63 = 8.31. */
    private static final String GROCERY_RECEIPT = """
            CORNER GROCERY
            123 Main St
            10/18/2026 14:32
            MILK 1 GAL        4.19
            BREAD             3.49
            SUBTOTAL          7.68
            TAX 8.25%         0.63
            TOTAL             8.31
            VISA ****1234     8.31
            """;

    @Test
    void readsABill() {
        DocumentExtractor.Extracted x = DocumentExtractor.extract(POWER_BILL);
        assertEquals(DocumentKind.BILL, x.kind());
        assertEquals("SYNTHETIC POWER CO", x.vendor());
        assertEquals(new BigDecimal("142.37"), x.total());
        assertEquals(LocalDate.of(2026, 10, 15), x.dueDate());
        // The service period is not the bill date, and the account line is never read as an amount.
        assertNull(x.documentDate());
        assertNull(x.tax());
        assertEquals(0, x.notes().size());
    }

    @Test
    void readsAReceiptAndKeepsTaxRateOutOfTheAmounts() {
        DocumentExtractor.Extracted x = DocumentExtractor.extract(GROCERY_RECEIPT);
        assertEquals(DocumentKind.RECEIPT, x.kind());
        assertEquals("CORNER GROCERY", x.vendor());
        assertEquals(new BigDecimal("8.31"), x.total());
        assertEquals(new BigDecimal("7.68"), x.subtotal());
        assertEquals(new BigDecimal("0.63"), x.tax());
        assertEquals(LocalDate.of(2026, 10, 18), x.documentDate());
        assertEquals(0, x.notes().size());
    }

    @Test
    void doesNotGuessBetweenTwoDifferentTotals() {
        DocumentExtractor.Extracted x = DocumentExtractor.extract("""
                CORNER GROCERY
                TOTAL             8.31
                TOTAL             9.31
                """);
        assertEquals(DocumentKind.RECEIPT, x.kind());
        assertNull(x.total());
        assertTrue(x.notes().get(0).contains("8.31") && x.notes().get(0).contains("9.31"), x.notes().toString());
    }

    @Test
    void ignoresMinimumAndPastDueAmounts() {
        DocumentExtractor.Extracted x = DocumentExtractor.extract("""
                SYNTHETIC WATER CO
                Past due as of 09/01/2026
                Minimum amount due: $25.00
                Total amount due: $142.00
                Due 10/20/2026
                """);
        assertEquals(DocumentKind.BILL, x.kind());
        assertEquals(new BigDecimal("142.00"), x.total());
        assertEquals(LocalDate.of(2026, 10, 20), x.dueDate());
    }

    @Test
    void readsThousandsSeparatorsAndNamedMonths() {
        DocumentExtractor.Extracted x = DocumentExtractor.extract("""
                SYNTHETIC ROOFING LLC
                Invoice date: Oct 5, 2026
                Amount due: $1,234.50
                """);
        assertEquals(new BigDecimal("1234.50"), x.total());
        assertEquals(LocalDate.of(2026, 10, 5), x.documentDate());
    }

    @Test
    void twoDifferentDatesOnAReceiptAreNotGuessed() {
        DocumentExtractor.Extracted x = DocumentExtractor.extract("""
                CORNER GROCERY
                10/18/2026 14:32
                10/19/2026 09:05
                TOTAL             8.31
                Return by 11/17/2026
                """);
        assertNull(x.documentDate());
        assertEquals(new BigDecimal("8.31"), x.total());
        assertEquals(1, x.notes().size());
    }

    @Test
    void twoTaxLinesAreNotSummedOrGuessed() {
        DocumentExtractor.Extracted x = DocumentExtractor.extract("""
                CORNER GROCERY
                SUBTOTAL          10.00
                TAX               0.50
                TAX               0.25
                TOTAL             10.75
                """);
        assertNull(x.tax());
        assertEquals(new BigDecimal("10.75"), x.total());
    }

    @Test
    void garbageProposesNothing() {
        DocumentExtractor.Extracted x = DocumentExtractor.extract("~~ ;; xX\n.. 1 ,,\n");
        assertEquals(DocumentKind.UNKNOWN, x.kind());
        assertTrue(x.isEmpty());
    }

    @Test
    void blankTextProposesNothing() {
        assertTrue(DocumentExtractor.extract("   \n\n").isEmpty());
        assertTrue(DocumentExtractor.extract(null).isEmpty());
    }

    // ---- garbled labels and several readings (all text synthetic) ----

    @Test
    void aTotalLabelMisreadByOneLetterIsStillTheTotal() {
        var x = DocumentExtractor.extract("""
                TEST MARKET
                SUBTOTAL 10.00
                TAX 0.80
                TOTAL 10.80
                """.replace("TOTAL 10.80", "T0TAL 10.80"));
        assertEquals(new BigDecimal("10.80"), x.total());
    }

    @Test
    void aGarbledSubtotalIsNeverTakenForTheTotal() {
        var x = DocumentExtractor.extract("""
                TEST MARKET
                S BTOTAL 36.32
                TAX 1 2.91
                Visa 39.23
                """);
        assertEquals(new BigDecimal("39.23"), x.total());
    }

    @Test
    void readingsThatAgreeGiveTheirAnswer() {
        String text = "TEST MARKET\nTOTAL 12.34\n09/01/2026\n";
        var x = DocumentExtractor.extractAll(java.util.List.of(text, text));
        assertEquals(new BigDecimal("12.34"), x.total());
        assertEquals(LocalDate.of(2026, 9, 1), x.documentDate());
    }

    @Test
    void readingsThatSplitProposeNoTotalAndNameBothNumbers() {
        var x = DocumentExtractor.extractAll(java.util.List.of(
                "TEST MARKET\nTOTAL 7.57\n", "TEST MARKET\nTOTAL 7.67\n"));
        assertNull(x.total());
        assertTrue(x.notes().stream().anyMatch(n -> n.contains("7.57") && n.contains("7.67")));
    }

    @Test
    void aValueOnlyOneReadingFoundIsKept() {
        var x = DocumentExtractor.extractAll(java.util.List.of(
                "TEST MARKET\nTOTAL 5.00\n", "TEST MARKET\nTOTAL 5.00\n09/02/2026\n"));
        assertEquals(LocalDate.of(2026, 9, 2), x.documentDate());
    }

    @Test
    void aMajorityDoesNotOutvoteADifferentReading() {
        var x = DocumentExtractor.extractAll(java.util.List.of(
                "TEST MARKET\nTOTAL 11.67\n", "TEST MARKET\nTOTAL 11.67\n", "TEST MARKET\nTOTAL 11.57\n"));
        assertNull(x.total());
        assertTrue(x.notes().stream().anyMatch(n -> n.contains("11.57 or 11.67")));
    }

    @Test
    void aTaxThatReadingsDisagreeOnIsNotProposed() {
        var x = DocumentExtractor.extractAll(java.util.List.of(
                "TEST MARKET\nSUBTOTAL 6.99\nTAX 0.58\nTOTAL 7.57\n", "TEST MARKET\nSUBTOTAL 6.99\nTAX 0.68\nTOTAL 7.57\n"));
        assertNull(x.tax());
        assertEquals(new BigDecimal("7.57"), x.total());
        assertEquals(new BigDecimal("6.99"), x.subtotal());
    }

    @Test
    void aTotalThatFitsNoSubtotalPlusTaxIsReplacedByTheConsistentOnes() {
        var x = DocumentExtractor.extractAll(java.util.List.of(
                "TEST MARKET\nSUBTOTAL 6.99\nTAX 0.58\nVisa 7.87\n", "TEST MARKET\nSUBTOTAL 6.99\nTAX 0.68\nVisa 7.87\n"));
        assertNull(x.total());
        assertTrue(x.notes().stream().anyMatch(n -> n.contains("7.57 or 7.67")));
    }
}
