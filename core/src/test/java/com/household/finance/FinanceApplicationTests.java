package com.household.finance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the application context starts and Flyway applies every migration
 * against a real PostgreSQL. Requires a database (docker compose or the CI postgres service).
 */
@SpringBootTest
class FinanceApplicationTests {

    @Test
    void contextLoadsAndMigrationsApply() {
        // Intentionally empty: a successful context start proves the schema migrates cleanly.
    }
}
