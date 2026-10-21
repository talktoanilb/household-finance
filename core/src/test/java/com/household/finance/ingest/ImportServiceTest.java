package com.household.finance.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.household.finance.statement.ChaseCheckingParser;
import com.household.finance.statement.CitiCostcoVisaParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end tests of the import flow against PostgreSQL. Each test is rolled back.
 * Requires a database (docker compose or the CI postgres service).
 * Expected numbers are hand-checked against the synthetic fixtures:
 * the Chase file has 7 lines (6 expenses, 1 deposit); the Citi file has 5 lines, the last one pending.
 */
@SpringBootTest
@Transactional
class ImportServiceTest {

    private static final String CHASE_HEAD = "Details,Posting Date,Description,Amount,Type,Balance,Check or Slip #\n";

    @Autowired JdbcTemplate jdbc;

    /** Built by hand with a fixed clock so the fixture dates (October 2026) never age out of the plausibility check. */
    ImportService service;

    long household;
    long account;
    long user;

    @BeforeEach
    void seed() {
        service = new ImportService(jdbc, Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC));
        household = jdbc.queryForObject("INSERT INTO households (name) VALUES ('test') RETURNING id", Long.class);
        account = jdbc.queryForObject("""
                INSERT INTO financial_accounts (household_id, institution, account_type, display_name, last4)
                VALUES (?, 'Chase', 'CHECKING', 'Checking', '1234') RETURNING id
                """, Long.class, household);
        user = jdbc.queryForObject("""
                INSERT INTO app_users (household_id, email, display_name, password_hash)
                VALUES (?, 'test@example.com', 'Test', 'x') RETURNING id
                """, Long.class, household);
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ImportOutcome importChase(String csv) {
        return service.importStatement(household, account, user, "CHASE_CHECKING",
                new ChaseCheckingParser(), csv.getBytes(StandardCharsets.UTF_8));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void cleanFileIsSavedInFull() throws Exception {
        ImportOutcome out = importChase(fixture("chase_checking_synthetic.csv"));
        assertEquals(ImportOutcome.Status.COMMITTED, out.status());
        assertEquals(7, out.imported());
        assertEquals(7, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
        // Hand-checked: expenses 142.37+15.49+86.13+86.13+54.20+118.00 = 502.32
        assertEquals(new BigDecimal("502.32"), jdbc.queryForObject(
                "SELECT sum(amount) FROM transactions WHERE household_id = ? AND direction = 'EXPENSE'", BigDecimal.class, household));
        assertEquals(0, count("SELECT count(*) FROM staged_lines"));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE household_id = ? AND action = 'STATEMENT_IMPORTED'", household));
    }

    @Test
    void sameFileTwiceChangesNothing() throws Exception {
        String csv = fixture("chase_checking_synthetic.csv");
        importChase(csv);
        ImportOutcome again = importChase(csv);
        assertEquals(ImportOutcome.Status.ALREADY_IMPORTED, again.status());
        assertEquals(7, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
    }

    @Test
    void overlappingFileSkipsLinesAlreadySaved() throws Exception {
        String csv = fixture("chase_checking_synthetic.csv");
        importChase(csv);
        // A later export that repeats all 7 lines and adds one new line with a correct balance (5330.60 - 20.00).
        String longer = csv.stripTrailing() + "\nDEBIT,10/08/2026,SYNTHETIC COFFEE,-20.00,DEBIT_CARD,5310.60,\n";
        ImportOutcome out = importChase(longer);
        assertEquals(ImportOutcome.Status.COMMITTED, out.status());
        assertEquals(1, out.imported());
        assertEquals(7, out.skippedDuplicates());
        assertEquals(8, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
    }

    @Test
    void wrongBalanceStagesTheFileAndSavesNothingUntilAnswered() throws Exception {
        String tampered = fixture("chase_checking_synthetic.csv").replace("5330.60", "5330.00");
        ImportOutcome out = importChase(tampered);
        assertEquals(ImportOutcome.Status.NEEDS_ANSWERS, out.status());
        assertEquals(0, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
        List<OpenQuestion> questions = service.openQuestions(household);
        assertEquals(1, questions.size());
        assertEquals(7, questions.get(0).lineNo());

        ImportOutcome done = service.answer(household, questions.get(0).id(), user, Answer.confirm());
        assertEquals(ImportOutcome.Status.COMMITTED, done.status());
        assertEquals(7, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
        assertEquals(List.of(), service.openQuestions(household));
    }

    @Test
    void excludingTheLineDropsOnlyThatLine() throws Exception {
        String tampered = fixture("chase_checking_synthetic.csv").replace("5330.60", "5330.00");
        importChase(tampered);
        long q = service.openQuestions(household).get(0).id();
        service.answer(household, q, user, Answer.exclude());
        assertEquals(6, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
    }

    @Test
    void correctingTheAmountSavesTheCorrectedValue() throws Exception {
        String tampered = fixture("chase_checking_synthetic.csv").replace("5330.60", "5330.00");
        importChase(tampered);
        long q = service.openQuestions(household).get(0).id();
        service.answer(household, q, user, Answer.correctAmount(new BigDecimal("117.40")));
        assertEquals(new BigDecimal("117.40"), jdbc.queryForObject(
                "SELECT amount FROM transactions WHERE household_id = ? AND posted_on = DATE '2026-10-07'", BigDecimal.class, household));
    }

    @Test
    void invalidCorrectionIsRejectedAndTheQuestionStaysOpen() throws Exception {
        importChase(fixture("chase_checking_synthetic.csv").replace("5330.60", "5330.00"));
        long q = service.openQuestions(household).get(0).id();
        assertThrows(IllegalArgumentException.class,
                () -> service.answer(household, q, user, Answer.correctAmount(new BigDecimal("-1.00"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.answer(household, q, user, Answer.correctAmount(new BigDecimal("1.005"))));
    }

    @Test
    void cancellingDiscardsEverythingAndAllowsAReUpload() throws Exception {
        String tampered = fixture("chase_checking_synthetic.csv").replace("5330.60", "5330.00");
        ImportOutcome staged = importChase(tampered);
        ImportOutcome cancelled = service.cancel(household, staged.importId(), user);
        assertEquals(ImportOutcome.Status.CANCELLED, cancelled.status());
        assertEquals(0, count("SELECT count(*) FROM transactions WHERE household_id = ?", household));
        assertEquals(0, count("SELECT count(*) FROM staged_lines"));
        assertEquals(ImportOutcome.Status.NEEDS_ANSWERS, importChase(tampered).status());
    }

    @Test
    void unparseableFileStoresNothingAndSaysSoPlainly() {
        ImportOutcome out = importChase("Date,Memo\n10/01/2026,SECRET VENDOR\n");
        assertEquals(ImportOutcome.Status.REJECTED, out.status());
        assertEquals(0, count("SELECT count(*) FROM statement_imports"));
        assertTrue(out.messages().get(0).contains("Nothing was saved"));
        assertTrue(!out.messages().get(0).contains("SECRET"));
    }

    @Test
    void accountFromAnotherHouseholdIsRefused() throws Exception {
        long other = jdbc.queryForObject("INSERT INTO households (name) VALUES ('other') RETURNING id", Long.class);
        long foreignAccount = jdbc.queryForObject("""
                INSERT INTO financial_accounts (household_id, institution, account_type, display_name, last4)
                VALUES (?, 'Chase', 'CHECKING', 'Other', '9999') RETURNING id
                """, Long.class, other);
        assertThrows(IllegalArgumentException.class, () -> service.importStatement(household, foreignAccount, user,
                "CHASE_CHECKING", new ChaseCheckingParser(), fixture("chase_checking_synthetic.csv").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void anotherHouseholdCannotAnswerMyQuestion() throws Exception {
        importChase(fixture("chase_checking_synthetic.csv").replace("5330.60", "5330.00"));
        long q = service.openQuestions(household).get(0).id();
        long other = jdbc.queryForObject("INSERT INTO households (name) VALUES ('other') RETURNING id", Long.class);
        assertThrows(IllegalArgumentException.class, () -> service.answer(other, q, user, Answer.confirm()));
        assertEquals(List.of(), service.openQuestions(other));
    }

    @Test
    void pendingCardLineIsSavedAsPending() throws Exception {
        long card = jdbc.queryForObject("""
                INSERT INTO financial_accounts (household_id, institution, account_type, display_name, last4)
                VALUES (?, 'Citi', 'CREDIT_CARD', 'Costco Visa', '6371') RETURNING id
                """, Long.class, household);
        ImportOutcome out = service.importStatement(household, card, user, "CITI_COSTCO_VISA",
                new CitiCostcoVisaParser(), fixture("citi_costco_visa_synthetic.csv").getBytes(StandardCharsets.UTF_8));
        assertEquals(ImportOutcome.Status.COMMITTED, out.status());
        assertEquals(5, out.imported());
        assertEquals(1, count("SELECT count(*) FROM transactions WHERE household_id = ? AND status = 'PENDING'", household));
        // Hand-checked: charges 213.45+15.49+24.10+38.75 = 291.79
        assertEquals(new BigDecimal("291.79"), jdbc.queryForObject(
                "SELECT sum(amount) FROM transactions WHERE household_id = ? AND direction = 'EXPENSE'", BigDecimal.class, household));
    }
}
