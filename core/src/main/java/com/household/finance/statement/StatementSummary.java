package com.household.finance.statement;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The totals a statement prints, used to check the extracted lines.
 * <p>
 * Bank accounts: balances are money held; {@code depositsTotal} is null when not printed.
 * Credit cards ({@code liability} true): balances are money owed; {@code purchasesTotal} is the printed
 * purchases figure and {@code creditsTotal} the printed payments plus credits (both null when not printed).
 */
public record StatementSummary(
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal beginningBalance,
        BigDecimal endingBalance,
        BigDecimal depositsTotal,
        boolean liability,
        BigDecimal purchasesTotal,
        BigDecimal creditsTotal) {

    /** A bank-account summary. */
    public StatementSummary(LocalDate periodStart, LocalDate periodEnd, BigDecimal beginningBalance,
                            BigDecimal endingBalance, BigDecimal depositsTotal) {
        this(periodStart, periodEnd, beginningBalance, endingBalance, depositsTotal, false, null, null);
    }

    /** A credit-card summary. */
    public static StatementSummary card(LocalDate periodStart, LocalDate periodEnd, BigDecimal previousBalance,
                                        BigDecimal newBalance, BigDecimal purchasesTotal, BigDecimal creditsTotal) {
        return new StatementSummary(periodStart, periodEnd, previousBalance, newBalance, null, true,
                purchasesTotal, creditsTotal);
    }
}
