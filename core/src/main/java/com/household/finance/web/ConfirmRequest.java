package com.household.finance.web;

import com.household.finance.document.DocumentFields;
import com.household.finance.document.DocumentKind;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What the user confirmed for one photographed bill or receipt. Every value is checked again in Java by the service;
 * nothing here is trusted. {@code categoryCode} and {@code accountId} are optional.
 */
public record ConfirmRequest(
        DocumentKind kind,
        String vendor,
        BigDecimal amount,
        LocalDate date,
        BigDecimal subtotal,
        BigDecimal tax,
        String categoryCode,
        Long accountId) {

    DocumentFields fields() {
        return new DocumentFields(kind, vendor, amount, date, subtotal, tax);
    }

    String categoryOrNull() {
        return categoryCode == null || categoryCode.isBlank() ? null : categoryCode.trim();
    }
}
