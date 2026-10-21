package com.household.finance.ingest;

import com.household.finance.statement.StatementLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Java-side checks on extracted lines. The extractor proposes; this decides what needs a human.
 * Pure: no database, no clock (the caller passes today's date).
 * <p>
 * Checks: (1) the running balance follows from line to line, in whichever order the file is sorted
 * (banks export oldest-first or newest-first); (2) dates are not in the future or implausibly old.
 * If the balance fails on many lines the file is probably partial or a different format, so one
 * whole-file question is raised instead of one per line.
 */
public final class Verifier {

    /** More balance mismatches than this collapse into a single whole-file question. */
    static final int MAX_LINE_QUESTIONS = 5;
    static final int MAX_AGE_YEARS = 7;

    private Verifier() {}

    public static List<Issue> verify(List<StatementLine> lines, LocalDate today) {
        List<Issue> issues = new ArrayList<>();
        issues.addAll(balanceIssues(lines));
        issues.addAll(dateIssues(lines, today));
        return issues;
    }

    private static BigDecimal signed(StatementLine line) {
        return line.direction() == StatementLine.Direction.EXPENSE ? line.amount().negate() : line.amount();
    }

    private static List<Issue> balanceIssues(List<StatementLine> lines) {
        int checked = 0;
        int okOldestFirst = 0;
        int okNewestFirst = 0;
        for (int i = 1; i < lines.size(); i++) {
            StatementLine prev = lines.get(i - 1);
            StatementLine cur = lines.get(i);
            if (prev.balance() == null || cur.balance() == null) {
                continue;
            }
            checked++;
            if (prev.balance().add(signed(cur)).compareTo(cur.balance()) == 0) {
                okOldestFirst++;
            }
            if (cur.balance().add(signed(prev)).compareTo(prev.balance()) == 0) {
                okNewestFirst++;
            }
        }
        if (checked == 0) {
            return List.of();
        }
        boolean oldestFirst = okOldestFirst >= okNewestFirst;

        List<Issue> mismatches = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            StatementLine prev = lines.get(i - 1);
            StatementLine cur = lines.get(i);
            if (prev.balance() == null || cur.balance() == null) {
                continue;
            }
            // The line whose balance does not follow from its neighbour.
            StatementLine suspect = oldestFirst ? cur : prev;
            BigDecimal expected = oldestFirst
                    ? prev.balance().add(signed(cur))
                    : cur.balance().add(signed(prev));
            if (expected.compareTo(suspect.balance()) != 0) {
                int lineNo = oldestFirst ? i + 1 : i;
                mismatches.add(new Issue(lineNo, Issue.Kind.BALANCE_MISMATCH,
                        "Line " + lineNo + " (" + suspect.postedOn() + "): the running balance does not follow from the"
                                + " neighbouring line. Expected " + expected.toPlainString() + " but the file says "
                                + suspect.balance().toPlainString() + ". An amount may have been misread or a line may be missing."));
            }
        }
        if (mismatches.size() > MAX_LINE_QUESTIONS) {
            return List.of(new Issue(0, Issue.Kind.BALANCE_MISMATCH,
                    "The running balance does not add up on " + mismatches.size() + " of " + checked
                            + " checked lines. This file may be incomplete or in a different format than expected."));
        }
        return mismatches;
    }

    private static List<Issue> dateIssues(List<StatementLine> lines, LocalDate today) {
        List<Issue> issues = new ArrayList<>();
        LocalDate latestOk = today.plusDays(1);
        LocalDate earliestOk = today.minusYears(MAX_AGE_YEARS);
        for (int i = 0; i < lines.size(); i++) {
            LocalDate date = lines.get(i).postedOn();
            if (date.isAfter(latestOk)) {
                issues.add(new Issue(i + 1, Issue.Kind.DATE_IMPLAUSIBLE,
                        "Line " + (i + 1) + ": the date " + date + " is in the future."));
            } else if (date.isBefore(earliestOk)) {
                issues.add(new Issue(i + 1, Issue.Kind.DATE_IMPLAUSIBLE,
                        "Line " + (i + 1) + ": the date " + date + " is more than " + MAX_AGE_YEARS + " years ago."));
            }
        }
        return issues;
    }
}
