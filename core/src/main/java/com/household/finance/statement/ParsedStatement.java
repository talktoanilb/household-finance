package com.household.finance.statement;

import java.util.List;

/**
 * Everything extracted from one statement file. {@code summary} and {@code accountLast4} are null for
 * formats that do not carry them (the CSV exports). Only the last 4 digits of the account are kept,
 * never the full number.
 */
public record ParsedStatement(List<StatementLine> lines, StatementSummary summary, String accountLast4) {

    public static ParsedStatement ofLines(List<StatementLine> lines) {
        return new ParsedStatement(lines, null, null);
    }
}
