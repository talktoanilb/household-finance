package com.household.finance.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.household.finance.statement.StatementLine.Direction;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Layout learning on invented files shaped like other banks' exports: one with a summary above the column names and
 * a running balance, one with no header row at all, one with separate debit and credit columns, and a card file
 * where every amount is positive.
 */
class LearnedCsvTest {

    static final String WITH_PREAMBLE = """
            Description,,Summary Amt.
            Beginning balance as of 10/01/2026,,"1,000.00"
            Total credits,,"2,000.00"
            Total debits,,"-165.20"
            Ending balance as of 10/31/2026,,"2,834.80"

            Date,Description,Amount,Running Bal.
            10/01/2026,Beginning balance as of 10/01/2026,,"1,000.00"
            10/03/2026,GROCERY MART #12 PLANO TX,-45.20,954.80
            10/05/2026,PAYROLL ACME INC,"2,000.00","2,954.80"
            10/09/2026,ELECTRIC CO BILL PAY,-120.00,"2,834.80"
            """;

    static final String NO_HEADER = """
            "10/01/2026","-45.20","*","","GROCERY MART PLANO TX"
            "10/02/2026","2000.00","*","","PAYROLL ACME INC"
            "10/04/2026","-120.00","*","","ELECTRIC CO BILL PAY"
            """;

    static final String DEBIT_CREDIT = """
            Transaction Date,Posted Date,Description,Debit,Credit
            2026-10-02,2026-10-03,GROCERY MART PLANO TX,45.20,
            2026-10-05,2026-10-06,AUTOPAY PAYMENT THANK YOU,,500.00
            2026-10-08,2026-10-09,HARDWARE DEPOT FRISCO TX,99.75,
            """;

    static final String POSITIVE_ONLY = """
            Date,Description,Amount
            2026-10-02,GROCERY MART PLANO TX,45.20
            2026-10-08,HARDWARE DEPOT FRISCO TX,99.75
            """;

    private static LayoutProposal detect(String csv) throws Exception {
        return CsvLayoutDetector.detect(new StringReader(csv));
    }

    private static List<StatementLine> read(String csv, LayoutProposal p, boolean card) throws Exception {
        return new LearnedCsvParser(p.layout(), card).parse(new StringReader(csv));
    }

    @Test
    void summaryAboveTheHeaderAndABalanceRowAreHandled() throws Exception {
        LayoutProposal p = detect(WITH_PREAMBLE);
        assertTrue(p.complete());
        CsvLayout l = p.layout();
        assertEquals(CsvLayout.AmountMode.SINGLE, l.mode());
        assertEquals(0, l.dateCol());
        assertEquals(1, l.descCol());
        assertEquals(2, l.amountCol());
        assertEquals(3, l.balanceCol());
        assertTrue(l.outIsNegative());
        List<StatementLine> lines = read(WITH_PREAMBLE, p, false);
        assertEquals(3, lines.size());
        assertEquals(Direction.EXPENSE, lines.get(0).direction());
        assertEquals(new BigDecimal("45.20"), lines.get(0).amount());
        assertEquals(LocalDate.of(2026, 10, 3), lines.get(0).postedOn());
        assertEquals(Direction.INCOME, lines.get(1).direction());
        assertEquals(new BigDecimal("2000.00"), lines.get(1).amount());
        assertEquals(new BigDecimal("2834.80"), lines.get(2).balance());
        assertEquals(3, p.preview().size());
        assertTrue(p.questions().stream().anyMatch(q -> q.contains("ignored")));
        assertEquals("date|description|amount|running bal.", l.fingerprint());
    }

    @Test
    void aFileWithNoHeaderRowIsReadByItsShape() throws Exception {
        LayoutProposal p = detect(NO_HEADER);
        assertTrue(p.complete());
        assertEquals("noheader:5", p.layout().fingerprint());
        assertEquals(4, p.layout().descCol());
        assertEquals(1, p.layout().amountCol());
        List<StatementLine> lines = read(NO_HEADER, p, false);
        assertEquals(3, lines.size());
        assertEquals("PAYROLL ACME INC", lines.get(1).description());
        assertEquals(Direction.INCOME, lines.get(1).direction());
    }

