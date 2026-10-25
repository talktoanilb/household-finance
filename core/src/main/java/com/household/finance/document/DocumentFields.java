package com.household.finance.document;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The values of one photographed bill or receipt: either what the reader proposes or what the user confirms.
 * {@code date} is the purchase date for a receipt and the due date for a bill. {@code subtotal} and {@code tax}
 * are optional and only used to cross-check the total. Any field may be null in a proposal.
 */
public record DocumentFields(
        DocumentKind kind,
        String vendor,
        BigDecimal amount,
        LocalDate date,
        BigDecimal subtotal,
        BigDecimal tax) {}
