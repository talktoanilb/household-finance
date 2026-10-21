package com.household.finance.ingest;

/**
 * Something the verifier could not confirm, to be put to the user as a question.
 * {@code lineNo} is the 1-based data line the issue is about, or 0 when it is about the whole file.
 * Messages are built from numbers and dates only, never from document text.
 */
public record Issue(int lineNo, Kind kind, String message) {

    public enum Kind { BALANCE_MISMATCH, DATE_IMPLAUSIBLE }

    public boolean wholeFile() {
        return lineNo == 0;
    }
}
