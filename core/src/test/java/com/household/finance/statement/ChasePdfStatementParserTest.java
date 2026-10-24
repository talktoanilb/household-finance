package com.household.finance.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.household.finance.statement.StatementLine.Direction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Synthetic statement text in the same layout as a real Chase checking PDF (invented names and amounts).
 * Hand-checked numbers: beginning 1,000.00; deposits 2,000.00 + 2,000.00 + 0.05 = 4,000.05;
 * withdrawals 85.40 + 150.00 + 1,200.00 + 86.13 + 86.13 = 1,607.66; ending 1,000.00 + 4,000.05 - 1,607.66 = 3,392.39.
 */
class ChasePdfStatementParserTest {

    private final ChasePdfStatementParser parser = new ChasePdfStatementParser();

    static String fixtureText() throws Exception {
        try (var in = ChasePdfStatementParserTest.class.getResourceAsStream("/fixtures/chase_checking_statement_synthetic.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsPeriodSummaryAndLast4() throws Exception {
        ParsedStatement s = parser.parse(fixtureText());
        assertEquals(LocalDate.of(2026, 9, 22), s.summary().periodStart());
        assertEquals(LocalDate.of(2026, 10, 21), s.summary().periodEnd());
        assertEquals(new BigDecimal("1000.00"), s.summary().beginningBalance());
        assertEquals(new BigDecimal("3392.39"), s.summary().endingBalance());
        assertEquals(new BigDecimal("4000.05"), s.summary().depositsTotal());
        assertEquals("1234", s.accountLast4());
    }

    @Test
    void readsEveryTransactionRowAcrossPages() throws Exception {
        ParsedStatement s = parser.parse(fixtureText());
        assertEquals(8, s.lines().size());
        StatementLine first = s.lines().get(0);
        assertEquals(LocalDate.of(2026, 9, 23), first.postedOn());
        assertEquals("Test Utility Autopay Web ID: 1111111111", first.description());
        assertEquals(new BigDecimal("85.40"), first.amount());
        assertEquals(Direction.EXPENSE, first.direction());
        assertEquals(new BigDecimal("914.60"), first.balance());
        StatementLine rent = s.lines().get(3);
        assertEquals(new BigDecimal("1200.00"), rent.amount());
        assertEquals(new BigDecimal("1564.60"), rent.balance());
        StatementLine last = s.lines().get(7);
        assertEquals(Direction.INCOME, last.direction());
        assertEquals(new BigDecimal("0.05"), last.amount());
    }

    @Test
    void identicalSameDayLinesGetDistinctRefs() throws Exception {
        ParsedStatement s = parser.parse(fixtureText());
        assertEquals(s.lines().get(4).description(), s.lines().get(5).description());
        assertTrue(!s.lines().get(4).externalRef().equals(s.lines().get(5).externalRef()));
        assertEquals(8, (int) s.lines().stream().map(StatementLine::externalRef).distinct().count());
    }

    @Test
    void fullAccountNumberIsNeverKept() throws Exception {
        assertTrue(!parser.parse(fixtureText()).toString().contains("000000001234"));
    }

    @Test
    void parsedLinesReconcileWithTheSummary() throws Exception {
        ParsedStatement s = parser.parse(fixtureText());
        BigDecimal change = BigDecimal.ZERO;
        for (StatementLine l : s.lines()) {
            change = change.add(l.direction() == Direction.EXPENSE ? l.amount().negate() : l.amount());
        }
        assertEquals(s.summary().endingBalance(), s.summary().beginningBalance().add(change));
    }

    @Test
    void yearComesFromThePeriodAcrossNewYear() {
        String text = """
                December 21, 2026 through January 20, 2027
                Account Number: 000000005555
                Beginning Balance $100.00
                Ending Balance $90.00
                TRANSACTION DETAIL
                12/28   Synthetic Shop   -4.00   96.00
                01/05   Synthetic Shop   -6.00   90.00
                """;
        ParsedStatement s = parser.parse(text);
        assertEquals(LocalDate.of(2026, 12, 28), s.lines().get(0).postedOn());
        assertEquals(LocalDate.of(2027, 1, 5), s.lines().get(1).postedOn());
    }

    @Test
    void aRowThatCannotBeReadFailsTheWholeFileInsteadOfBeingSkipped() {
        String text = """
                September 22, 2026 through October 21, 2026
                Beginning Balance $100.00
                Ending Balance $90.00
                TRANSACTION DETAIL
                09/23   Synthetic Shop   -10.00   90.00
                09/24   Synthetic Shop that wrapped onto
                """;
        StatementParseException e = assertThrows(StatementParseException.class, () -> parser.parse(text));
        assertEquals("1 transaction line(s) could not be read", e.getMessage());
    }

    @Test
    void missingPieceGivesAClearMessage() {
        assertThrows(StatementParseException.class, () -> parser.parse("Beginning Balance $1.00\nEnding Balance $1.00\n"));
        assertThrows(StatementParseException.class, () -> parser.parse(
                "September 22, 2026 through October 21, 2026\nEnding Balance $1.00\nTRANSACTION DETAIL\n"));
        assertThrows(StatementParseException.class, () -> parser.parse(
                "September 22, 2026 through October 21, 2026\nBeginning Balance $1.00\nEnding Balance $1.00\n"));
    }

    @Test
    void aStatementWithNoTransactionsIsValid() {
        ParsedStatement s = parser.parse("""
                September 22, 2026 through October 21, 2026
                Beginning Balance $100.00
                Ending Balance $100.00
                TRANSACTION DETAIL
                """);
        assertEquals(0, s.lines().size());
    }

    @Test
    void aDateOutsideThePeriodIsRejected() {
        String text = """
                September 22, 2026 through October 21, 2026
                Beginning Balance $100.00
                Ending Balance $90.00
                TRANSACTION DETAIL
                03/05   Synthetic Shop   -10.00   90.00
                """;
        assertThrows(StatementParseException.class, () -> parser.parse(text));
    }

    @Test
    void sameTransactionFromCsvAndPdfGetsTheSameRef() throws Exception {
        String csv = "Details,Posting Date,Description,Amount,Type,Balance,Check or Slip #\n"
                + "DEBIT,09/23/2026,Test Utility Autopay Web ID: 1111111111,-85.40,ACH_DEBIT,914.60,\n";
        StatementLine fromCsv = new ChaseCheckingParser().parse(new java.io.StringReader(csv)).get(0);
        StatementLine fromPdf = parser.parse(fixtureText()).lines().get(0);
        assertEquals(fromCsv.externalRef(), fromPdf.externalRef());
    }
}
