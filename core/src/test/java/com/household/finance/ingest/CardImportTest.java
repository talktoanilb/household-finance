package com.household.finance.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.household.finance.statement.CardPdfStatementParser;
import com.household.finance.statement.CardPdfStatementParser.Issuer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Card statements through the import flow against PostgreSQL (rolled back). Requires a database.
 * Uses the synthetic Chase card fixture: payment 50.00 (a transfer), purchases 30.00 + 12.34.
 */
@SpringBootTest
@Transactional
class CardImportTest {

    @Autowired JdbcTemplate jdbc;

    ImportService service;
    long household;
    long account;
    long user;

    @BeforeEach
    void seed() {
        service = new ImportService(jdbc, Clock.fixed(Instant.parse("2027-01-20T12:00:00Z"), ZoneOffset.UTC));
        household = jdbc.queryForObject("INSERT INTO households (name) VALUES ('test') RETURNING id", Long.class);
        account = jdbc.queryForObject("""
                INSERT INTO financial_accounts (household_id, institution, account_type, display_name, last4)
                VALUES (?, 'Chase', 'CREDIT_CARD', 'Card', '4242') RETURNING id
                """, Long.class, household);
        user = jdbc.queryForObject("""
                INSERT INTO app_users (household_id, email, display_name, password_hash)
                VALUES (?, 'test@example.com', 'Test', 'x') RETURNING id
                """, Long.class, household);
    }

    private String text() throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/chase_card_statement_synthetic.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ImportOutcome run(String text) {
        return service.importParsed(household, account, user, "CHASE_CARD_PDF",
                new CardPdfStatementParser(Issuer.CHASE_CARD).parse(text), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void cleanCardStatementIsSavedAndThePaymentIsATransfer() throws Exception {
        ImportOutcome out = run(text());
        assertEquals(ImportOutcome.Status.COMMITTED, out.status());
        assertEquals(3, out.imported());
        assertEquals(new BigDecimal("42.34"), jdbc.queryForObject(
                "SELECT sum(amount) FROM transactions WHERE household_id = ? AND direction = 'EXPENSE'",
                BigDecimal.class, household));
        assertEquals(new BigDecimal("50.00"), jdbc.queryForObject(
                "SELECT sum(amount) FROM transactions WHERE household_id = ? AND direction = 'TRANSFER_IN'",
                BigDecimal.class, household));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM transactions WHERE household_id = ? AND direction = 'INCOME'",
                Integer.class, household));
    }

    @Test
    void aStatementForAnotherCardIsRejected() throws Exception {
        jdbc.update("UPDATE financial_accounts SET last4 = '9999' WHERE id = ?", account);
        ImportOutcome out = run(text());
        assertEquals(ImportOutcome.Status.REJECTED, out.status());
        assertTrue(out.messages().get(0).contains("4242"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM transactions WHERE household_id = ?", Integer.class, household));
    }

    @Test
    void totalsThatDoNotAddUpAskInsteadOfSaving() throws Exception {
        // A fee the lines do not show: the statement says the new balance is 5.00 higher than the lines explain.
        ImportOutcome out = run(text().replace("New Balance                          $42.34",
                "New Balance                          $47.34"));
        assertEquals(ImportOutcome.Status.NEEDS_ANSWERS, out.status());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM transactions WHERE household_id = ?", Integer.class, household));
        assertEquals(1, service.openQuestions(household).size());
    }
}
