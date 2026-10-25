package com.household.finance.statement;

/**
 * How to read one bank's CSV: which column holds what. Learned once per account, confirmed by the user, then reused.
 * Columns are 0-based; -1 means "none".
 * <p>
 * {@code fingerprint} identifies the file's column names (or its width when there is no header row), so a bank
 * that changes its export is noticed and the user is asked again instead of the old mapping being applied blindly.
 */
public record CsvLayout(
        String fingerprint,
        int dateCol,
        String dateFormat,
        int descCol,
        AmountMode mode,
        int amountCol,
        int debitCol,
        int creditCol,
        boolean outIsNegative,
        int balanceCol) {

    public enum AmountMode {
        /** One amount column; the sign says which way the money went (see {@code outIsNegative}). */
        SINGLE,
        /** Separate columns for money out (debit) and money in (credit); one of them is blank on each row. */
        DEBIT_CREDIT
    }
}
