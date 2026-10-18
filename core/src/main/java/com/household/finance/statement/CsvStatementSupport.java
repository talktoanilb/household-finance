package com.household.finance.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Map;

/** Shared helpers for the bank-specific parsers: strict header check, dates, money. */
final class CsvStatementSupport {

    private static final DateTimeFormatter US_DATE =
            DateTimeFormatter.ofPattern("MM/dd/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private CsvStatementSupport() {}

    /** Returns column name -> index. Fails unless every expected column is present (format drift). */
    static Map<String, Integer> requireHeader(List<CsvReader.Row> rows, List<String> expected) {
        if (rows.isEmpty()) {
            throw new StatementParseException("empty file: header row missing");
        }
        List<String> header = rows.get(0).fields().stream().map(String::trim).toList();
        Map<String, Integer> index = new java.util.HashMap<>();
        for (String column : expected) {
            int i = header.indexOf(column);
            if (i < 0) {
                throw StatementParseException.atLine(rows.get(0).lineNumber(),
                        "unexpected header: expected column '" + column + "' is missing");
            }
            index.put(column, i);
        }
        return index;
    }

    static String field(CsvReader.Row row, int index) {
        if (index >= row.fields().size()) {
            throw StatementParseException.atLine(row.lineNumber(), "row has too few columns");
        }
        return row.fields().get(index).trim();
    }

    static LocalDate date(CsvReader.Row row, String value) {
        try {
            return LocalDate.parse(value, US_DATE);
        } catch (java.time.DateTimeException e) {
            throw StatementParseException.atLine(row.lineNumber(), "invalid date, expected MM/dd/yyyy");
        }
    }

    /** Parses a plain decimal with at most 2 fraction digits. Strict on purpose: no $ or thousands commas. */
    static BigDecimal money(CsvReader.Row row, String value) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw StatementParseException.atLine(row.lineNumber(), "invalid amount");
        }
        if (amount.scale() > 2) {
            throw StatementParseException.atLine(row.lineNumber(), "amount has more than 2 decimal places");
        }
        return amount.setScale(2);
    }

    static String requireDescription(CsvReader.Row row, String value) {
        if (value.isEmpty()) {
            throw StatementParseException.atLine(row.lineNumber(), "description is empty");
        }
        return value;
    }
}
