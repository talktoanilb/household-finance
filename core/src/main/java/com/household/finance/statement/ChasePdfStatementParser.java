package com.household.finance.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the text of a Chase checking statement PDF.
 * <p>
 * Layout it expects: a "Month D, YYYY through Month D, YYYY" period, an "Account Number:" line, a summary
 * with "Beginning Balance", "Ending Balance" and (optionally) "Deposits and Additions", and a
 * "TRANSACTION DETAIL" table of {@code MM/DD description amount balance} rows. Dates carry no year, so
 * the year is taken from the period. Amounts are signed (negative = money out).
 * <p>
 * It never skips anything silently: a row that looks like a transaction but cannot be read makes the
 * whole file fail with a plain message, and the caller then verifies the lines against the summary.
 * Only the last 4 digits of the account number are kept.
 */
public final class ChasePdfStatementParser {

    private static final String MONEY = "-?\\$?-?[\\d,]+\\.\\d{2}";
    private static final Pattern PERIOD = Pattern.compile(
            "([A-Z][a-z]+ \\d{1,2}, \\d{4})\\s+through\\s+([A-Z][a-z]+ \\d{1,2}, \\d{4})");
    private static final Pattern ACCOUNT = Pattern.compile("Account Number:\\s*(\\d{4,})");
    private static final Pattern TX = Pattern.compile(
            "^\\s*(\\d{2}/\\d{2})\\s+(.+?)\\s+(" + MONEY + ")\\s+(" + MONEY + ")\\s*$");
    private static final Pattern TX_START = Pattern.compile("^\\s*\\d{2}/\\d{2}\\s");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US);
    private static final int YEAR_TOLERANCE_DAYS = 7;

    public ParsedStatement parse(String text) {
        String[] lines = text.split("\\R");

        Matcher period = PERIOD.matcher(text);
        if (!period.find()) {
            throw new StatementParseException("could not find the statement period");
        }
        LocalDate start = date(period.group(1));
        LocalDate end = date(period.group(2));

        Matcher account = ACCOUNT.matcher(text);
        String last4 = null;
        if (account.find()) {
            String digits = account.group(1);
            last4 = digits.substring(digits.length() - 4);
        }

        BigDecimal beginning = firstAmountAfterLabel(lines, "Beginning Balance");
        BigDecimal ending = firstAmountAfterLabel(lines, "Ending Balance");
        if (beginning == null || ending == null) {
            throw new StatementParseException("could not find the beginning and ending balance");
        }
        BigDecimal deposits = firstAmountAfterLabel(lines, "Deposits and Additions");

        int tableStart = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("TRANSACTION DETAIL")) {
                tableStart = i;
                break;
            }
        }
        if (tableStart < 0) {
            throw new StatementParseException("could not find the transaction section");
        }

        Fingerprint fingerprint = new Fingerprint("CHASE_CHECKING");
        List<StatementLine> parsed = new ArrayList<>();
        int unreadable = 0;
        for (int i = tableStart; i < lines.length; i++) {
            Matcher m = TX.matcher(lines[i]);
            if (m.matches()) {
                BigDecimal signed = money(m.group(3));
                if (signed.signum() == 0) {
                    throw new StatementParseException("a transaction has a zero amount");
                }
                BigDecimal balance = money(m.group(4));
                LocalDate date = withYear(m.group(1), start, end);
                String description = m.group(2).trim().replaceAll("\\s+", " ");
                StatementLine.Direction direction =
                        signed.signum() < 0 ? StatementLine.Direction.EXPENSE : StatementLine.Direction.INCOME;
                parsed.add(new StatementLine(date, description, signed.abs(), direction, StatementLine.Status.POSTED,
                        fingerprint.next(date, description, signed, balance.toPlainString()), balance));
            } else if (TX_START.matcher(lines[i]).find()) {
                unreadable++;
            }
        }
        if (unreadable > 0) {
            throw new StatementParseException(unreadable + " transaction line(s) could not be read");
        }
        return new ParsedStatement(List.copyOf(parsed),
                new StatementSummary(start, end, beginning, ending, deposits), last4);
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text, LONG_DATE);
        } catch (java.time.DateTimeException e) {
            throw new StatementParseException("the statement period is not a valid date");
        }
    }

    /** The first line containing the label and ending in an amount, e.g. "Beginning Balance   $8,531.19". */
    private static BigDecimal firstAmountAfterLabel(String[] lines, String label) {
        Pattern p = Pattern.compile(Pattern.quote(label) + "\\s+(" + MONEY + ")\\s*$");
        for (String line : lines) {
            Matcher m = p.matcher(line.trim());
            if (m.find()) {
                return money(m.group(1));
            }
        }
        return null;
    }

    private static BigDecimal money(String text) {
        boolean negative = text.contains("-");
        BigDecimal value = new BigDecimal(text.replace("-", "").replace("$", "").replace(",", ""));
        return (negative ? value.negate() : value).setScale(2);
    }

    /** Picks the year from the period so December rows in a January statement get the earlier year. */
    private static LocalDate withYear(String monthDay, LocalDate start, LocalDate end) {
        int month = Integer.parseInt(monthDay.substring(0, 2));
        int day = Integer.parseInt(monthDay.substring(3, 5));
        for (int year = start.getYear(); year <= end.getYear(); year++) {
            LocalDate candidate;
            try {
                candidate = LocalDate.of(year, month, day);
            } catch (java.time.DateTimeException e) {
                continue;
            }
            if (!candidate.isBefore(start.minusDays(YEAR_TOLERANCE_DAYS))
                    && !candidate.isAfter(end.plusDays(YEAR_TOLERANCE_DAYS))) {
                return candidate;
            }
        }
        throw new StatementParseException("a transaction date falls outside the statement period");
    }
}
