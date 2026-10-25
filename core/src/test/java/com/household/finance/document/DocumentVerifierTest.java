package com.household.finance.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentVerifierTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    private static DocumentFields receipt(String amount, LocalDate date, String subtotal, String tax) {
        return new DocumentFields(DocumentKind.RECEIPT, "Corner Grocery", amount == null ? null : new BigDecimal(amount),
                date, subtotal == null ? null : new BigDecimal(subtotal), tax == null ? null : new BigDecimal(tax));
    }

    /** Hand-checked: 7.68 + 0.63 = 8.31. */
    @Test
    void cleanReceiptHasNoProblems() {
        assertEquals(List.of(), DocumentVerifier.verify(receipt("8.31", LocalDate.of(2026, 10, 18), "7.68", "0.63"), TODAY));
    }

    @Test
    void subtotalPlusTaxMustEqualTheTotal() {
        List<String> problems = DocumentVerifier.verify(receipt("8.41", LocalDate.of(2026, 10, 18), "7.68", "0.63"), TODAY);
        assertEquals(List.of("The subtotal 7.68 plus tax 0.63 is 8.31, but the total is 8.41. One of them is misread."), problems);
    }

    @Test
    void totalCannotBeBelowTheSubtotal() {
        List<String> problems = DocumentVerifier.verify(receipt("5.00", LocalDate.of(2026, 10, 18), "7.68", null), TODAY);
        assertEquals(List.of("The total 5.00 is less than the subtotal 7.68."), problems);
    }

    @Test
    void amountMustBePositiveWithTwoDecimalsAndBelowTheLimit() {
        assertEquals(List.of("An amount is needed."),
                DocumentVerifier.verify(receipt(null, LocalDate.of(2026, 10, 18), null, null), TODAY));
        assertEquals(List.of("The amount must be more than zero."),
                DocumentVerifier.verify(receipt("0.00", LocalDate.of(2026, 10, 18), null, null), TODAY));
        assertEquals(List.of("The amount has more than 2 decimal places: 8.311."),
                DocumentVerifier.verify(receipt("8.311", LocalDate.of(2026, 10, 18), null, null), TODAY));
        assertTrue(DocumentVerifier.verify(receipt("1000000.00", LocalDate.of(2026, 10, 18), null, null), TODAY)
                .get(0).contains("1,000,000"));
    }

    @Test
    void receiptDateCannotBeInTheFutureOrOlderThanSevenYears() {
        assertEquals(List.of("The receipt date 2026-10-26 is in the future."),
                DocumentVerifier.verify(receipt("8.31", LocalDate.of(2026, 10, 26), null, null), TODAY));
        assertEquals(List.of("The date 2019-10-24 is more than 7 years ago."),
                DocumentVerifier.verify(receipt("8.31", LocalDate.of(2019, 10, 24), null, null), TODAY));
        assertEquals(List.of(), DocumentVerifier.verify(receipt("8.31", LocalDate.of(2026, 10, 25), null, null), TODAY));
    }

    @Test
    void billDueDateMayBeInTheFutureButNotMoreThanAYear() {
        DocumentFields near = new DocumentFields(DocumentKind.BILL, "Synthetic Power Co", new BigDecimal("142.37"),
                LocalDate.of(2026, 11, 15), null, null);
        assertEquals(List.of(), DocumentVerifier.verify(near, TODAY));
        DocumentFields far = new DocumentFields(DocumentKind.BILL, "Synthetic Power Co", new BigDecimal("142.37"),
                LocalDate.of(2027, 10, 26), null, null);
        assertEquals(List.of("The due date 2027-10-26 is more than a year away."), DocumentVerifier.verify(far, TODAY));
    }

    @Test
    void kindVendorAndDateAreRequired() {
        DocumentFields none = new DocumentFields(DocumentKind.UNKNOWN, " ", new BigDecimal("8.31"), null, null, null);
        List<String> problems = DocumentVerifier.verify(none, TODAY);
        assertEquals(List.of("Say whether this is a bill or a receipt.", "A vendor name is needed.", "A date is needed."), problems);
    }

    @Test
    void messagesNeverRepeatTheVendorText() {
        DocumentFields f = new DocumentFields(DocumentKind.RECEIPT, "ACCT 4111111111111111 SECRET", new BigDecimal("0"),
                LocalDate.of(2026, 10, 18), null, null);
        for (String message : DocumentVerifier.verify(f, TODAY)) {
            assertTrue(!message.contains("SECRET") && !message.contains("4111"), message);
        }
    }
}
