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
import java.util.List;
import org.junit.jupiter.api.Test;

class CitiCostcoVisaParserTest {

    private final CitiCostcoVisaParser parser = new CitiCostcoVisaParser();
    private static final String HEAD = "Status,Date,Description,Debit,Credit,Member Name\n";

    private List<StatementLine> parseFixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/citi_costco_visa_synthetic.csv")) {
            return parser.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void parsesFixtureLineByLine() throws Exception {
        List<StatementLine> lines = parseFixture();
        assertEquals(5, lines.size());

        StatementLine first = lines.get(0);
        assertEquals(LocalDate.of(2026, 10, 2), first.postedOn());
        assertEquals(new BigDecimal("213.45"), first.amount());
        assertEquals(Direction.EXPENSE, first.direction());
        assertEquals(Status.POSTED, first.status());

        StatementLine refund = lines.get(3);
        assertEquals(new BigDecimal("20.00"), refund.amount());
        assertEquals(Direction.INCOME, refund.direction());

        assertEquals(Status.PENDING, lines.get(4).status());
    }

    /** Hand-checked: charges 213.45+15.49+24.10+38.75 = 291.79; one refund of 20.00. */
    @Test
    void totalsMatchHandCalculation() throws Exception {
        List<StatementLine> lines = parseFixture();
        BigDecimal charges = lines.stream().filter(l -> l.direction() == Direction.EXPENSE)
                .map(StatementLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("291.79"), charges);
    }

    @Test
    void sameDayIdenticalChargesGetDistinctStableRefs() throws Exception {
        String csv = HEAD
                + "Cleared,10/02/2026,SYNTHETIC GAS,40.00,,TEST MEMBER\n"
                + "Cleared,10/02/2026,SYNTHETIC GAS,40.00,,TEST MEMBER\n";
        List<StatementLine> a = parser.parse(new StringReader(csv));
        List<StatementLine> b = parser.parse(new StringReader(csv));
        assertTrue(!a.get(0).externalRef().equals(a.get(1).externalRef()));
        assertEquals(a.get(0).externalRef(), b.get(0).externalRef());
        assertEquals(a.get(1).externalRef(), b.get(1).externalRef());
    }

    @Test
    void aChargeAndAnEqualRefundDoNotCollide() throws Exception {
        String csv = HEAD
                + "Cleared,10/02/2026,SYNTHETIC SHOP,20.00,,TEST MEMBER\n"
                + "Cleared,10/02/2026,SYNTHETIC SHOP,,20.00,TEST MEMBER\n";
        List<StatementLine> lines = parser.parse(new StringReader(csv));
        assertTrue(!lines.get(0).externalRef().equals(lines.get(1).externalRef()));
    }

    @Test
    void rejectsBothOrNeitherDebitAndCredit() {
        assertThrows(StatementParseException.class, () -> parser.parse(
                new StringReader(HEAD + "Cleared,10/02/2026,X,1.00,1.00,M\n")));
        assertThrows(StatementParseException.class, () -> parser.parse(
                new StringReader(HEAD + "Cleared,10/02/2026,X,,,M\n")));
    }

    @Test
    void rejectsUnknownStatusBadDateAndNegativeAmount() {
        assertThrows(StatementParseException.class, () -> parser.parse(
                new StringReader(HEAD + "Declined,10/02/2026,X,1.00,,M\n")));
        assertThrows(StatementParseException.class, () -> parser.parse(
                new StringReader(HEAD + "Cleared,2026-10-02,X,1.00,,M\n")));
        assertThrows(StatementParseException.class, () -> parser.parse(
                new StringReader(HEAD + "Cleared,10/02/2026,X,-1.00,,M\n")));
    }

    @Test
    void rejectsUnexpectedHeader() {
        assertThrows(StatementParseException.class, () -> parser.parse(
                new StringReader("Date,Amount\n10/01/2026,1.00\n")));
    }

    @Test
    void memberNameIsNotCarriedIntoTheResult() throws Exception {
        for (StatementLine line : parseFixture()) {
            assertTrue(!line.toString().contains("TEST MEMBER"));
        }
    }
}
