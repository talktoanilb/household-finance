package com.household.finance.statement;

import java.io.IOException;

/** Turns a PDF with a text layer into plain text, one visual row per line. Scanned PDFs need OCR instead. */
public interface PdfTextExtractor {

    String extract(byte[] pdf) throws IOException;
}
