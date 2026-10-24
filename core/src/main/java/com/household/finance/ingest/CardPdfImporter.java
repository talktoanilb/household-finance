package com.household.finance.ingest;

import com.household.finance.statement.CardPdfStatementParser;
import com.household.finance.statement.CardPdfStatementParser.Issuer;
import com.household.finance.statement.ParsedStatement;
import com.household.finance.statement.PdfTextExtractor;
import com.household.finance.statement.StatementParseException;
import java.io.IOException;
import org.springframework.stereotype.Service;

/**
 * Imports a credit card statement PDF (Discover, Chase card, Citi Costco Visa): extract the text locally, parse
 * it, then hand it to the import flow, which checks the lines against the statement's printed totals, the card's
 * last 4 digits and the previous statement. Card payments are saved as transfers, not as earnings.
 * Every failure here stores nothing and says why in plain words.
 */
@Service
public class CardPdfImporter {

    static final int MAX_PDF_BYTES = 10 * 1024 * 1024;

    private final ImportService service;
    private final PdfTextExtractor extractor;

    public CardPdfImporter(ImportService service, PdfTextExtractor extractor) {
        this.service = service;
        this.extractor = extractor;
    }

    public ImportOutcome importCardPdf(long householdId, long accountId, long userId, Issuer issuer, byte[] pdf) {
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
                    + " Nothing was saved. Enter the items by hand.");
        }
        ParsedStatement parsed;
        try {
            parsed = new CardPdfStatementParser(issuer).parse(text);
        } catch (StatementParseException e) {
            return ImportOutcome.rejected("This PDF does not match the expected " + issuer
                    + " statement format (" + e.getMessage() + "). Nothing was saved.");
        }
        return service.importParsed(householdId, accountId, userId, issuer + "_PDF", parsed, pdf);
    }
}
