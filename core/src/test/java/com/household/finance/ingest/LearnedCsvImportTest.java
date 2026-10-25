package com.household.finance.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.household.finance.statement.CsvLayout;
import com.household.finance.statement.LayoutProposal;
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
 * The first-time-asks, next-time-remembers flow for a CSV from a bank the app has no built-in reader for.
 * Requires a database (docker compose or the CI postgres service). Each test is rolled back.
 */
@SpringBootTest
@Transactional
class LearnedCsvImportTest {

    private static final String FILE = """
            Date,Description,Amount,Running Bal.
            10/03/2026,GROCERY MART #12 PLANO TX,-45.20,954.80
            10/05/2026,PAYROLL ACME INC,"2,000.00","2,954.80"
            10/09/2026,ELECTRIC CO BILL PAY,-120.00,"2,834.80"
            """;

    @Autowired JdbcTemplate jdbc;

    LearnedCsvImporter importer;
    long household;
    long account;
    long user;

    @BeforeEach
    void seed() {
        ImportService service = new ImportService(jdbc, Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC));
        importer = new LearnedCsvImporter(jdbc, service);
        household = jdbc.queryForObject("INSERT INTO households (name) VALUES ('test') RETURNING id", Long.class);
        account = jdbc.queryForObject("""
                INSERT INTO financial_accounts (household_id, institution, account_type, display_name, last4)
                VALUES (?, 'Some Bank', 'CHECKING', 'Checking', '5678') RETURNING id
                """, Long.class, household);
        user = jdbc.queryForObject("""
                INSERT INTO app_users (household_id, email, display_name, password_hash)
                VALUES (?, 'test@example.com', 'Test', 'x') RETURNING id
                """, Long.class, household);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE household_id = ?", Integer.class, household);
    }

    @Test
    void anUnknownLayoutAsksFirstAndStoresNothing() {
        LearnedCsvImporter.Result r = importer.importCsv(household, account, user, bytes(FILE));
        assertNotNull(r.proposal());
        assertEquals(null, r.outcome());
        assertTrue(r.proposal().complete());
        assertEquals(0, count("transactions"));
        assertEquals(0, count("csv_layouts"));
        assertEquals(0, count("statement_imports"));
    }

    @Test
    void confirmingSavesTheLayoutAndImports() {
        LayoutProposal p = importer.importCsv(household, account, user, bytes(FILE)).proposal();
        LearnedCsvImporter.Result r = importer.confirmLayoutAndImport(household, account, user, bytes(FILE), p.layout());
        assertEquals(ImportOutcome.Status.COMMITTED, r.outcome().status());
        assertEquals(3, r.outcome().imported());
        assertEquals(3, count("transactions"));
        assertEquals(1, count("csv_layouts"));
    }

    @Test
    void theNextFileWithTheSameColumnsNeedsNoQuestions() {
        LayoutProposal p = importer.importCsv(household, account, user, bytes(FILE)).proposal();
        importer.confirmLayoutAndImport(household, account, user, bytes(FILE), p.layout());
        String nextMonth = """
                Date,Description,Amount,Running Bal.
                10/12/2026,COFFEE SHOP PLANO TX,-4.50,"2,830.30"
                """;
        LearnedCsvImporter.Result r = importer.importCsv(household, account, user, bytes(nextMonth));
        assertEquals(null, r.proposal());
        assertEquals(ImportOutcome.Status.COMMITTED, r.outcome().status());
        assertEquals(4, count("transactions"));
    }

    @Test
    void changedColumnsAreAskedAboutAgain() {
        LayoutProposal p = importer.importCsv(household, account, user, bytes(FILE)).proposal();
        importer.confirmLayoutAndImport(household, account, user, bytes(FILE), p.layout());
        String changed = """
                Posted,Details,Value,Balance
                10/12/2026,COFFEE SHOP PLANO TX,-4.50,"2,830.30"
                """;
        LearnedCsvImporter.Result r = importer.importCsv(household, account, user, bytes(changed));
        assertNotNull(r.proposal());
        assertTrue(r.proposal().questions().get(0).contains("different from the ones saved"));
        assertEquals(3, count("transactions"));
    }

    @Test
    void wrongColumnChoicesAreRefusedAndNotSaved() {
        LayoutProposal p = importer.importCsv(household, account, user, bytes(FILE)).proposal();
        CsvLayout good = p.layout();
        CsvLayout wrong = new CsvLayout(good.fingerprint(), 1, good.dateFormat(), 0, good.mode(), good.amountCol(),
                good.debitCol(), good.creditCol(), good.outIsNegative(), good.balanceCol());
        LearnedCsvImporter.Result r = importer.confirmLayoutAndImport(household, account, user, bytes(FILE), wrong);
        assertEquals(ImportOutcome.Status.REJECTED, r.outcome().status());
        assertEquals(0, count("csv_layouts"));
        assertEquals(0, count("transactions"));
    }

    @Test
    void aWrongSignChoiceIsCaughtByTheRunningBalance() {
        LayoutProposal p = importer.importCsv(household, account, user, bytes(FILE)).proposal();
        CsvLayout good = p.layout();
        CsvLayout flipped = new CsvLayout(good.fingerprint(), good.dateCol(), good.dateFormat(), good.descCol(),
                good.mode(), good.amountCol(), good.debitCol(), good.creditCol(), !good.outIsNegative(), good.balanceCol());
        LearnedCsvImporter.Result r = importer.confirmLayoutAndImport(household, account, user, bytes(FILE), flipped);
        assertEquals(ImportOutcome.Status.NEEDS_ANSWERS, r.outcome().status());
        assertEquals(0, count("transactions"));
    }
}
