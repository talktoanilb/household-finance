package com.household.finance.ingest;

import com.household.finance.statement.ChasePdfStatementParser;
import com.household.finance.statement.ParsedStatement;
import com.household.finance.statement.PdfTextExtractor;
import com.household.finance.statement.StatementParseException;
import java.io.IOException;
import org.springframework.stereotype.Service;

/**
 * Imports a Chase checking statement PDF: extract the text locally, parse it, then hand it to the import flow,
 * which checks the lines against the statement's own totals and against the previous statement.
 * Every failure here stores nothing and says why in plain words.
 */
@Service
public class ChasePdfImporter {

    static final int MAX_PDF_BYTES = 10 * 1024 * 1024;

    private final ImportService service;
    private final PdfTextExtractor extractor;
    private final ChasePdfStatementParser parser = new ChasePdfStatementParser();

    public ChasePdfImporter(ImportService service, PdfTextExtractor extractor) {
        this.service = service;
        this.extractor = extractor;
    }

    public ImportOutcome importChaseCheckingPdf(long householdId, long accountId, long userId, byte[] pdf) {
        if (pdf.length > MAX_PDF_BYTES) {
            return ImportOutcome.rejected("The PDF is larger than 10 MB. Nothing was saved.");
        }
        String text;
        try {
            text = extractor.extract(pdf);
        } catch (IOException | RuntimeException e) {
            return ImportOutcome.rejected("The PDF could not be read. It may be damaged or password-protected. Nothing was saved.");
        }
        if (text == null || text.isBlank()) {
            return ImportOutcome.rejected("This PDF has no readable text. It may be a scan, which is not supported yet."
                    + " Nothing was saved. Enter the items by hand, or use a CSV export.");
        }
        ParsedStatement parsed;
        try {
            parsed = parser.parse(text);
        } catch (StatementParseException e) {
            return ImportOutcome.rejected("This PDF does not match the expected Chase checking statement format ("
                    + e.getMessage() + "). Nothing was saved.");
        }
        return service.importParsed(householdId, accountId, userId, "CHASE_CHECKING_PDF", parsed, pdf);
    }
}
