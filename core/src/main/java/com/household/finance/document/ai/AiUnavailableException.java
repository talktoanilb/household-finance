package com.household.finance.document.ai;

/** AI reading was asked for but could not run: no key is set, the service refused the request, or it timed out. */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
