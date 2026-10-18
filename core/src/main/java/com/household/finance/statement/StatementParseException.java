package com.household.finance.statement;

/**
 * Thrown when a statement file does not match the expected format.
 * Messages carry the line number and a reason only, never the file's contents
 * (CLAUDE.md: never log document contents or account data).
 */
public class StatementParseException extends RuntimeException {

    public StatementParseException(String message) {
        super(message);
    }

    static StatementParseException atLine(int lineNumber, String reason) {
        return new StatementParseException("line " + lineNumber + ": " + reason);
    }
}
