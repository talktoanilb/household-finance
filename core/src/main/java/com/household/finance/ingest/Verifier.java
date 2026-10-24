package com.household.finance.ingest;

import com.household.finance.statement.StatementLine;
import com.household.finance.statement.StatementSummary;
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

    /**
     * Checks the lines against the totals the bank printed: beginning balance plus every line must equal the
     * ending balance, the printed deposits total (when there is one) must equal the deposit lines, and the
     * first line's balance must follow from the beginning balance. Statement lines are chronological.
     */
    public static List<Issue> verifySummary(List<StatementLine> lines, StatementSummary summary) {
        if (summary.liability()) {
            return verifyCardSummary(lines, summary);
        }
        List<Issue> issues = new ArrayList<>();
        BigDecimal change = BigDecimal.ZERO;
        BigDecimal deposits = BigDecimal.ZERO;
        for (StatementLine line : lines) {
            change = change.add(signed(line));
            if (signed(line).signum() > 0) {
                deposits = deposits.add(line.amount());
            }
        }
        BigDecimal expectedEnding = summary.beginningBalance().add(change);
        if (expectedEnding.compareTo(summary.endingBalance()) != 0) {
            issues.add(new Issue(0, Issue.Kind.SUMMARY_MISMATCH,
                    "The lines add up to a change of " + change.toPlainString() + ", so the statement should end at "
                            + expectedEnding.toPlainString() + ", but it says " + summary.endingBalance().toPlainString()
                            + ". A line may be missing or misread."));
        }
        if (summary.depositsTotal() != null && deposits.compareTo(summary.depositsTotal()) != 0) {
            issues.add(new Issue(0, Issue.Kind.SUMMARY_MISMATCH,
                    "The statement says deposits total " + summary.depositsTotal().toPlainString()
                            + " but the deposit lines total " + deposits.toPlainString() + "."));
        }
        if (!lines.isEmpty() && lines.get(0).balance() != null) {
            StatementLine first = lines.get(0);
            BigDecimal expected = summary.beginningBalance().add(signed(first));
            if (expected.compareTo(first.balance()) != 0) {
                issues.add(new Issue(1, Issue.Kind.BALANCE_MISMATCH,
                        "Line 1 (" + first.postedOn() + "): the balance does not follow from the statement's beginning balance. Expected "
                                + expected.toPlainString() + " but the file says " + first.balance().toPlainString() + "."));
            }
        }
        return issues;
    }

    /** Effect on a bank balance (money held): out is negative. */
    private static BigDecimal signed(StatementLine line) {
        boolean out = line.direction() == StatementLine.Direction.EXPENSE
                || line.direction() == StatementLine.Direction.TRANSFER_OUT;
        return out ? line.amount().negate() : line.amount();
    }

    /**
     * Credit-card statements carry no running balance, so the only checks are the printed totals:
     * previous balance + purchases - payments and credits = new balance, and each printed total against its lines.
     * Purchases are EXPENSE lines; payments (TRANSFER_IN) and refunds or credits (INCOME) reduce what is owed.
     */
    private static List<Issue> verifyCardSummary(List<StatementLine> lines, StatementSummary summary) {
        List<Issue> issues = new ArrayList<>();
        BigDecimal purchases = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (StatementLine line : lines) {
            if (line.direction() == StatementLine.Direction.EXPENSE) {
                purchases = purchases.add(line.amount());
            } else {
                credits = credits.add(line.amount());
            }
        }
        BigDecimal expectedNew = summary.beginningBalance().add(purchases).subtract(credits);
        if (expectedNew.compareTo(summary.endingBalance()) != 0) {
            issues.add(new Issue(0, Issue.Kind.SUMMARY_MISMATCH,
                    "Previous balance " + summary.beginningBalance().toPlainString() + " plus the purchases "
                            + purchases.toPlainString() + " minus the payments and credits " + credits.toPlainString()
                            + " should give a new balance of " + expectedNew.toPlainString() + ", but the statement says "
                            + summary.endingBalance().toPlainString() + ". A line, a fee or interest charge may be"
                            + " missing or misread."));
        }
        if (summary.purchasesTotal() != null && purchases.compareTo(summary.purchasesTotal()) != 0) {
            issues.add(new Issue(0, Issue.Kind.SUMMARY_MISMATCH,
                    "The statement says purchases total " + summary.purchasesTotal().toPlainString()
                            + " but the purchase lines total " + purchases.toPlainString() + "."));
        }
        if (summary.creditsTotal() != null && credits.compareTo(summary.creditsTotal()) != 0) {
            issues.add(new Issue(0, Issue.Kind.SUMMARY_MISMATCH,
                    "The statement says payments and credits total " + summary.creditsTotal().toPlainString()
                            + " but those lines total " + credits.toPlainString() + "."));
        }
        return issues;
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
