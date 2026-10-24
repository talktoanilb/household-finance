package com.household.finance.ingest;

import com.household.finance.statement.ParsedStatement;
import com.household.finance.statement.StatementLine;
import com.household.finance.statement.StatementParseException;
import com.household.finance.statement.StatementParser;
import com.household.finance.statement.StatementSummary;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports a bank statement: parse, stage, verify, ask, then save.
 * <p>
 * Nothing reaches {@code transactions} until every open question is answered. A file that cannot be
 * parsed stores nothing and returns a plain message. Every query is scoped by household, so one
 * household can never read or change another's imports.
 * <p>
 * Not yet: re-verifying after an amount correction, updating a pending line when it later clears,
 * checking a statement against a LATER one already saved, and learned layouts for unknown banks (M1c).
 */
@Service
public class ImportService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public ImportService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemDefaultZone());
    }

    ImportService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ImportOutcome importStatement(long householdId, long accountId, long userId,
                                         String layout, StatementParser parser, byte[] fileBytes) {
        requireAccountInHousehold(householdId, accountId);
        String sha = sha256(fileBytes);
        ImportOutcome already = alreadyImported(householdId, accountId, sha);
        if (already != null) {
            return already;
        }

        List<StatementLine> lines;
        try {
            lines = parser.parse(new InputStreamReader(new java.io.ByteArrayInputStream(fileBytes), StandardCharsets.UTF_8));
        } catch (StatementParseException e) {
            return ImportOutcome.rejected("This file does not match the expected " + layout
                    + " format (" + e.getMessage() + "). Nothing was saved. Check the file, or enter the items by hand.");
        } catch (IOException e) {
            return ImportOutcome.rejected("The file could not be read. Nothing was saved.");
        }
        return stage(householdId, accountId, userId, layout, ParsedStatement.ofLines(lines), sha);
    }

    /**
     * For formats that carry totals, such as PDF statements: the caller has already extracted and parsed the
     * file. The statement's own totals, its account digits and the previous statement are all checked.
     */
    @Transactional
    public ImportOutcome importParsed(long householdId, long accountId, long userId,
                                      String layout, ParsedStatement parsed, byte[] fileBytes) {
        requireAccountInHousehold(householdId, accountId);
        String sha = sha256(fileBytes);
        ImportOutcome already = alreadyImported(householdId, accountId, sha);
        if (already != null) {
            return already;
        }
        return stage(householdId, accountId, userId, layout, parsed, sha);
    }

    private ImportOutcome alreadyImported(long householdId, long accountId, String sha) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM statement_imports
                WHERE household_id = ? AND account_id = ? AND file_sha256 = ? AND status <> 'CANCELLED'
                """, Integer.class, householdId, accountId, sha);
        if (count != null && count > 0) {
            return new ImportOutcome(ImportOutcome.Status.ALREADY_IMPORTED, null, 0, 0,
                    List.of("This exact file was already imported for this account. Nothing was changed."));
        }
        return null;
    }

    private ImportOutcome stage(long householdId, long accountId, long userId,
                                String layout, ParsedStatement parsed, String sha) {
        if (parsed.accountLast4() != null) {
            String actual = jdbc.queryForObject("SELECT last4 FROM financial_accounts WHERE id = ?", String.class, accountId);
            if (!parsed.accountLast4().equals(actual)) {
                return ImportOutcome.rejected("This statement is for the account ending " + parsed.accountLast4()
                        + ", but the selected account ends in " + actual
                        + ". Nothing was saved. Pick the matching account and try again.");
            }
        }
        List<StatementLine> lines = parsed.lines();
        StatementSummary summary = parsed.summary();

        long importId = jdbc.queryForObject("""
                INSERT INTO statement_imports (household_id, account_id, file_sha256, layout, status, line_count,
                                               created_by_user_id, period_start, period_end, beginning_balance, ending_balance)
                VALUES (?, ?, ?, ?, 'NEEDS_ANSWERS', ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, householdId, accountId, sha, layout, lines.size(), userId,
                summary == null ? null : summary.periodStart(), summary == null ? null : summary.periodEnd(),
                summary == null ? null : summary.beginningBalance(), summary == null ? null : summary.endingBalance());

        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            StatementLine l = lines.get(i);
            rows.add(new Object[] {importId, i + 1, l.postedOn(), l.description(), l.amount(),
                    l.direction().name(), l.status().name(), l.balance(), l.externalRef()});
        }
        jdbc.batchUpdate("""
                INSERT INTO staged_lines (import_id, line_no, posted_on, description, amount, direction, status, balance, external_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);

        List<Issue> issues = new ArrayList<>(Verifier.verify(lines, LocalDate.now(clock)));
        if (summary != null) {
            issues.addAll(Verifier.verifySummary(lines, summary));
            issues.addAll(continuityIssues(householdId, accountId, summary));
        }
        for (Issue issue : issues) {
            jdbc.update("""
                    INSERT INTO ingestion_questions (household_id, import_id, line_no, kind, message)
                    VALUES (?, ?, ?, ?, ?)
                    """, householdId, importId, issue.wholeFile() ? null : issue.lineNo(), issue.kind().name(), issue.message());
        }
        if (!issues.isEmpty()) {
            return new ImportOutcome(ImportOutcome.Status.NEEDS_ANSWERS, importId, 0, 0,
                    issues.stream().map(Issue::message).toList());
        }
        return commit(householdId, importId, userId);
    }

    /**
     * Compares this statement with the most recent earlier one already saved for the account: a hole between
     * the two periods means a statement is missing, and the balances must join up. Only looks backwards.
     */
    private List<Issue> continuityIssues(long householdId, long accountId, StatementSummary summary) {
        List<Object[]> previous = jdbc.query("""
                SELECT period_end, ending_balance FROM statement_imports
                WHERE household_id = ? AND account_id = ? AND status = 'COMMITTED'
                  AND period_end IS NOT NULL AND ending_balance IS NOT NULL AND period_end < ?
                ORDER BY period_end DESC LIMIT 1
                """, (rs, n) -> new Object[] {rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)},
                householdId, accountId, summary.periodStart());
        if (previous.isEmpty()) {
            return List.of();
        }
        LocalDate previousEnd = (LocalDate) previous.get(0)[0];
        BigDecimal previousEnding = (BigDecimal) previous.get(0)[1];
        long gapDays = ChronoUnit.DAYS.between(previousEnd, summary.periodStart());
        if (gapDays > 1) {
            return List.of(new Issue(0, Issue.Kind.STATEMENT_GAP, "No statement has been imported for "
                    + previousEnd.plusDays(1) + " to " + summary.periodStart().minusDays(1)
                    + ". Transactions in that period are missing."));
        }
        if (previousEnding.compareTo(summary.beginningBalance()) != 0) {
            return List.of(new Issue(0, Issue.Kind.STATEMENT_GAP, "This statement begins at "
                    + summary.beginningBalance().toPlainString() + " but the previous statement ended at "
                    + previousEnding.toPlainString() + "."));
        }
        return List.of();
    }

    @Transactional(readOnly = true)
    public List<OpenQuestion> openQuestions(long householdId) {
        return jdbc.query("""
                SELECT id, import_id, COALESCE(line_no, 0), kind, message
                FROM ingestion_questions WHERE household_id = ? AND status = 'OPEN'
                ORDER BY import_id, COALESCE(line_no, 0), id
                """, (rs, n) -> new OpenQuestion(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5)),
                householdId);
    }

    @Transactional
    public ImportOutcome answer(long householdId, long questionId, long userId, Answer answer) {
        List<long[]> found = jdbc.query("""
                SELECT import_id, COALESCE(line_no, 0) FROM ingestion_questions
                WHERE id = ? AND household_id = ? AND status = 'OPEN'
                """, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}, questionId, householdId);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("No open question with that id in this household.");
        }
        long importId = found.get(0)[0];
        int lineNo = (int) found.get(0)[1];

        BigDecimal corrected = null;
        if (answer.type() == Answer.Type.CORRECT_AMOUNT) {
            corrected = answer.correctedAmount();
            if (lineNo == 0) {
                throw new IllegalArgumentException("An amount cannot be corrected on a whole-file question.");
            }
            if (corrected == null || corrected.signum() < 0 || corrected.scale() > 2) {
                throw new IllegalArgumentException("The corrected amount must be zero or more, with at most 2 decimals.");
            }
        }

        jdbc.update("""
                UPDATE ingestion_questions
                SET status = 'ANSWERED', answer = ?, corrected_amount = ?, answered_at = now(), answered_by_user_id = ?
                WHERE id = ?
                """, answer.type().name(), corrected, userId, questionId);

        if (answer.type() == Answer.Type.EXCLUDE && lineNo == 0) {
            return cancel(householdId, importId, userId);
        }
        if (answer.type() == Answer.Type.EXCLUDE) {
            jdbc.update("UPDATE staged_lines SET excluded = TRUE WHERE import_id = ? AND line_no = ?", importId, lineNo);
        } else if (answer.type() == Answer.Type.CORRECT_AMOUNT) {
            jdbc.update("UPDATE staged_lines SET amount = ? WHERE import_id = ? AND line_no = ?", corrected, importId, lineNo);
        }

        Integer open = jdbc.queryForObject(
                "SELECT count(*) FROM ingestion_questions WHERE import_id = ? AND status = 'OPEN'", Integer.class, importId);
        if (open != null && open > 0) {
            return new ImportOutcome(ImportOutcome.Status.NEEDS_ANSWERS, importId, 0, 0,
                    List.of(open + " question(s) still need an answer."));
        }
        return commit(householdId, importId, userId);
    }

    @Transactional
    public ImportOutcome cancel(long householdId, long importId, long userId) {
        int changed = jdbc.update("""
                UPDATE statement_imports SET status = 'CANCELLED', completed_at = now()
                WHERE id = ? AND household_id = ? AND status = 'NEEDS_ANSWERS'
                """, importId, householdId);
        if (changed == 0) {
            throw new IllegalArgumentException("No pending import with that id in this household.");
        }
        jdbc.update("DELETE FROM staged_lines WHERE import_id = ?", importId);
        jdbc.update("UPDATE ingestion_questions SET status = 'ANSWERED' WHERE import_id = ? AND status = 'OPEN'", importId);
        audit(householdId, userId, "STATEMENT_IMPORT_CANCELLED", importId);
        return new ImportOutcome(ImportOutcome.Status.CANCELLED, importId, 0, 0,
                List.of("Import cancelled. Nothing was saved."));
    }

    private ImportOutcome commit(long householdId, long importId, long userId) {
        int inserted = jdbc.update("""
                INSERT INTO transactions (household_id, account_id, posted_on, vendor, amount, direction,
                                          source, status, external_ref, entered_by_user_id)
                SELECT i.household_id, i.account_id, s.posted_on, s.description, s.amount, s.direction,
                       'STATEMENT', s.status, s.external_ref, ?
                FROM staged_lines s
                JOIN statement_imports i ON i.id = s.import_id
                WHERE s.import_id = ? AND NOT s.excluded
                  AND NOT EXISTS (
                      SELECT 1 FROM transactions t
                      WHERE t.household_id = i.household_id
                        AND COALESCE(t.account_id, 0) = COALESCE(i.account_id, 0)
                        AND t.external_ref = s.external_ref)
                """, userId, importId);
        Integer eligible = jdbc.queryForObject(
                "SELECT count(*) FROM staged_lines WHERE import_id = ? AND NOT excluded", Integer.class, importId);
        int skipped = (eligible == null ? 0 : eligible) - inserted;

        jdbc.update("DELETE FROM staged_lines WHERE import_id = ?", importId);
        jdbc.update("""
                UPDATE statement_imports
                SET status = 'COMMITTED', imported_count = ?, skipped_count = ?, completed_at = now()
                WHERE id = ?
                """, inserted, skipped, importId);
        audit(householdId, userId, "STATEMENT_IMPORTED", importId);

        String message = "Saved " + inserted + " transaction(s)"
                + (skipped > 0 ? "; " + skipped + " already existed and were skipped." : ".");
        return new ImportOutcome(ImportOutcome.Status.COMMITTED, importId, inserted, skipped, List.of(message));
    }

    private void audit(long householdId, long userId, String action, long importId) {
        jdbc.update("""
                INSERT INTO audit_log (household_id, user_id, action, entity, entity_id)
                VALUES (?, ?, ?, 'statement_imports', ?)
                """, householdId, userId, action, importId);
    }

    private void requireAccountInHousehold(long householdId, long accountId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM financial_accounts WHERE id = ? AND household_id = ?", Integer.class, accountId, householdId);
        if (n == null || n == 0) {
            throw new IllegalArgumentException("That account does not belong to this household.");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
