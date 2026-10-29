package com.household.finance.web;

/** Raised when the local database has no household member yet, so there is nobody to act as. */
public class NoLocalUserException extends RuntimeException {

    public NoLocalUserException() {
        super("No household member exists yet. Create one first (see 'Run the app' in the README).");
    }
}
