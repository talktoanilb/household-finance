package com.household.finance.document.ai;

import com.household.finance.document.DocumentKind;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What an AI reader proposed for one photo. Any field may be null when it could not be read. Nothing here is
 * trusted: the caller only uses a field to fill a gap the local reading left, and the same Java checks
 * (DocumentVerifier) run on the result before it can be saved.
 */
public record AiReading(DocumentKind kind, String vendor, BigDecimal total, BigDecimal subtotal, BigDecimal tax,
                        LocalDate date, LocalDate dueDate) {}
