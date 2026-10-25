package com.household.finance.document;

import java.util.List;

/**
 * Result of a photo step, in plain terms for the caller to show the user.
 * {@code importId} is null when nothing was stored (REJECTED, or a repeat of a photo already uploaded).
 * {@code proposal} is what the reader suggests, for the user to correct and confirm; {@code messages} say what is
 * still unclear or wrong. {@code transactionId} is set only once the upload is COMMITTED.
 */
public record DocumentOutcome(Status status, Long importId, Long transactionId, DocumentFields proposal, List<String> messages) {

    public enum Status {
        /** The photo was read and its values are waiting for the user. Nothing was saved to transactions. */
        NEEDS_CONFIRMATION,
        /** The user confirmed values that passed every check, and one transaction was saved. */
        COMMITTED,
        /** This exact photo was already uploaded. Nothing changed. */
        ALREADY_IMPORTED,
        /** The photo could not be used. Nothing was stored. */
        REJECTED,
        /** The user cancelled the upload. Its proposed values were discarded. */
        CANCELLED
    }

    static DocumentOutcome rejected(String message) {
        return new DocumentOutcome(Status.REJECTED, null, null, null, List.of(message));
    }
}
