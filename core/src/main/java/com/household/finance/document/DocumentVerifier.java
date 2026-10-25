package com.household.finance.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Java-side checks on the values of one photographed bill or receipt. The reader proposes; this decides what is
 * still wrong. Pure: no database, no clock (the caller passes today's date).
 * Messages are built from numbers and dates only, never from document text, so they are safe to show and log.
 */
public final class DocumentVerifier {

    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");
    static final int MAX_AGE_YEARS = 7;
    static final int MAX_VENDOR_LENGTH = 80;

    private DocumentVerifier() {}

    /** An empty list means every check passed. */
    public static List<String> verify(DocumentFields f, LocalDate today) {
        List<String> problems = new ArrayList<>();
        if (f.kind() == null || f.kind() == DocumentKind.UNKNOWN) {
            problems.add("Say whether this is a bill or a receipt.");
        }
        if (f.vendor() == null || f.vendor().isBlank()) {
            problems.add("A vendor name is needed.");
        } else if (f.vendor().trim().length() > MAX_VENDOR_LENGTH) {
            problems.add("The vendor name is longer than " + MAX_VENDOR_LENGTH + " characters.");
        }

        BigDecimal amount = f.amount();
        if (amount == null) {
            problems.add("An amount is needed.");
        } else if (amount.signum() <= 0) {
            problems.add("The amount must be more than zero.");
        } else if (amount.scale() > 2) {
            problems.add("The amount has more than 2 decimal places: " + amount.toPlainString() + ".");
        } else if (amount.compareTo(MAX_AMOUNT) >= 0) {
            problems.add("The amount " + amount.toPlainString() + " is at or above the 1,000,000 limit for photo entries."
                    + " Enter it by hand if it is correct.");
        }

        problems.addAll(dateProblems(f, today));
        problems.addAll(subtotalAndTaxProblems(f));
        return problems;
    }

    private static List<String> dateProblems(DocumentFields f, LocalDate today) {
        LocalDate date = f.date();
        if (date == null) {
            return List.of(f.kind() == DocumentKind.RECEIPT ? "The purchase date is needed."
                    : f.kind() == DocumentKind.BILL ? "The due date is needed." : "A date is needed.");
        }
        if (date.isBefore(today.minusYears(MAX_AGE_YEARS))) {
            return List.of("The date " + date + " is more than " + MAX_AGE_YEARS + " years ago.");
        }
        if (f.kind() == DocumentKind.RECEIPT && date.isAfter(today)) {
            return List.of("The receipt date " + date + " is in the future.");
        }
        if (f.kind() == DocumentKind.BILL && date.isAfter(today.plusYears(1))) {
            return List.of("The due date " + date + " is more than a year away.");
        }
        return List.of();
    }

    private static List<String> subtotalAndTaxProblems(DocumentFields f) {
        List<String> problems = new ArrayList<>();
        BigDecimal subtotal = f.subtotal();
        BigDecimal tax = f.tax();
        if (subtotal != null && (subtotal.signum() < 0 || subtotal.scale() > 2)) {
            problems.add("The subtotal must be zero or more, with at most 2 decimals.");
            subtotal = null;
        }
        if (tax != null && (tax.signum() < 0 || tax.scale() > 2)) {
            problems.add("The tax must be zero or more, with at most 2 decimals.");
            tax = null;
        }
        BigDecimal amount = f.amount();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            return problems;
        }
        if (subtotal != null && tax != null) {
            BigDecimal sum = subtotal.add(tax);
            if (sum.compareTo(amount) != 0) {
                problems.add("The subtotal " + subtotal.toPlainString() + " plus tax " + tax.toPlainString() + " is "
                        + sum.toPlainString() + ", but the total is " + amount.toPlainString() + ". One of them is misread.");
            }
        } else if (subtotal != null && amount.compareTo(subtotal) < 0) {
            problems.add("The total " + amount.toPlainString() + " is less than the subtotal " + subtotal.toPlainString() + ".");
        }
        return problems;
    }
}
