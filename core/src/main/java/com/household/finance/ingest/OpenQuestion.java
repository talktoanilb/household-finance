package com.household.finance.ingest;

/** A question waiting for the user. {@code lineNo} is 0 for a whole-file question. */
public record OpenQuestion(long id, long importId, int lineNo, String kind, String message) {}
