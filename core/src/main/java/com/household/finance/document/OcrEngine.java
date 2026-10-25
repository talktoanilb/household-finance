package com.household.finance.document;

import java.io.IOException;

/**
 * Turns a photo into plain text, one visual row per line. The reader only proposes text; every value taken from it
 * is checked in Java and confirmed by the user before anything is saved. Implementations must not log the text.
 */
public interface OcrEngine {

    String read(byte[] image) throws IOException;
}