    @Test
    void debitAndCreditColumnsPickThePostedDateAndAskAboutIt() throws Exception {
        LayoutProposal p = detect(DEBIT_CREDIT);
        assertTrue(p.complete());
        assertEquals(CsvLayout.AmountMode.DEBIT_CREDIT, p.layout().mode());
        assertEquals(1, p.layout().dateCol()); // "Posted Date"
        assertEquals(3, p.layout().debitCol());
        assertEquals(4, p.layout().creditCol());
        assertTrue(p.questions().stream().anyMatch(q -> q.contains("Two columns look like dates")));
        List<StatementLine> bank = read(DEBIT_CREDIT, p, false);
        assertEquals(Direction.INCOME, bank.get(1).direction());
        // On a credit card account the payment is a transfer, not earnings.
        List<StatementLine> card = read(DEBIT_CREDIT, p, true);
        assertEquals(Direction.TRANSFER_IN, card.get(1).direction());
        assertEquals(Direction.EXPENSE, card.get(2).direction());
    }

    @Test
    void anAllPositiveFileAsksWhatPositiveMeans() throws Exception {
        LayoutProposal p = detect(POSITIVE_ONLY);
        assertTrue(p.complete());
        assertFalse(p.layout().outIsNegative());
        assertTrue(p.questions().stream().anyMatch(q -> q.contains("Every amount is positive")));
        assertEquals(Direction.EXPENSE, read(POSITIVE_ONLY, p, true).get(0).direction());
    }

    @Test
    void ambiguousDatesAreFlaggedAndClearOnesAreNot() throws Exception {
        LayoutProposal ambiguous = detect("Date,Description,Amount\n03/04/2026,SHOP ONE PLANO,-5.00\n05/06/2026,SHOP TWO PLANO,-6.00\n");
        assertTrue(ambiguous.questions().stream().anyMatch(q -> q.contains("month first or day first")));
        LayoutProposal clear = detect("Date,Description,Amount\n03/04/2026,SHOP ONE PLANO,-5.00\n05/16/2026,SHOP TWO PLANO,-6.00\n");
        assertFalse(clear.questions().stream().anyMatch(q -> q.contains("month first or day first")));
        assertEquals("M/d/uuuu", clear.layout().dateFormat());
    }

    @Test
    void whenItCannotDecideItSaysSoAndProposesNothing() throws Exception {
        LayoutProposal none = detect("Name,Notes\nAlice,hello\n");
        assertFalse(none.complete());
        assertEquals(null, none.layout());
        LayoutProposal twoNumbers = detect("10/01/2026,GROCERY MART PLANO,-45.20,954.80\n10/02/2026,PAYROLL ACME INC,2000.00,2954.80\n");
        assertFalse(twoNumbers.complete());
        assertTrue(twoNumbers.questions().get(0).contains("running balance"));
    }

    @Test
    void aChangedHeaderIsADifferentShape() throws Exception {
        String renamed = WITH_PREAMBLE.replace("Running Bal.", "Balance");
        assertNotEquals(CsvLayoutDetector.fingerprint(new StringReader(WITH_PREAMBLE)),
                CsvLayoutDetector.fingerprint(new StringReader(renamed)));
        // Same shape every month: a new month's file has the same fingerprint.
        assertEquals(CsvLayoutDetector.fingerprint(new StringReader(WITH_PREAMBLE)),
                CsvLayoutDetector.fingerprint(new StringReader(WITH_PREAMBLE.replace("45.20", "12.00"))));
    }

    @Test
    void anUnreadableRowFailsTheFileWithoutEchoingIt() throws Exception {
        LayoutProposal p = detect(NO_HEADER);
        String broken = NO_HEADER + "\"10/05/2026\",\"abc\",\"*\",\"\",\"SECRET SHOP\"\n";
        StatementParseException e = assertThrows(StatementParseException.class, () -> read(broken, p, false));
        assertTrue(e.getMessage().startsWith("line 4"));
        assertFalse(e.getMessage().contains("SECRET"));
    }

    @Test
    void amountStylesAreParsed() {
        assertEquals(new BigDecimal("-1234.50"), CsvLayoutDetector.parseMoney("-$1,234.50"));
        assertEquals(new BigDecimal("-45.20"), CsvLayoutDetector.parseMoney("(45.20)"));
        assertEquals(new BigDecimal("-45.20"), CsvLayoutDetector.parseMoney("45.20-"));
        assertEquals(new BigDecimal("45.20"), CsvLayoutDetector.parseMoney("+45.20"));
        assertEquals(null, CsvLayoutDetector.parseMoney("12.345"));
        assertEquals(null, CsvLayoutDetector.parseMoney("abc"));
    }

    @Test
    void identicalLinesGetDistinctFingerprints() throws Exception {
        String twice = "Date,Description,Amount\n10/01/2026,COFFEE SHOP PLANO,-4.50\n10/01/2026,COFFEE SHOP PLANO,-4.50\n";
        List<StatementLine> lines = read(twice, detect(twice), false);
        assertEquals(2, lines.size());
        assertNotEquals(lines.get(0).externalRef(), lines.get(1).externalRef());
    }
}
