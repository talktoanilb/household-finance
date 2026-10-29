package com.household.finance.web;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Who is using the app, until real login exists (M3).
 * <p>
 * There is no authentication yet, so the app acts as the first household member in the database and is only safe on
 * your own machine: the server listens on 127.0.0.1 unless BIND_ADDRESS says otherwise. Do not expose it to a network.
 * Every service call below this layer is already scoped by household, so replacing this class with a real login later
 * does not change the services.
 */
@Component
public class LocalIdentity {

    public record Who(long householdId, long userId) {}

    private final JdbcTemplate jdbc;

    public LocalIdentity(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Who current() {
        List<Who> found = jdbc.query("SELECT household_id, id FROM app_users ORDER BY id LIMIT 1",
                (rs, n) -> new Who(rs.getLong(1), rs.getLong(2)));
        if (found.isEmpty()) {
            throw new NoLocalUserException();
        }
        return found.get(0);
    }
}
