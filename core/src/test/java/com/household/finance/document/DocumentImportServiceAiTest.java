package com.household.finance.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.household.finance.document.ai.AiReading;
import com.household.finance.document.ai.FakeAiReader;
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
 * The AI-reading merge, with a fake AiReader so no network call is made.
 * Hand-checked: the receipt is 4.19 + 3.49 = 7.68 subtotal, plus 0.63 tax = 8.31 total.
 */
@SpringBootTest
@Transactional
class DocumentImportServiceAiTest {

    private static final String RECEIPT_WITH_TOTAL = """
            CORNER GROCERY
            123 Main St
            10/18/2026 14:32
            MILK 1 GAL        4.19
            BREAD             3.49
            SUBTOTAL          7.68
            TAX 8.25%         0.63
            TOTAL             8.31
            """;

    /** No total line, so the local reading finds only the vendor and the due date. */
    private static final String BILL_WITHOUT_AMOUNT = """
            SYNTHETIC POWER CO
            Due date: 10/15/2026
            """;

    private static final String HAS_A_CARD_NUMBER = RECEIPT_WITH_TOTAL + "CARD 4242 4242 4242 4242\n";

    @Autowired JdbcTemplate jdbc;

    String ocrText = RECEIPT_WITH_TOTAL;
    FakeAiReader ai;
    DocumentImportService service;
    long household;
    long user;

