package com.household.finance.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the text of a credit card statement PDF. One class, one small layout description per issuer.
 * <p>
 * Card statements print no running balance, so the checking is done on totals: the summary's previous balance,
 * purchases, payments and credits and new balance are read here and checked by the {@code Verifier}.
 * Lines carry a month/day without a year; the year is taken from the billing period (a December row in a
 * January cycle gets the earlier year). Positive amounts are purchases (EXPENSE); negative amounts are
 * payments (TRANSFER_IN, never counted as earnings) or refunds and other credits (INCOME).
 * <p>
 * Nothing is skipped silently: a row that starts with a date inside the transaction section but cannot be read
 * fails the whole file with a plain message. Only the last 4 digits of the account are kept.
 */
public final class CardPdfStatementParser {

    public enum Issuer { DISCOVER, CHASE_CARD, CITI_COSTCO }

    private static final String AMT = "([\\d,]+\\.\\d{2})";
    private static final String DATE = "(\\d{2}/\\d{2}/(?:\\d{4}|\\d{2}))";
    private static final int YEAR_TOLERANCE_DAYS = 14;

    /** What differs between issuers. */
    private record Layout(
            String format,
            Pattern period,
            Pattern last4,
            List<String> previousLabels,
            List<String> newLabels,
            List<String> purchaseLabels,
            List<String> creditLabels,
            Predicate<String> sectionStart,
            Predicate<String> sectionEnd,
            Pattern row,
            int dateGroup,
            int descGroup,
            int signGroup,
            int amountGroup) {}

    private static final Layout DISCOVER = new Layout("DISCOVER_CARD",
            Pattern.compile("OPEN TO CLOSE DATE:\\s*" + DATE + "\\s*-\\s*" + DATE),
            Pattern.compile("Card ending in (\\d{4})"),
            List.of("Previous Balance"), List.of("New Balance:"), List.of("Transactions"),
            List.of("Payments and Credits"),
            l -> l.matches(".*#\\d{4}:.*"),
            l -> l.contains("Fees and Interest Charged"),
            Pattern.compile("^\\s*(\\d{2}/\\d{2})\\s+(.+?)\\s+(-\\s*)?\\$" + AMT + "\\s*$"),
            1, 2, 3, 4);

    private static final Layout CHASE_CARD = new Layout("CHASE_CARD",
            Pattern.compile("Opening/Closing Date\\s*" + DATE + "\\s*-\\s*" + DATE),
            Pattern.compile("Account Number:\\s*(?:[Xx*]{4}\\s*){3}(\\d{4})"),
            List.of("Previous Balance"), List.of("New Balance"), List.of("Purchases"),
            List.of("Payment, Credits"),
            l -> l.contains("ACCOUNT ACTIVITY"),
            l -> l.contains("INTEREST CHARGES") || l.contains("Year-to-Date"),
            Pattern.compile("^\\s*(\\d{2}/\\d{2})\\s+(.+?)\\s+(-)?" + AMT + "\\s*$"),
            1, 2, 3, 4);

    private static final Layout CITI_COSTCO = new Layout("CITI_COSTCO_CARD",
            Pattern.compile("Billing Period:\\s*" + DATE + "\\s*-\\s*" + DATE),
            Pattern.compile("Account number ending in:\\s*(\\d{4})"),
            List.of("Previous balance"), List.of("New balance"), List.of("Purchases"),
            List.of("Payments", "Credits"),
            l -> l.contains("ACCOUNT SUMMARY"),
            l -> l.trim().equals("Fees Charged"),
            // sale date, post date, description, amount; the post date is the one kept
            Pattern.compile("^\\s*\\d{2}/\\d{2}\\s+(\\d{2}/\\d{2})\\s+(.+?)\\s+(-\\s*)?\\$" + AMT + "\\s*$"),
            1, 2, 3, 4);

    private static final Pattern ROW_START = Pattern.compile("^\\s*\\d{2}/\\d{2}\\s");

    private final Layout layout;

    public CardPdfStatementParser(Issuer issuer) {
        this.layout = switch (issuer) {
            case DISCOVER -> DISCOVER;
            case CHASE_CARD -> CHASE_CARD;
            case CITI_COSTCO -> CITI_COSTCO;
        };
    }

