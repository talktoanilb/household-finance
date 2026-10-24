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

    /**
     * Seen from the account the statement belongs to. TRANSFER_IN / TRANSFER_OUT are money moving between the
     * household's own accounts (for example a card payment): they are not spending or earnings and must be left
     * out of those totals. A card payment is TRANSFER_IN on the card and TRANSFER_OUT on the checking account.
     */
    public enum Direction { EXPENSE, INCOME, TRANSFER_IN, TRANSFER_OUT }

    /** Subset of the schema's transaction statuses that a statement can produce. */
    public enum Status { PENDING, POSTED }
}