    @BeforeEach
    void seed() {
        OcrEngine fake = image -> ocrText;
        ai = new FakeAiReader();
        service = new DocumentImportService(jdbc, fake, ai, Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC));
        household = jdbc.queryForObject("INSERT INTO households (name) VALUES ('test') RETURNING id", Long.class);
        user = jdbc.queryForObject("""
                INSERT INTO app_users (household_id, email, display_name, password_hash)
                VALUES (?, 'test@example.com', 'Test', 'x') RETURNING id
                """, Long.class, household);
    }

    private static byte[] png(int seed) {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, (byte) seed, 1, 2, 3};
    }

    @Test
    void withUseAiFalseTheAiReaderIsNeverCalled() {
        ai.nextReading = new AiReading(DocumentKind.RECEIPT, "Ignored Vendor", new BigDecimal("1.00"), null, null, null, null);
        DocumentOutcome out = service.upload(household, user, png(1), false);
        assertEquals(new BigDecimal("8.31"), out.proposal().amount());
        assertNull(ai.lastImageSeen);
    }

    @Test
    void aiFillsOnlyWhatTheLocalReadingLeftBlank() {
        ocrText = BILL_WITHOUT_AMOUNT;
        ai.nextReading = new AiReading(DocumentKind.BILL, null, new BigDecimal("142.37"), null, null, null, null);
        DocumentOutcome out = service.upload(household, user, png(1), true);
        assertEquals(DocumentOutcome.Status.NEEDS_CONFIRMATION, out.status());
        assertEquals(DocumentKind.BILL, out.proposal().kind());               // local was UNKNOWN; AI filled it
        assertEquals(new BigDecimal("142.37"), out.proposal().amount());      // local was null; AI filled it
        assertEquals(LocalDate.of(2026, 10, 15), out.proposal().date());      // local already had the due date; unchanged
        assertTrue(out.messages().stream().anyMatch(m -> m.contains("filled in by AI")));
    }

    @Test
    void aiNeverReplacesAValueTheLocalReadingAlreadyFound() {
        ai.nextReading = new AiReading(DocumentKind.BILL, "Some Other Vendor", new BigDecimal("99.99"),
                new BigDecimal("1.00"), new BigDecimal("1.00"), LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 1));
        DocumentOutcome out = service.upload(household, user, png(1), true);
        // Every field was already read locally, so none of the AI's (very different) values are used.
        assertEquals(new BigDecimal("8.31"), out.proposal().amount());
        assertEquals(LocalDate.of(2026, 10, 18), out.proposal().date());
    }

    @Test
    void whenAiIsNotSetUpTheLocalReadingIsUsedAlone() {
        ai.enabled = false;
        DocumentOutcome out = service.upload(household, user, png(1), true);
        assertEquals(new BigDecimal("8.31"), out.proposal().amount());
        assertNull(ai.lastImageSeen);
        assertTrue(out.messages().stream().anyMatch(m -> m.contains("not set up")));
    }

    @Test
    void whenAiFailsTheLocalReadingIsUsedAloneAndNothingCrashes() {
        ai.nextFailure = new RuntimeException("network unreachable");
        DocumentOutcome out = service.upload(household, user, png(1), true);
        assertEquals(DocumentOutcome.Status.NEEDS_CONFIRMATION, out.status());
        assertEquals(new BigDecimal("8.31"), out.proposal().amount());
        assertTrue(out.messages().stream().anyMatch(m -> m.contains("did not complete")));
    }

    @Test
    void aPhotoThatMayShowAFullCardNumberIsNotSentToAi() {
        ocrText = HAS_A_CARD_NUMBER;
        DocumentOutcome out = service.upload(household, user, png(1), true);
        assertNull(ai.lastImageSeen);
        assertTrue(out.messages().stream().anyMatch(m -> m.contains("card or account number")));
    }

    @Test
    void rePickingTheSamePendingPhotoResumesInsteadOfBeingRefused() {
        DocumentOutcome first = service.upload(household, user, png(1), false);
        DocumentOutcome second = service.upload(household, user, png(1), false);
        assertEquals(DocumentOutcome.Status.NEEDS_CONFIRMATION, second.status());   // not ALREADY_IMPORTED
        assertEquals(first.importId(), second.importId());
        assertEquals(new BigDecimal("8.31"), second.proposal().amount());
        assertEquals(0, ai.callCount);   // useAi was false both times
    }

    @Test
    void onlyACommittedUploadOfTheSamePhotoIsRefused() {
        DocumentOutcome up = service.upload(household, user, png(1), false);
        service.confirm(household, up.importId(), user, up.proposal(), null, null);
        DocumentOutcome again = service.upload(household, user, png(1), false);
        assertEquals(DocumentOutcome.Status.ALREADY_IMPORTED, again.status());
    }

    @Test
    void resumingWithAiFillsBlanksFromTheStoredPendingValues() {
        ocrText = BILL_WITHOUT_AMOUNT;
        DocumentOutcome first = service.upload(household, user, png(1), false);   // local only: kind UNKNOWN, amount null
        ai.nextReading = new AiReading(DocumentKind.BILL, null, new BigDecimal("142.37"), null, null, null, null);
        DocumentOutcome second = service.upload(household, user, png(1), true);
        assertEquals(first.importId(), second.importId());
        assertEquals(1, ai.callCount);
        assertEquals(DocumentKind.BILL, second.proposal().kind());
        assertEquals(new BigDecimal("142.37"), second.proposal().amount());
        assertTrue(second.messages().stream().anyMatch(m -> m.contains("filled in by AI")));
    }

    @Test
    void aiIsNotTriedTwiceOnTheSamePendingUpload() {
        ocrText = BILL_WITHOUT_AMOUNT;
        service.upload(household, user, png(1), false);
        ai.nextReading = new AiReading(DocumentKind.BILL, null, new BigDecimal("142.37"), null, null, null, null);
        service.upload(household, user, png(1), true);          // first (and only) real AI call
        DocumentOutcome third = service.upload(household, user, png(1), true);   // ticked again on the same upload
        assertEquals(1, ai.callCount);                            // not called a second time
        assertTrue(third.messages().stream().anyMatch(m -> m.contains("already tried")));
        assertEquals(new BigDecimal("142.37"), third.proposal().amount());   // the earlier AI value is kept
    }

    @Test
    void aFailedAiAttemptCanStillBeRetriedOnTheSamePendingUpload() {
        ocrText = BILL_WITHOUT_AMOUNT;
        service.upload(household, user, png(1), false);
        ai.nextFailure = new RuntimeException("network unreachable");
        DocumentOutcome failed = service.upload(household, user, png(1), true);
        assertTrue(failed.messages().stream().anyMatch(m -> m.contains("did not complete")));

        ai.nextFailure = null;
        ai.nextReading = new AiReading(DocumentKind.BILL, null, new BigDecimal("142.37"), null, null, null, null);
        DocumentOutcome retried = service.upload(household, user, png(1), true);
        assertEquals(2, ai.callCount);   // the failed attempt did not count against the "already tried" guard
        assertEquals(new BigDecimal("142.37"), retried.proposal().amount());
    }

    @Test
    void confirmingAnAiFilledProposalStillRunsTheSameJavaChecks() {
        ocrText = BILL_WITHOUT_AMOUNT;
        ai.nextReading = new AiReading(DocumentKind.BILL, "Synthetic Power Co", new BigDecimal("142.37"), null, null, null, null);
        DocumentOutcome up = service.upload(household, user, png(1), true);
        DocumentOutcome out = service.confirm(household, up.importId(), user, up.proposal(), null, null);
        assertEquals(DocumentOutcome.Status.COMMITTED, out.status());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM transactions WHERE household_id = ?", Integer.class, household));

        // A value the AI invented that fails verification (negative) is still refused, exactly like a local one.
        DocumentOutcome up2 = service.upload(household, user, png(2), true);
        DocumentFields bad = new DocumentFields(DocumentKind.BILL, "Synthetic Power Co", new BigDecimal("-5.00"),
                LocalDate.of(2026, 10, 15), null, null);
        DocumentOutcome rejected = service.confirm(household, up2.importId(), user, bad, null, null);
        assertEquals(DocumentOutcome.Status.NEEDS_CONFIRMATION, rejected.status());
        assertFalse(rejected.messages().isEmpty());
    }
}
