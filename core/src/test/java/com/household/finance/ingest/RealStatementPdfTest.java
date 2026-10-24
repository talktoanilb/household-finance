package com.household.finance.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.household.finance.statement.ChasePdfStatementParser;
import com.household.finance.statement.ParsedStatement;
import com.household.finance.statement.PdfBoxTextExtractor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in check against YOUR real statements, kept out of the repo. Skipped unless REAL_STATEMENT_PDF is set to
 * one or more PDF paths separated by commas. It prints only counts, never statement text. Never commit real files.
 *   REAL_STATEMENT_PDF="/path/one.pdf,/path/two.pdf" mvn -Dtest=RealStatementPdfTest test
 */
class RealStatementPdfTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "REAL_STATEMENT_PDF", matches = ".+")
    void realStatementsReadAndReconcile() throws Exception {
        for (String path : System.getenv("REAL_STATEMENT_PDF").split(",")) {
            byte[] pdf = Files.readAllBytes(Path.of(path.trim()));
            ParsedStatement parsed = new ChasePdfStatementParser().parse(new PdfBoxTextExtractor().extract(pdf));
            assertEquals(List.of(), Verifier.verifySummary(parsed.lines(), parsed.summary()));
            System.out.println("REAL STATEMENT OK: " + parsed.lines().size() + " lines, account ending " + parsed.accountLast4());
        }
    }
}
