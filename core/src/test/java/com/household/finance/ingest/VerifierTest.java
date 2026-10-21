package com.household.finance.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.household.finance.statement.ChaseCheckingParser;
import com.household.finance.statement.StatementLine;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerifierTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private List<StatementLine> fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/chase_checking_synthetic.csv")) {
            return new ChaseCheckingParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private List<StatementLine> parse(String body) throws Exception {
        String head = "Details,Posting Date,Description,Amount,Type,Balance,Check or Slip #\n";
        return new ChaseCheckingParser().parse(new StringReader(head + body));
    }

    /** Hand-checked: 3210.55-15.49=3195.06, +2480.00=5675.06, -86.13=5588.93, -86.13=5502.80, -54.20=5448.60, -118.00=5330.60. */
    @Test
    void cleanFixtureHasNoIssues() throws Exception {
        assertEquals(List.of(), Verifier.verify(fixture(), TODAY));
    }

    @Test
    void newestFirstOrderAlsoPasses() throws Exception {
        List<StatementLine> reversed = new ArrayList<>(fixture());
        Collections.reverse(reversed);
        assertEquals(List.of(), Verifier.verify(reversed, TODAY));
    }

    @Test
    void wrongLastBalanceRaisesOneQuestionOnThatLine() throws Exception {
        List<StatementLine> lines = parse("""
                DEBIT,10/01/2026,SYNTHETIC A,-10.00,T,100.00,
                DEBIT,10/02/2026,SYNTHETIC B,-10.00,T,90.00,
                DEBIT,10/03/2026,SYNTHETIC C,-10.00,T,70.00,
                """);
        List<Issue> issues = Verifier.verify(lines, TODAY);
        assertEquals(1, issues.size());
        assertEquals(3, issues.get(0).lineNo());
        assertEquals(Issue.Kind.BALANCE_MISMATCH, issues.get(0).kind());
        assertTrue(issues.get(0).message().contains("80.00"));
        assertTrue(issues.get(0).message().contains("70.00"));
    }

    @Test
    void messagesNeverContainDescriptions() throws Exception {
        List<StatementLine> lines = parse("""
                DEBIT,10/01/2026,SECRET VENDOR,-10.00,T,100.00,
                DEBIT,10/02/2026,SECRET VENDOR,-10.00,T,50.00,
                """);
        for (Issue issue : Verifier.verify(lines, TODAY)) {
            assertTrue(!issue.message().contains("SECRET"));
        }
    }

    @Test
    void manyMismatchesCollapseToOneWholeFileQuestion() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            body.append("DEBIT,10/0").append(i).append("/2026,SYNTHETIC X,-5.00,T,").append(1000 - i * 7).append(".00,\n");
        }
        List<Issue> issues = Verifier.verify(parse(body.toString()), TODAY);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).wholeFile());
    }

    @Test
    void linesWithoutBalancesAreNotBalanceChecked() throws Exception {
        List<StatementLine> lines = parse("""
                DEBIT,10/01/2026,SYNTHETIC A,-10.00,T,,
                DEBIT,10/02/2026,SYNTHETIC B,-10.00,T,,
                """);
        assertEquals(List.of(), Verifier.verify(lines, TODAY));
    }

    @Test
    void futureAndVeryOldDatesAreFlagged() throws Exception {
        List<StatementLine> lines = parse("""
                DEBIT,12/25/2026,SYNTHETIC FUTURE,-1.00,T,,
                DEBIT,01/05/2015,SYNTHETIC OLD,-1.00,T,,
                DEBIT,10/21/2026,SYNTHETIC TOMORROW,-1.00,T,,
                """);
        List<Issue> issues = Verifier.verify(lines, TODAY);
        assertEquals(2, issues.size());
        assertEquals(1, issues.get(0).lineNo());
        assertEquals(2, issues.get(1).lineNo());
        assertEquals(Issue.Kind.DATE_IMPLAUSIBLE, issues.get(0).kind());
    }
}
