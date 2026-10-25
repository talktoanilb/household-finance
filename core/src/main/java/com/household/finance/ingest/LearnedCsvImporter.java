package com.household.finance.ingest;

import com.household.finance.statement.CsvLayout;
import com.household.finance.statement.CsvLayoutDetector;
import com.household.finance.statement.LayoutProposal;
import com.household.finance.statement.LearnedCsvParser;
import com.household.finance.statement.StatementParseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports a CSV from any bank. The first time a file shape is seen for an account the app does not guess: it
 * returns a {@link LayoutProposal} (what it thinks each column is, plus questions in plain words) and stores
 * nothing. When the user confirms, the layout is saved for that account and the file goes through the normal
 * import flow (verification, questions, then commit). The next file with the same columns needs no questions;
 * a file with different columns is a new shape, so the user is asked again.
 */
@Service
public class LearnedCsvImporter {

    /** Exactly one of {@code outcome} (the file was processed) or {@code proposal} (the user must confirm columns). */
    public record Result(ImportOutcome outcome, LayoutProposal proposal) {}

    static final int MAX_BYTES = 10 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final ImportService service;

    public LearnedCsvImporter(JdbcTemplate jdbc, ImportService service) {
        this.jdbc = jdbc;
        this.service = service;
    }

    @Transactional
    public Result importCsv(long householdId, long accountId, long userId, byte[] file) {
        String problem = fileProblem(file);
        if (problem != null) {
            return new Result(ImportOutcome.rejected(problem), null);
        }
        String fingerprint;
        try {
            fingerprint = CsvLayoutDetector.fingerprint(reader(file));
        } catch (IOException | StatementParseException e) {
            return new Result(ImportOutcome.rejected("The file could not be read as a CSV. Nothing was saved."), null);
        }
        CsvLayout saved = fingerprint == null ? null : savedLayout(householdId, accountId, fingerprint);
        if (saved != null) {
            return new Result(run(householdId, accountId, userId, saved, file), null);
        }
        LayoutProposal proposal;
        try {
            proposal = CsvLayoutDetector.detect(reader(file));
        } catch (IOException | StatementParseException e) {
            return new Result(ImportOutcome.rejected("The file could not be read as a CSV. Nothing was saved."), null);
        }
        if (hasAnySavedLayout(householdId, accountId)) {
            List<String> questions = new ArrayList<>(proposal.questions());
            questions.add(0, "The columns in this file are different from the ones saved for this account,"
                    + " so please confirm how to read this version.");
            proposal = new LayoutProposal(proposal.complete(), proposal.layout(), proposal.columnNames(),
                    List.copyOf(questions), proposal.preview());
        }
        return new Result(null, proposal);
    }

    /**
     * The user confirmed (or corrected) the proposed columns. The layout is tried on the whole file first and is
     * only saved if every row reads cleanly; then the file is imported.
     */
    @Transactional
    public Result confirmLayoutAndImport(long householdId, long accountId, long userId, byte[] file, CsvLayout choice) {
        String problem = fileProblem(file);
        if (problem != null) {
            return new Result(ImportOutcome.rejected(problem), null);
        }
        String fingerprint;
        try {
            fingerprint = CsvLayoutDetector.fingerprint(reader(file));
        } catch (IOException | StatementParseException e) {
            return new Result(ImportOutcome.rejected("The file could not be read as a CSV. Nothing was saved."), null);
        }
        if (fingerprint == null) {
            return new Result(ImportOutcome.rejected("No row with a date and an amount was found. Nothing was saved."), null);
        }
        String invalid = invalidChoice(choice);
        if (invalid != null) {
            return new Result(ImportOutcome.rejected(invalid + " Nothing was saved."), null);
        }
        CsvLayout layout = new CsvLayout(fingerprint, choice.dateCol(), choice.dateFormat(), choice.descCol(),
                choice.mode(), choice.amountCol(), choice.debitCol(), choice.creditCol(), choice.outIsNegative(),
                choice.balanceCol());
        try {
            new LearnedCsvParser(layout, isCreditCard(accountId)).parse(reader(file));
        } catch (StatementParseException e) {
            return new Result(ImportOutcome.rejected("Those columns do not fit the file (" + e.getMessage()
                    + "). Nothing was saved. Please check the column choices."), null);
        } catch (IOException e) {
            return new Result(ImportOutcome.rejected("The file could not be read. Nothing was saved."), null);
        }
        save(householdId, accountId, userId, layout);
        return new Result(run(householdId, accountId, userId, layout, file), null);
    }

