package com.household.finance.statement;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chase checking CSV: {@code Details,Posting Date,Description,Amount,Type,Balance,Check or Slip #}.
 * Amount is signed: negative is money out (EXPENSE), positive is money in (INCOME).
 * Every Chase line is already posted. The running balance is part of the fingerprint, which keeps
 * identical same-day purchases distinct and stable across re-imports.
 */
public final class ChaseCheckingParser implements StatementParser {

    private static final List<String> COLUMNS =
            List.of("Details", "Posting Date", "Description", "Amount", "Balance");

    @Override
    public List<StatementLine> parse(Reader csv) throws IOException {
        List<CsvReader.Row> rows = CsvReader.read(csv);
        Map<String, Integer> col = CsvStatementSupport.requireHeader(rows, COLUMNS);
        Fingerprint fingerprint = new Fingerprint("CHASE_CHECKING");
        List<StatementLine> lines = new ArrayList<>();
        for (CsvReader.Row row : rows.subList(1, rows.size())) {
            LocalDate date = CsvStatementSupport.date(row, CsvStatementSupport.field(row, col.get("Posting Date")));
            String description = CsvStatementSupport.requireDescription(row,
                    CsvStatementSupport.field(row, col.get("Description")));
            BigDecimal signed = CsvStatementSupport.money(row, CsvStatementSupport.field(row, col.get("Amount")));
            if (signed.signum() == 0) {
                throw StatementParseException.atLine(row.lineNumber(), "amount is zero");
            }
            String balance = CsvStatementSupport.field(row, col.get("Balance"));
            if (!balance.isEmpty()) {
                balance = CsvStatementSupport.money(row, balance).toPlainString();
            }
            StatementLine.Direction direction =
                    signed.signum() < 0 ? StatementLine.Direction.EXPENSE : StatementLine.Direction.INCOME;
            lines.add(new StatementLine(date, description, signed.abs(), direction,
                    StatementLine.Status.POSTED, fingerprint.next(date, description, signed, balance)));
        }
        return List.copyOf(lines);
    }
}
