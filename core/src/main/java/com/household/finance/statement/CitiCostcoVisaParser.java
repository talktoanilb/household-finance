package com.household.finance.statement;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Citi Costco Visa CSV: {@code Status,Date,Description,Debit,Credit,Member Name}.
 * Exactly one of Debit (EXPENSE) or Credit (INCOME, e.g. a refund) is filled per row.
 * "Cleared" lines are POSTED, "Pending" lines are PENDING. The file has no running balance,
 * so the occurrence counter in the fingerprint separates identical lines.
 * Member Name is deliberately ignored: it is not needed and is personal data.
 */
public final class CitiCostcoVisaParser implements StatementParser {

    private static final List<String> COLUMNS = List.of("Status", "Date", "Description", "Debit", "Credit");

    @Override
    public List<StatementLine> parse(Reader csv) throws IOException {
        List<CsvReader.Row> rows = CsvReader.read(csv);
        Map<String, Integer> col = CsvStatementSupport.requireHeader(rows, COLUMNS);
        Fingerprint fingerprint = new Fingerprint("CITI_COSTCO_VISA");
        List<StatementLine> lines = new ArrayList<>();
        for (CsvReader.Row row : rows.subList(1, rows.size())) {
            StatementLine.Status status = switch (CsvStatementSupport.field(row, col.get("Status"))) {
                case "Cleared" -> StatementLine.Status.POSTED;
                case "Pending" -> StatementLine.Status.PENDING;
                default -> throw StatementParseException.atLine(row.lineNumber(), "unknown status");
            };
            LocalDate date = CsvStatementSupport.date(row, CsvStatementSupport.field(row, col.get("Date")));
            String description = CsvStatementSupport.requireDescription(row,
                    CsvStatementSupport.field(row, col.get("Description")));
            String debit = CsvStatementSupport.field(row, col.get("Debit"));
            String credit = CsvStatementSupport.field(row, col.get("Credit"));
            if (debit.isEmpty() == credit.isEmpty()) {
                throw StatementParseException.atLine(row.lineNumber(), "exactly one of Debit or Credit must be filled");
            }
            StatementLine.Direction direction =
                    debit.isEmpty() ? StatementLine.Direction.INCOME : StatementLine.Direction.EXPENSE;
            BigDecimal amount = CsvStatementSupport.money(row, debit.isEmpty() ? credit : debit);
            if (amount.signum() <= 0) {
                throw StatementParseException.atLine(row.lineNumber(), "amount must be positive");
            }
            BigDecimal signed = direction == StatementLine.Direction.EXPENSE ? amount.negate() : amount;
            lines.add(new StatementLine(date, description, amount, direction, status,
                    fingerprint.next(date, description, signed, null), null));
        }
        return List.copyOf(lines);
    }
}
