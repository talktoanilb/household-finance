package com.household.finance;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the schema rules that CLAUDE.md and the spec depend on. Each test runs in a transaction
 * that is rolled back. Requires PostgreSQL (docker compose or the CI service).
 */
@SpringBootTest
@Transactional
class SchemaConstraintsTests {

    @Autowired
    JdbcTemplate jdbc;

    long household;

    @BeforeEach
    void seed() {
        household = jdbc.queryForObject("INSERT INTO households (name) VALUES ('test') RETURNING id", Long.class);
    }

    private void insertTransaction(long householdId, Long accountId, String amount, String externalRef) {
        jdbc.update("""
                INSERT INTO transactions (household_id, account_id, posted_on, vendor, amount, direction, source, external_ref)
                VALUES (?, ?, DATE '2026-10-01', 'SYNTHETIC', ?::numeric, 'EXPENSE', 'STATEMENT', ?)
                """, householdId, accountId, amount, externalRef);
    }

    @Test
    void fullCardNumberIsRejected() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO financial_accounts (household_id, institution, account_type, display_name, last4) "
                        + "VALUES (?, 'Bank', 'CHECKING', 'x', '12345678')", household));
    }

    @Test
    void negativeAmountIsRejected() {
        assertThrows(DataIntegrityViolationException.class, () -> insertTransaction(household, null, "-5.00", "r1"));
    }

    @Test
    void sameExternalRefTwiceIsRejectedWithAnAccount() {
        long account = jdbc.queryForObject("""
                INSERT INTO financial_accounts (household_id, institution, account_type, display_name, last4)
                VALUES (?, 'Bank', 'CHECKING', 'x', '1234') RETURNING id
                """, Long.class, household);
        insertTransaction(household, account, "5.00", "same");
        assertThrows(DataIntegrityViolationException.class, () -> insertTransaction(household, account, "5.00", "same"));
    }

    /** Regression test for the V1 gap fixed in V2: NULL account_id used to bypass the unique index. */
    @Test
    void sameExternalRefTwiceIsRejectedWithoutAnAccount() {
        insertTransaction(household, null, "5.00", "same");
        assertThrows(DataIntegrityViolationException.class, () -> insertTransaction(household, null, "5.00", "same"));
    }

    @Test
    void sameExternalRefInDifferentHouseholdsIsAllowed() {
        long other = jdbc.queryForObject("INSERT INTO households (name) VALUES ('other') RETURNING id", Long.class);
        insertTransaction(household, null, "5.00", "same");
        insertTransaction(other, null, "5.00", "same");
    }

    @Test
    void manualEntriesWithoutExternalRefAreNotConstrained() {
        insertTransaction(household, null, "5.00", null);
        insertTransaction(household, null, "5.00", null);
    }
}
