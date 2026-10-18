package com.household.finance.statement;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 reader: quoted fields, escaped quotes, CRLF or LF, optional UTF-8 BOM. */
final class CsvReader {

    private CsvReader() {}

    /** A parsed record and the 1-based line number where it started. */
    record Row(int lineNumber, List<String> fields) {}

    static List<Row> read(Reader source) throws IOException {
        BufferedReader reader = source instanceof BufferedReader b ? b : new BufferedReader(source);
        List<Row> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldWasQuoted = false;
        boolean first = true;
        int line = 1;
        int rowStart = 1;
        int c;
        while ((c = reader.read()) != -1) {
            if (first) {
                first = false;
                if (c == '﻿') {
                    continue;
                }
            }
            if (inQuotes) {
                if (c == '"') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        inQuotes = false;
                        if (next != -1) {
                            reader.reset();
                        }
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append((char) c);
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inQuotes = true;
                    fieldWasQuoted = true;
                }
                case ',' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> { /* swallowed; the following \n ends the record */ }
                case '\n' -> {
                    fields.add(field.toString());
                    addRow(rows, rowStart, fields, fieldWasQuoted);
                    fields = new ArrayList<>();
                    field.setLength(0);
                    fieldWasQuoted = false;
                    line++;
                    rowStart = line;
                }
                default -> field.append((char) c);
            }
        }
        if (inQuotes) {
            throw StatementParseException.atLine(rowStart, "unterminated quoted field");
        }
        if (field.length() > 0 || !fields.isEmpty()) {
            fields.add(field.toString());
            addRow(rows, rowStart, fields, fieldWasQuoted);
        }
        return rows;
    }

    private static void addRow(List<Row> rows, int lineNumber, List<String> fields, boolean anyQuoted) {
        boolean blank = fields.size() == 1 && fields.get(0).isBlank() && !anyQuoted;
        if (!blank) {
            rows.add(new Row(lineNumber, List.copyOf(fields)));
        }
    }
}
