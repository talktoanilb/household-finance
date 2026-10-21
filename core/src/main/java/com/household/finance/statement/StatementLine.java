package com.household.finance.statement;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One parsed statement line, ready to become a {@code transactions} row.
 * {@code amount} is always non-negative (matches the schema); {@code direction} carries the sign.
 * {@code externalRef} is the idempotency fingerprint stored in {@code transactions.external_ref}.
 * {@code balance} is the running balance after this line when the bank provides one, otherwise null.
 */
public record StatementLine(
        LocalDate postedOn,
        String description,
        BigDecimal amount,
        Direction direction,
        Status status,
        String externalRef,
        BigDecimal balance) {

    public enum Direction { EXPENSE, INCOME }

    /** Subset of the schema's transaction statuses that a statement can produce. */
    public enum Status { PENDING, POSTED }
}