    public ParsedStatement parse(String text) {
        String[] lines = text.split("\\R");

        Matcher period = layout.period().matcher(text);
        if (!period.find()) {
            throw new StatementParseException("could not find the billing period");
        }
        LocalDate start = date(period.group(1));
        LocalDate end = date(period.group(2));
        if (end.isBefore(start)) {
            throw new StatementParseException("the billing period ends before it starts");
        }

        Matcher account = layout.last4().matcher(text);
        String last4 = account.find() ? account.group(1) : null;

        BigDecimal previous = first(lines, layout.previousLabels());
        BigDecimal newBalance = first(lines, layout.newLabels());
        if (previous == null || newBalance == null) {
            throw new StatementParseException("could not find the previous and new balance");
        }
        BigDecimal purchases = first(lines, layout.purchaseLabels());
        BigDecimal credits = null;
        for (String label : layout.creditLabels()) {
            BigDecimal part = first(lines, List.of(label));
            if (part != null) {
                credits = (credits == null ? BigDecimal.ZERO : credits).add(part.abs());
            }
        }

        int from = -1;
        for (int i = 0; i < lines.length; i++) {
            if (layout.sectionStart().test(lines[i])) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            throw new StatementParseException("could not find the transaction section");
        }

        Fingerprint fingerprint = new Fingerprint(layout.format());
        List<StatementLine> parsed = new ArrayList<>();
        int unreadable = 0;
        for (int i = from; i < lines.length; i++) {
            if (i > from && layout.sectionEnd().test(lines[i])) {
                break;
            }
            Matcher m = layout.row().matcher(lines[i]);
            if (m.matches()) {
                BigDecimal amount = new BigDecimal(m.group(layout.amountGroup()).replace(",", "")).setScale(2);
                if (amount.signum() == 0) {
                    throw new StatementParseException("a transaction has a zero amount");
                }
                boolean credit = m.group(layout.signGroup()) != null;
                String description = m.group(layout.descGroup()).trim().replaceAll("\\s+", " ");
                LocalDate posted = withYear(m.group(layout.dateGroup()), start, end);
                StatementLine.Direction direction;
                if (!credit) {
                    direction = StatementLine.Direction.EXPENSE;
                } else if (looksLikePayment(description)) {
                    direction = StatementLine.Direction.TRANSFER_IN;
                } else {
                    direction = StatementLine.Direction.INCOME;
                }
                BigDecimal signed = credit ? amount.negate() : amount;
                parsed.add(new StatementLine(posted, description, amount, direction, StatementLine.Status.POSTED,
                        fingerprint.next(posted, description, signed, null), null));
            } else if (ROW_START.matcher(lines[i]).find()) {
                unreadable++;
            }
        }
        if (unreadable > 0) {
            throw new StatementParseException(unreadable + " transaction line(s) could not be read");
        }
        return new ParsedStatement(List.copyOf(parsed),
                StatementSummary.card(start, end, previous, newBalance, purchases, credits), last4);
    }

    /** A negative line that pays the card (not a refund): "AUTOMATIC PAYMENT - THANK YOU", "AUTOPAY PYMT". */
    public static boolean looksLikePayment(String description) {
        String d = description.toUpperCase();
        return d.contains("PAYMENT") || d.contains("PYMT") || d.contains("AUTOPAY");
    }

    /** First line starting with one of the labels followed by an amount ("Payments and Credits - $16.80"). */
    private static BigDecimal first(String[] lines, List<String> labels) {
        for (String label : labels) {
            Pattern p = Pattern.compile("^" + Pattern.quote(label) + "\\s*([+-])?\\s*-?\\$?\\s*" + AMT);
            for (String line : lines) {
                Matcher m = p.matcher(line.trim());
                if (m.find()) {
                    BigDecimal value = new BigDecimal(m.group(2).replace(",", "")).setScale(2);
                    return "-".equals(m.group(1)) ? value.negate() : value;
                }
            }
        }
        return null;
    }

    private static LocalDate date(String text) {
        String[] p = text.split("/");
        int year = Integer.parseInt(p[2]);
        if (year < 100) {
            year += 2000;
        }
        try {
            return LocalDate.of(year, Integer.parseInt(p[0]), Integer.parseInt(p[1]));
        } catch (java.time.DateTimeException e) {
            throw new StatementParseException("the billing period is not a valid date");
        }
    }

    /** Year for a month/day: the one that lands within a couple of weeks of the billing period. */
    private static LocalDate withYear(String monthDay, LocalDate start, LocalDate end) {
        int month = Integer.parseInt(monthDay.substring(0, 2));
        int day = Integer.parseInt(monthDay.substring(3, 5));
        for (int year = start.getYear() - 1; year <= end.getYear(); year++) {
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
        throw new StatementParseException("a transaction date falls outside the billing period");
    }
}
