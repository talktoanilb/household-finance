package com.household.finance.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.household.finance.document.ai.FakeAiReader;
import com.household.finance.document.pdf.FakePdfTextExtractor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * A text-layer PDF bill, read the same way a photo's OCR text is, with a fake PdfTextExtractor so no real PDF file
 * or PDFBox call is needed. Hand-checked: "Amount due: $142.37" on 10/15/2026.
 */
@SpringBootTest
@Transactional
class DocumentImportServicePdfBillTest {

    private static final String BILL_PDF_TEXT = """
            SYNTHETIC POWER CO
            Account: TEST-0000
            Service period: 09/01/2026 - 09/30/2026
            Due date: 10/15/2026
            Amount due: $142.37
            """;

    @Autowired JdbcTemplate jdbc;

    FakePdfTextExtractor pdfText;
    DocumentImportService service;
    long household;
    long user;

    @BeforeEach
    void seed() {
        pdfText = new FakePdfTextExtractor();
        pdfText.nextText = BILL_PDF_TEXT;
        OcrEngine unusedOcr = image -> {
            throw new IllegalStateException("A PDF bill must not use OCR.");
        };
        service = new DocumentImportService(jdbc, unusedOcr, new FakeAiReader(), pdfText,
                Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC));
        household = jdbc.queryForObject("INSERT INTO households (name) VALUES ('test') RETURNING id", Long.class);
        user = jdbc.queryForObject("""
                INSERT INTO app_users (household_id, email, display_name, password_hash)
                VALUES (?, 'test@example.com', 'Test', 'x') RETURNING id
                """, Long.class, household);
    }

    private static byte[] pdf(int seed) {
        return new byte[] {'%', 'P', 'D', 'F', '-', '1', '.', '4', (byte) seed};
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void uploadingProposesValuesButSavesNothing() {
        DocumentOutcome out = service.uploadPdfBill(household, user, pdf(1));
        assertEquals(DocumentOutcome.Status.NEEDS_CONFIRMATION, out.status());
        assertEquals(DocumentKind.BILL, out.proposal().kind());
        assertEquals(new BigDecimal("142.37"), out.proposal().amount());
        assertEquals(LocalDate.of(2026, 10, 15), out.proposal().date());
        assertEquals(0, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
    }

    @Test
    void confirmingSavesExactlyOneTransaction() {
        DocumentOutcome up = service.uploadPdfBill(household, user, pdf(1));
        DocumentOutcome out = service.confirm(household, up.importId(), user, up.proposal(), "utilities", null);
        assertEquals(DocumentOutcome.Status.COMMITTED, out.status());
        assertEquals(1, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM transactions WHERE id = ?", String.class, out.transactionId()));
    }

    @Test
    void aScannedPdfWithNoTextIsRefused() {
        pdfText.nextText = "   ";
        DocumentOutcome out = service.uploadPdfBill(household, user, pdf(1));
        assertEquals(DocumentOutcome.Status.REJECTED, out.status());
        assertTrue(out.messages().get(0).contains("may be a scan"), out.messages().toString());
        assertEquals(0, count("SELECT count(*) FROM document_imports WHERE household_id = ?", household));
    }

    @Test
    void anUnreadablePdfIsRefused() {
        pdfText.nextFailure = new RuntimeException("bad PDF");
        DocumentOutcome out = service.uploadPdfBill(household, user, pdf(1));
        assertEquals(DocumentOutcome.Status.REJECTED, out.status());
        assertTrue(out.messages().get(0).contains("could not be read"), out.messages().toString());
    }

    @Test
    void notAPdfIsRejectedBeforeAnyExtraction() {
        DocumentOutcome out = service.uploadPdfBill(household, user, new byte[] {1, 2, 3});
        assertEquals(DocumentOutcome.Status.REJECTED, out.status());
        assertTrue(out.messages().get(0).contains("Only PDF files"), out.messages().toString());
    }

    @Test
    void rePickingTheSamePendingPdfResumesInsteadOfBeingRefused() {
        DocumentOutcome first = service.uploadPdfBill(household, user, pdf(1));
        DocumentOutcome second = service.uploadPdfBill(household, user, pdf(1));
        assertEquals(DocumentOutcome.Status.NEEDS_CONFIRMATION, second.status());
        assertEquals(first.importId(), second.importId());
        assertEquals(new BigDecimal("142.37"), second.proposal().amount());
    }

    @Test
    void onlyACommittedPdfBillIsRefused() {
        DocumentOutcome up = service.uploadPdfBill(household, user, pdf(1));
        service.confirm(household, up.importId(), user, up.proposal(), null, null);
        DocumentOutcome again = service.uploadPdfBill(household, user, pdf(1));
        assertEquals(DocumentOutcome.Status.ALREADY_IMPORTED, again.status());
    }

    @Test
    void aPdfBillWithAnUnclearAmountIsNotGuessed() {
        pdfText.nextText = "SYNTHETIC POWER CO\nDue date: 10/15/2026\n";   // no "Amount due" line
        DocumentOutcome out = service.uploadPdfBill(household, user, pdf(1));
        assertEquals(DocumentOutcome.Status.NEEDS_CONFIRMATION, out.status());
        assertEquals(null, out.proposal().amount());
        assertTrue(out.messages().stream().anyMatch(m -> m.toLowerCase().contains("amount")));
    }
}
