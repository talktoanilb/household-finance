package com.household.finance.ingest;

import java.util.List;

/**
 * Result of an import step, in plain terms for the caller to show the user.
 * {@code importId} is null when nothing was stored (REJECTED).
 */
public record ImportOutcome(Status status, Long importId, int imported, int skippedDuplicates, List<String> messages) {

    public enum Status {
        /** Every line was verified (or confirmed) and saved. */
        COMMITTED,
        /** Lines are staged; the user must answer the open questions. Nothing was saved to transactions. */
        NEEDS_ANSWERS,
        /** This exact file was already imported for this account. Nothing changed. */
        ALREADY_IMPORTED,
        /** The file did not match the expected format. Nothing was stored. */
        REJECTED,
        /** The user cancelled the import. Staged lines were discarded. */
        CANCELLED
    }

    static ImportOutcome rejected(String message) {
        return new ImportOutcome(Status.REJECTED, null, 0, 0, List.of(message));
    }
}
