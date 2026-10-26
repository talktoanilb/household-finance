package com.household.finance.document;

import java.io.IOException;
import java.util.List;

/**
 * Turns a photo into plain text, one visual row per line. The reader only proposes text; every value taken from it
 * is checked in Java and confirmed by the user before anything is saved. Implementations must not log the text.
 */
public interface OcrEngine {

    String read(byte[] image) throws IOException;

    /**
     * Several independent readings of the same photo (for example at different sizes). Digits are the part OCR gets
     * wrong most, and two readings rarely make the same mistake, so comparing them shows which amounts to trust.
     * The default is a single reading.
     */
    default List<String> readAll(byte[] image) throws IOException {
        return List.of(read(image));
    }
}
