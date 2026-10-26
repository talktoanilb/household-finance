package com.household.finance.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end tests of the photo flow against PostgreSQL. Each test is rolled back.
 * The OCR engine is faked with synthetic text, so these tests do not need Tesseract.
 * Hand-checked numbers: the receipt is 4.19 + 3.49 = 7.68 subtotal, plus 0.63 tax = 8.31 total.
 */
@SpringBootTest
@Transactional
class DocumentImportServiceTest {

    private static final String RECEIPT_TEXT = """
            CORNER GROCERY
            123 Main St
            10/18/2026 14:32
            MILK 1 GAL        4.19
            BREAD             3.49
            SUBTOTAL          7.68
            TAX 8.25%         0.63
            TOTAL             8.31
            """;

    private static final String BILL_TEXT = """
            SYNTHETIC POWER CO
            Account: TEST-0000
            Service period: 09/01/2026 - 09/30/2026
            Due date: 10/15/2026
            Amount due: $142.37
            """;

    @Autowired JdbcTemplate jdbc;

    /** Text the fake OCR engine returns for the next photo. */
    String ocrText = RECEIPT_TEXT;
    boolean ocrCalled;
    DocumentImportService service;
    long household;
    long otherHousehold;
    long user;

    @BeforeEach
    void seed() {
        OcrEngine fake = image -> {
            ocrCalled = true;
            return ocrText;
        };
        service = new DocumentImportService(jdbc, fake, Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC));
        household = jdbc.queryForObject("INSERT INTO households (name) VALUES ('test') RETURNING id", Long.class);
        otherHousehold = jdbc.queryForObject("INSERT INTO households (name) VALUES ('other') RETURNING id", Long.class);
        user = jdbc.queryForObject("""
                INSERT INTO app_users (household_id, email, display_name, password_hash)
                VALUES (?, 'test@example.com', 'Test', 'x') RETURNING id
                """, Long.class, household);
    }

    /** A byte string that starts like a PNG file. The fake OCR engine ignores the rest. */
    private static byte[] png(int seed) {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, (byte) seed, 1, 2, 3};
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private DocumentFields goodReceipt() {
        return new DocumentFields(DocumentKind.RECEIPT, "Corner Grocery", new BigDecimal("8.31"),
                LocalDate.of(2026, 10, 18), new BigDecimal("7.68"), new BigDecimal("0.63"));
    }

    @Test
    void uploadProposesValuesButSavesNothing() {
        DocumentOutcome out = service.upload(household, user, png(1));
        assertEquals(DocumentOutcome.Status.NEEDS_CONFIRMATION, out.status());
        assertEquals(DocumentKind.RECEIPT, out.proposal().kind());
        assertEquals(new BigDecimal("8.31"), out.proposal().amount());
        assertEquals(LocalDate.of(2026, 10, 18), out.proposal().date());
        assertEquals(List.of(), out.messages());
        assertEquals(0, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
    }

    @Test
    void confirmingSavesExactlyOneTransaction() {
        DocumentOutcome up = service.upload(household, user, png(1));
        DocumentOutcome out = service.confirm(household, up.importId(), user, goodReceipt(), "groceries", null);
        assertEquals(DocumentOutcome.Status.COMMITTED, out.status());
        assertEquals(1, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
        assertEquals(new BigDecimal("8.31"), jdbc.queryForObject(
                "SELECT amount FROM transactions WHERE id = ?", BigDecimal.class, out.transactionId()));
        assertEquals("PHOTO", jdbc.queryForObject("SELECT source FROM transactions WHERE id = ?", String.class, out.transactionId()));
        assertEquals("POSTED", jdbc.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class, out.transactionId()));
        assertEquals("EXPENSE", jdbc.queryForObject("SELECT direction FROM transactions WHERE id = ?", String.class, out.transactionId()));
        assertEquals("groceries", jdbc.queryForObject("SELECT category_code FROM transactions WHERE id = ?", String.class, out.transactionId()));
        // The proposed values are cleared once committed.
        assertNull(jdbc.queryForObject("SELECT proposed_vendor FROM document_imports WHERE id = ?", String.class, up.importId()));
        // It cannot be confirmed twice.
        assertThrows(IllegalArgumentException.class,
                () -> service.confirm(household, up.importId(), user, goodReceipt(), null, null));
        assertEquals(1, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
    }

    @Test
    void theUserCanCorrectAWronglyReadAmountBeforeConfirming() {
        ocrText = RECEIPT_TEXT.replace("TOTAL             8.31", "TOTAL             8.37");
        DocumentOutcome up = service.upload(household, user, png(1));
        // 7.68 + 0.63 = 8.31, not the 8.37 that was misread, so the proposal already carries a plain question.
        assertEquals(1, up.messages().size());
        assertTrue(up.messages().get(0).contains("8.31") && up.messages().get(0).contains("8.37"), up.messages().toString());
        DocumentOutcome out = service.confirm(household, up.importId(), user, goodReceipt(), null, null);
        assertEquals(DocumentOutcome.Status.COMMITTED, out.status());
    }

    @Test
    void confirmingValuesThatFailTheChecksSavesNothing() {
        DocumentOutcome up = service.upload(household, user, png(1));
        DocumentFields wrong = new DocumentFields(DocumentKind.RECEIPT, "Corner Grocery", new BigDecimal("8.41"),
                LocalDate.of(2026, 10, 18), new BigDecimal("7.68"), new BigDecimal("0.63"));
        DocumentOutcome out = service.confirm(household, up.importId(), user, wrong, null, null);
        assertEquals(DocumentOutcome.Status.NEEDS_CONFIRMATION, out.status());
        assertEquals(List.of("The subtotal 7.68 plus tax 0.63 is 8.31, but the total is 8.41. One of them is misread."), out.messages());
        assertEquals(0, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
        // The same upload can still be confirmed with right values.
        assertEquals(DocumentOutcome.Status.COMMITTED,
                service.confirm(household, up.importId(), user, goodReceipt(), null, null).status());
    }

    @Test
    void aBillIsSavedAsPendingOnItsDueDate() {
        ocrText = BILL_TEXT;
        DocumentOutcome up = service.upload(household, user, png(2));
        assertEquals(DocumentKind.BILL, up.proposal().kind());
        assertEquals(LocalDate.of(2026, 10, 15), up.proposal().date());
        DocumentFields bill = new DocumentFields(DocumentKind.BILL, "Synthetic Power Co", new BigDecimal("142.37"),
                LocalDate.of(2026, 10, 15), null, null);
        DocumentOutcome out = service.confirm(household, up.importId(), user, bill, "utilities", null);
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class, out.transactionId()));
        assertEquals("2026-10-15", jdbc.queryForObject(
                "SELECT posted_on::text FROM transactions WHERE id = ?", String.class, out.transactionId()));
    }

    @Test
    void theSamePhotoIsRefusedUnlessTheEarlierUploadWasCancelled() {
        DocumentOutcome first = service.upload(household, user, png(1));
        assertEquals(DocumentOutcome.Status.ALREADY_IMPORTED, service.upload(household, user, png(1)).status());
        // Another household uploading identical bytes is a separate upload.
        assertEquals(DocumentOutcome.Status.NEEDS_CONFIRMATION, service.upload(otherHousehold, user, png(1)).status());
        service.cancel(household, first.importId(), user);
        assertEquals(DocumentOutcome.Status.NEEDS_CONFIRMATION, service.upload(household, user, png(1)).status());
        assertEquals(0, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
    }

    @Test
    void anUnreadablePhotoStoresNothing() {
        ocrText = "   ";
        DocumentOutcome out = service.upload(household, user, png(1));
        assertEquals(DocumentOutcome.Status.REJECTED, out.status());
        assertTrue(out.messages().get(0).contains("Nothing was saved"), out.messages().toString());
        assertEquals(0, count("SELECT count(*) FROM document_imports WHERE household_id = ?", household));
    }

    @Test
    void textWithNothingUsableStoresNothing() {
        ocrText = "~~ ;; xX";
        assertEquals(DocumentOutcome.Status.REJECTED, service.upload(household, user, png(1)).status());
        assertEquals(0, count("SELECT count(*) FROM document_imports WHERE household_id = ?", household));
    }

    @Test
    void aNameWithNoAmountOrDateIsNotWorthAskingAbout() {
        ocrText = "SOME STORE NAME\nthank you for visiting\n";
        assertEquals(DocumentOutcome.Status.REJECTED, service.upload(household, user, png(1)).status());
        assertEquals(0, count("SELECT count(*) FROM document_imports WHERE household_id = ?", household));
    }

    @Test
    void aFileThatIsNotAnImageIsRejectedBeforeReading() {
        DocumentOutcome out = service.upload(household, user, "not an image".getBytes());
        assertEquals(DocumentOutcome.Status.REJECTED, out.status());
        assertTrue(!ocrCalled);
        byte[] heic = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0};
        assertTrue(service.upload(household, user, heic).messages().get(0).contains("HEIC"));
    }

    @Test
    void anotherHouseholdCannotConfirmOrCancel() {
        DocumentOutcome up = service.upload(household, user, png(1));
        assertThrows(IllegalArgumentException.class,
                () -> service.confirm(otherHousehold, up.importId(), user, goodReceipt(), null, null));
        assertThrows(IllegalArgumentException.class, () -> service.cancel(otherHousehold, up.importId(), user));
    }

    @Test
    void anUnknownCategoryOrForeignAccountIsRefused() {
        DocumentOutcome up = service.upload(household, user, png(1));
        assertThrows(IllegalArgumentException.class,
                () -> service.confirm(household, up.importId(), user, goodReceipt(), "not-a-category", null));
        long foreign = jdbc.queryForObject("""
                INSERT INTO financial_accounts (household_id, institution, account_type, display_name, last4)
                VALUES (?, 'Chase', 'CHECKING', 'Checking', '1234') RETURNING id
                """, Long.class, otherHousehold);
        assertThrows(IllegalArgumentException.class,
                () -> service.confirm(household, up.importId(), user, goodReceipt(), null, foreign));
        assertEquals(0, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
    }
}
