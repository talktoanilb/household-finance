package com.household.finance.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.household.finance.ingest.Verifier;
import com.household.finance.statement.CardPdfStatementParser.Issuer;
import com.household.finance.statement.StatementLine.Direction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Synthetic card statement text in the layout of real Discover, Chase and Citi Costco PDFs (invented names and
 * amounts). Hand-checked: Discover 100.00 + 165.25 - 112.50 = 152.75; Chase 50.00 + 42.34 - 50.00 = 42.34;
 * Citi 80.00 + 70.00 - 85.00 = 65.00.
 */
class CardPdfStatementParserTest {

    private static String fixture(String name) throws Exception {
        try (var in = CardPdfStatementParserTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void discoverReadsSummaryAndLines() throws Exception {
        ParsedStatement s = new CardPdfStatementParser(Issuer.DISCOVER).parse(fixture("discover_card_statement_synthetic.txt"));
        assertEquals("4242", s.accountLast4());
        assertEquals(LocalDate.of(2026, 12, 15), s.summary().periodStart());
        assertEquals(LocalDate.of(2027, 1, 14), s.summary().periodEnd());
        assertEquals(d("100.00"), s.summary().beginningBalance());
        assertEquals(d("152.75"), s.summary().endingBalance());
        assertEquals(d("165.25"), s.summary().purchasesTotal());
        assertEquals(d("112.50"), s.summary().creditsTotal());
        assertTrue(s.summary().liability());
        assertEquals(5, s.lines().size());
        // The payment is a transfer, the refund is income, the rest are purchases.
        assertEquals(Direction.TRANSFER_IN, s.lines().get(0).direction());
        assertEquals(d("100.00"), s.lines().get(0).amount());
        assertEquals(Direction.INCOME, s.lines().get(1).direction());
        assertEquals(Direction.EXPENSE, s.lines().get(2).direction());
        // A purchase dated just before the cycle starts keeps its own year; January rows get the later year.
        assertEquals(LocalDate.of(2026, 12, 13), s.lines().get(2).postedOn());
        assertEquals(LocalDate.of(2027, 1, 5), s.lines().get(4).postedOn());
        assertTrue(Verifier.verifySummary(s.lines(), s.summary()).isEmpty());
    }

    @Test
    void chaseCardReadsTwoDigitYearsAndPayment() throws Exception {
        ParsedStatement s = new CardPdfStatementParser(Issuer.CHASE_CARD).parse(fixture("chase_card_statement_synthetic.txt"));
        assertEquals("4242", s.accountLast4());
        assertEquals(LocalDate.of(2026, 12, 16), s.summary().periodStart());
        assertEquals(LocalDate.of(2027, 1, 15), s.summary().periodEnd());
        assertEquals(d("50.00"), s.summary().beginningBalance());
        assertEquals(d("42.34"), s.summary().endingBalance());
        assertEquals(3, s.lines().size());
        assertEquals(Direction.TRANSFER_IN, s.lines().get(0).direction());
        assertEquals(LocalDate.of(2027, 1, 2), s.lines().get(2).postedOn());
        assertTrue(Verifier.verifySummary(s.lines(), s.summary()).isEmpty());
    }

    @Test
    void citiReadsPostDateAndSplitsPaymentsFromCredits() throws Exception {
        ParsedStatement s = new CardPdfStatementParser(Issuer.CITI_COSTCO).parse(fixture("citi_costco_card_statement_synthetic.txt"));
        assertEquals("4242", s.accountLast4());
        assertEquals(d("85.00"), s.summary().creditsTotal());
        assertEquals(d("70.00"), s.summary().purchasesTotal());
        assertEquals(4, s.lines().size());
        assertEquals(LocalDate.of(2027, 3, 2), s.lines().get(0).postedOn());
        assertEquals(Direction.INCOME, s.lines().get(2).direction());
        assertEquals(Direction.TRANSFER_IN, s.lines().get(3).direction());
        assertTrue(Verifier.verifySummary(s.lines(), s.summary()).isEmpty());
    }

    @Test
    void aChangedAmountIsCaughtByTheTotals() throws Exception {
        String text = fixture("chase_card_statement_synthetic.txt").replace("12.34", "12.43");
        ParsedStatement s = new CardPdfStatementParser(Issuer.CHASE_CARD).parse(text);
        var issues = Verifier.verifySummary(s.lines(), s.summary());
        assertEquals(2, issues.size()); // new balance does not follow, and purchases total differs
        assertTrue(issues.get(0).message().contains("42.43"));
    }

    @Test
    void anUnreadableRowFailsTheWholeFileWithoutEchoingIt() throws Exception {
        String text = fixture("chase_card_statement_synthetic.txt")
                .replace("SAMPLE CAFE PLANO TX                                           12.34",
                        "SAMPLE CAFE PLANO TX                                           twelve");
        StatementParseException e = assertThrows(StatementParseException.class,
                () -> new CardPdfStatementParser(Issuer.CHASE_CARD).parse(text));
        assertTrue(e.getMessage().contains("1 transaction line"));
        assertTrue(!e.getMessage().contains("SAMPLE"));
    }

    @Test
    void aDifferentIssuersFileIsRejectedClearly() throws Exception {
        StatementParseException e = assertThrows(StatementParseException.class,
                () -> new CardPdfStatementParser(Issuer.CITI_COSTCO).parse(fixture("discover_card_statement_synthetic.txt")));
        assertTrue(e.getMessage().contains("billing period"));
    }

    @Test
    void aRowOutsideThePeriodFailsTheFile() throws Exception {
        String text = fixture("chase_card_statement_synthetic.txt").replace("01/02   SAMPLE CAFE", "05/02   SAMPLE CAFE");
        assertThrows(StatementParseException.class, () -> new CardPdfStatementParser(Issuer.CHASE_CARD).parse(text));
    }
}
