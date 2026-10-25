package com.household.finance.document;

import java.io.IOException;

/** The OCR program is not installed or not reachable. The message is plain words for the user. */
public class OcrUnavailableException extends IOException {

    public OcrUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
