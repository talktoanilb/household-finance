package com.household.finance.statement;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a CSV with a layout the user confirmed ({@link CsvLayout}). Lines above the first transaction (title,
 * summary, column names) are skipped; after that every row must be readable or the whole file fails with the line
 * number and a reason, never the row's contents.
 * <p>
 * On a credit card account a negative or credit line that looks like a payment becomes a transfer (not earnings).
 */
public final class LearnedCsvParser implements StatementParser {

    private static final String FORMAT_KEY = "LEARNED_CSV";

    private final CsvLayout layout;
    private final boolean creditCard;
    private final DateTimeFormatter dateFormat;

    public LearnedCsvParser(CsvLayout layout, boolean creditCard) {
        this.layout = layout;
        this.creditCard = creditCard;
        this.dateFormat = DateTimeFormatter.ofPattern(layout.dateFormat(), Locale.US).withResolverStyle(ResolverStyle.STRICT);
    }

    @Override
    public List<StatementLine> parse(Reader csv) throws IOException {
        return parseRows(CsvReader.read(csv));
    }

    List<StatementLine> parseRows(List<CsvReader.Row> rows) {
        Fingerprint fingerprint = new Fingerprint(FORMAT_KEY);
        List<StatementLine> out = new ArrayList<>();
        boolean started = false;
        for (CsvReader.Row row : rows) {
            if (!started) {
                if (!startsData(row)) {
                    continue;
                }
                started = true;
            }
            StatementLine line = line(row, fingerprint);
            if (line != null) {
                out.add(line);
            }
        }
        if (out.isEmpty()) {
            throw new StatementParseException("no transaction rows found");
        }
        return List.copyOf(out);
    }

    private boolean startsData(CsvReader.Row row) {
        return cell(row, layout.dateCol()) != null
                && CsvLayoutDetector.parseDate(cell(row, layout.dateCol()), dateFormat) != null;
    }

    private StatementLine line(CsvReader.Row row, Fingerprint fingerprint) {
        String rawDate = cell(row, layout.dateCol());
        LocalDate date = rawDate == null ? null : CsvLayoutDetector.parseDate(rawDate, dateFormat);
        if (date == null) {
            throw StatementParseException.atLine(row.lineNumber(), "the date could not be read");
        }
        String description = cell(row, layout.descCol());
        if (description == null) {
            throw StatementParseException.atLine(row.lineNumber(), "description is empty");
        }
        description = description.replaceAll("\\s+", " ");

        BigDecimal signed;           // effect on the account's own side: out is negative
        StatementLine.Direction direction;
        BigDecimal amount;
        if (layout.mode() == CsvLayout.AmountMode.SINGLE) {
            BigDecimal v = money(row, layout.amountCol());
            if (v == null && cell(row, layout.amountCol()) == null && looksLikeBalanceRow(description)) {
                return null; // "Beginning balance" rows carry no transaction
            }
            if (v == null) {
                throw StatementParseException.atLine(row.lineNumber(), "the amount could not be read");
            }
            if (v.signum() == 0) {
                throw StatementParseException.atLine(row.lineNumber(), "the amount is zero");
            }
            boolean out = layout.outIsNegative() ? v.signum() < 0 : v.signum() > 0;
            amount = v.abs();
            direction = out ? StatementLine.Direction.EXPENSE : StatementLine.Direction.INCOME;
        } else {
            BigDecimal debit = money(row, layout.debitCol());
            BigDecimal credit = money(row, layout.creditCol());
            if ((debit == null) == (credit == null)) {
                throw StatementParseException.atLine(row.lineNumber(),
                        "exactly one of the money-out and money-in columns must have an amount");
            }
            BigDecimal v = debit != null ? debit : credit;
            if (v.signum() == 0) {
                throw StatementParseException.atLine(row.lineNumber(), "the amount is zero");
            }
            amount = v.abs();
            direction = debit != null ? StatementLine.Direction.EXPENSE : StatementLine.Direction.INCOME;
        }
        if (creditCard && direction == StatementLine.Direction.INCOME && CardPdfStatementParser.looksLikePayment(description)) {
            direction = StatementLine.Direction.TRANSFER_IN;
        }
        signed = direction == StatementLine.Direction.EXPENSE ? amount.negate() : amount;

        BigDecimal balance = null;
        if (layout.balanceCol() >= 0 && cell(row, layout.balanceCol()) != null) {
            balance = money(row, layout.balanceCol());
            if (balance == null) {
                throw StatementParseException.atLine(row.lineNumber(), "the balance could not be read");
            }
        }
        return new StatementLine(date, description, amount, direction, StatementLine.Status.POSTED,
                fingerprint.next(date, description, signed, balance == null ? null : balance.toPlainString()), balance);
    }

    private static boolean looksLikeBalanceRow(String description) {
        String d = description.toLowerCase(Locale.ROOT);
        return d.contains("beginning balance") || d.contains("ending balance")
                || d.contains("opening balance") || d.contains("closing balance");
    }

    private BigDecimal money(CsvReader.Row row, int col) {
        String v = cell(row, col);
        return v == null ? null : CsvLayoutDetector.parseMoney(v);
    }

    /** The trimmed cell, or null when the column is missing from this row or blank. */
    private static String cell(CsvReader.Row row, int col) {
        if (col < 0 || col >= row.fields().size()) {
            return null;
        }
        String v = row.fields().get(col).trim();
        return v.isEmpty() ? null : v;
    }
}
