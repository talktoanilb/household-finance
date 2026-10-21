package com.household.finance.ingest;

import java.math.BigDecimal;

/**
 * The user's answer to an {@link OpenQuestion}.
 * CONFIRM: the line is right as read. EXCLUDE: drop that line (on a whole-file question: cancel the import).
 * CORRECT_AMOUNT: replace the line's amount with the given non-negative value (at most 2 decimals).
 */
public record Answer(Type type, BigDecimal correctedAmount) {

    public enum Type { CONFIRM, EXCLUDE, CORRECT_AMOUNT }

    public static Answer confirm() {
        return new Answer(Type.CONFIRM, null);
    }

    public static Answer exclude() {
        return new Answer(Type.EXCLUDE, null);
    }

    public static Answer correctAmount(BigDecimal amount) {
        return new Answer(Type.CORRECT_AMOUNT, amount);
    }
}