    private ImportOutcome run(long householdId, long accountId, long userId, CsvLayout layout, byte[] file) {
        return service.importStatement(householdId, accountId, userId, "LEARNED_CSV",
                new LearnedCsvParser(layout, isCreditCard(accountId)), file);
    }

    private static String fileProblem(byte[] file) {
        if (file.length > MAX_BYTES) {
            return "The file is larger than 10 MB. Nothing was saved.";
        }
        if (file.length == 0) {
            return "The file is empty. Nothing was saved.";
        }
        return null;
    }

    private static String invalidChoice(CsvLayout c) {
        if (c.mode() == null || !CsvLayoutDetector.isSupportedDateFormat(c.dateFormat())) {
            return "The date style is not one I can read.";
        }
        if (c.dateCol() < 0 || c.descCol() < 0) {
            return "A date column and a description column are required.";
        }
        if (c.mode() == CsvLayout.AmountMode.SINGLE && c.amountCol() < 0) {
            return "An amount column is required.";
        }
        if (c.mode() == CsvLayout.AmountMode.DEBIT_CREDIT && (c.debitCol() < 0 || c.creditCol() < 0)) {
            return "Both a money-out and a money-in column are required.";
        }
        return null;
    }

    private static InputStreamReader reader(byte[] file) {
        return new InputStreamReader(new ByteArrayInputStream(file), StandardCharsets.UTF_8);
    }

    private boolean isCreditCard(long accountId) {
        String type = jdbc.queryForObject("SELECT account_type FROM financial_accounts WHERE id = ?", String.class, accountId);
        return "CREDIT_CARD".equals(type);
    }

    private boolean hasAnySavedLayout(long householdId, long accountId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM csv_layouts WHERE household_id = ? AND account_id = ?", Integer.class, householdId, accountId);
        return n != null && n > 0;
    }

    private CsvLayout savedLayout(long householdId, long accountId, String fingerprint) {
        List<CsvLayout> found = jdbc.query("""
                SELECT date_col, date_format, desc_col, amount_mode, amount_col, debit_col, credit_col,
                       out_is_negative, balance_col
                FROM csv_layouts WHERE household_id = ? AND account_id = ? AND header_fingerprint = ?
                """, (rs, n) -> new CsvLayout(fingerprint, rs.getInt("date_col"), rs.getString("date_format"),
                        rs.getInt("desc_col"), CsvLayout.AmountMode.valueOf(rs.getString("amount_mode")),
                        orNone(rs.getObject("amount_col")), orNone(rs.getObject("debit_col")),
                        orNone(rs.getObject("credit_col")), rs.getBoolean("out_is_negative"),
                        orNone(rs.getObject("balance_col"))),
                householdId, accountId, fingerprint);
        return found.isEmpty() ? null : found.get(0);
    }

    private static int orNone(Object value) {
        return value == null ? -1 : ((Number) value).intValue();
    }

    private void save(long householdId, long accountId, long userId, CsvLayout l) {
        jdbc.update("""
                INSERT INTO csv_layouts (household_id, account_id, header_fingerprint, date_col, date_format, desc_col,
                                         amount_mode, amount_col, debit_col, credit_col, out_is_negative, balance_col,
                                         confirmed_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id, header_fingerprint) DO UPDATE SET
                    date_col = EXCLUDED.date_col, date_format = EXCLUDED.date_format, desc_col = EXCLUDED.desc_col,
                    amount_mode = EXCLUDED.amount_mode, amount_col = EXCLUDED.amount_col,
                    debit_col = EXCLUDED.debit_col, credit_col = EXCLUDED.credit_col,
                    out_is_negative = EXCLUDED.out_is_negative, balance_col = EXCLUDED.balance_col,
                    confirmed_by_user_id = EXCLUDED.confirmed_by_user_id, confirmed_at = now()
                """, householdId, accountId, l.fingerprint(), l.dateCol(), l.dateFormat(), l.descCol(), l.mode().name(),
                none(l.amountCol()), none(l.debitCol()), none(l.creditCol()), l.outIsNegative(), none(l.balanceCol()),
                userId);
    }

    private static Integer none(int col) {
        return col < 0 ? null : col;
    }
}
