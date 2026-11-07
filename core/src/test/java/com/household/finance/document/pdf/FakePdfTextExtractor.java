package com.household.finance.document.pdf;

import com.household.finance.statement.PdfTextExtractor;
import java.io.IOException;

/** Test double: returns fixed text for any PDF bytes, without using PDFBox. */
public class FakePdfTextExtractor implements PdfTextExtractor {

    public String nextText;
    public RuntimeException nextFailure;

    @Override
    public String extract(byte[] pdf) throws IOException {
        if (nextFailure != null) {
            throw nextFailure;
        }
        return nextText;
    }
}
