package com.household.finance.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.household.finance.statement.StatementLine.Direction;
import com.household.finance.statement.StatementLine.Status;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChaseCheckingParserTest {

    private final ChaseCheckingParser parser = new ChaseCheckingParser();

    private List<StatementLine> parseFixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/chase_checking_synthetic.csv")) {
            return parser.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void parsesFixtureLineByLine() throws Exception {
        List<StatementLine> lines = parseFixture();
        assertEquals(7, lines.size());

        StatementLine first = lines.get(0);
        assertEquals(LocalDate.of(2026, 10, 1), first.postedOn());
        assertEquals("SYNTHETIC POWER CO ONLINE PMT", first.description());
        assertEquals(new BigDecimal("142.37"), first.amount());
        assertEquals(Direction.EXPENSE, first.direction());
        assertEquals(Status.POSTED, first.status());
        assertEquals(new BigDecimal("3210.55"), first.balance());

        StatementLine payroll = lines.get(2);
        assertEquals(new BigDecimal("2480.00"), payroll.amount());
        assertEquals(Direction.INCOME, payroll.direction());
    }

    /** Hand-checked: expenses 142.37+15.49+86.13+86.13+54.20+118.00 = 502.32; one deposit of 2480.00. */
    @Test
    void totalsMatchHandCalculation() throws Exception {
        List<StatementLine> lines = parseFixture();
        BigDecimal expenses = lines.stream().filter(l -> l.direction() == Direction.EXPENSE)
                .map(StatementLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal income = lines.stream().filter(l -> l.direction() == Direction.INCOME)
                .map(StatementLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("502.32"), expenses);
        assertEquals(new BigDecimal("2480.00"), income);
    }

    @Test
    void amountsAreNeverNegative() throws Exception {
        assertTrue(parseFixture().stream().allMatch(l -> l.amount().signum() > 0));
    }

    @Test
    void identicalPurchasesOnDifferentDaysGetDistinctRefs() throws Exception {
        List<StatementLine> lines = parseFixture();
        // Lines 4 and 5 of the fixture: same vendor and amount (86.13), consecutive days.
        assertEquals(lines.get(3).amount(), lines.get(4).amount());
        assertEquals(7, new HashSet<>(lines.stream().map(StatementLine::externalRef).toList()).size());
    }

    @Test
    void reimportingTheSameFileGivesTheSameRefs() throws Exception {
        List<String> first = parseFixture().stream().map(StatementLine::externalRef).toList();
        List<String> second = parseFixture().stream().map(StatementLine::externalRef).toList();
        assertEquals(first, second);
    }

    @Test
    void sameDaySameAmountSameBalanceStillGetDistinctRefs() throws Exception {
        String csv = """
                Details,Posting Date,Description,Amount,Type,Balance,Check or Slip #
                DEBIT,10/04/2026,SYNTHETIC COFFEE,-4.50,DEBIT_CARD,100.00,
                DEBIT,10/04/2026,SYNTHETIC COFFEE,-4.50,DEBIT_CARD,100.00,
                """;
        List<StatementLine> lines = parser.parse(new StringReader(csv));
        assertEquals(2, lines.size());
        assertTrue(!lines.get(0).externalRef().equals(lines.get(1).externalRef()));
    }

    @Test
    void rejectsUnexpectedHeader() {
        String csv = "Date,Memo,Value\n10/01/2026,X,1.00\n";
        StatementParseException e = assertThrows(StatementParseException.class, () -> parser.parse(new StringReader(csv)));
        assertTrue(e.getMessage().startsWith("line 1:"));
    }

    @Test
    void rejectsEmptyFile() {
        assertThrows(StatementParseException.class, () -> parser.parse(new StringReader("")));
    }

    @Test
    void rejectsBadDateAmountAndZero() {
        String head = "Details,Posting Date,Description,Amount,Type,Balance,Check or Slip #\n";
        assertThrows(StatementParseException.class,
                () -> parser.parse(new StringReader(head + "DEBIT,13/45/2026,X,-1.00,T,1.00,\n")));
        assertThrows(StatementParseException.class,
                () -> parser.parse(new StringReader(head + "DEBIT,10/01/2026,X,abc,T,1.00,\n")));
        assertThrows(StatementParseException.class,
                () -> parser.parse(new StringReader(head + "DEBIT,10/01/2026,X,-1.005,T,1.00,\n")));
        assertThrows(StatementParseException.class,
                () -> parser.parse(new StringReader(head + "DEBIT,10/01/2026,X,0.00,T,1.00,\n")));
    }

    @Test
    void errorMessagesNeverContainFileContents() {
        String csv = "Details,Posting Date,Description,Amount,Type,Balance,Check or Slip #\n"
                + "DEBIT,10/01/2026,SECRET MERCHANT 9999,not-a-number,T,1.00,\n";
        StatementParseException e = assertThrows(StatementParseException.class, () -> parser.parse(new StringReader(csv)));
        assertEquals("line 2: invalid amount", e.getMessage());
    }

    @Test
    void toleratesBomCrlfAndBlankTrailingLines() throws Exception {
        String csv = "﻿Details,Posting Date,Description,Amount,Type,Balance,Check or Slip #\r\n"
                + "DEBIT,10/01/2026,SYNTHETIC SHOP,-9.99,DEBIT_CARD,50.00,\r\n\r\n";
        assertEquals(1, parser.parse(new StringReader(csv)).size());
    }

    @Test
    void handlesQuotedDescriptionsWithCommasAndQuotes() throws Exception {
        String csv = "Details,Posting Date,Description,Amount,Type,Balance,Check or Slip #\n"
                + "DEBIT,10/01/2026,\"SYNTHETIC SHOP, INC \"\"#5\"\"\",-9.99,DEBIT_CARD,50.00,\n";
        assertEquals("SYNTHETIC SHOP, INC \"#5\"", parser.parse(new StringReader(csv)).get(0).description());
    }
}
