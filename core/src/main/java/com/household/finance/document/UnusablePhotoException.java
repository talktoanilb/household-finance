package com.household.finance.document;

import java.io.IOException;

/** The photo cannot be used as an image (damaged, wrong format, or too large). The message is plain words for the user. */
public class UnusablePhotoException extends IOException {

    private static final long serialVersionUID = 1L;

    public UnusablePhotoException(String message) {
        super(message);
    }
}
