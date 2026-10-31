package com.household.finance.document;

import com.household.finance.document.ai.AiReader;
import com.household.finance.document.ai.AiReading;
import com.household.finance.document.ai.AiUnavailableException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports a bill or receipt photo: read it, stage the proposed values, then save only what the user confirms.
 * <p>
 * Unlike a bank statement, a photo has no independent total to reconcile against, so a photo is NEVER saved on its
 * own: the user always confirms (and may correct) the values, and the Java checks run again on what they confirm.
 * A photo that cannot be read stores nothing. The photo and its text are not stored or logged. Every query is
 * scoped by household, so one household can never read or change another's uploads.
 * <p>
 * A bill is saved as a PENDING expense dated on its due date; a receipt is saved as a POSTED expense dated on the
 * purchase date. Matching a bill or receipt to the bank or card line that later pays it is not done here (M2).
 * <p>
 * AI reading (opt-in, see {@link #upload(long, long, byte[], boolean)}) only fills a value the local reading left
 * blank. It never overrides a value the local reading already found, and its answer goes through the same
 * {@link DocumentVerifier} checks as any other proposal before it can be saved.
 * <p>
 * Re-picking the same photo while it is still waiting for confirmation resumes that same pending upload instead of
 * being refused: its stored values are shown again, and AI reading (if asked for and not already tried) is tried
 * once against them. {@code ai_attempted} (V8) remembers whether AI reading already ran for that upload, so ticking
 * the box again on a retry does not pay for a second call. Only a photo already COMMITTED to a transaction is
 * refused outright.
 */
@Service
public class DocumentImportService {

    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** Four or more groups of 4 digits separated by a space or dash: how a full card number is usually printed or
     * typed, e.g. "4242 4242 4242 4242". Always treated as a possible full card number. */
    private static final Pattern GROUPED_CARD_NUMBER = Pattern.compile("(?:\\d{4}[ -]){3}\\d{1,4}");
    /** 13 to 19 digits with no separators: long enough for a full card or account number, but this shape alone
     * also matches an order number or barcode, so it is treated as one only with a card/account word on the same line. */
    private static final Pattern UNBROKEN_LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)");
    private static final Pattern CARD_OR_ACCOUNT_WORD =
            Pattern.compile("card|visa|mastercard|discover|amex|account|acct", Pattern.CASE_INSENSITIVE);

    private static final AiReader NO_AI = new AiReader() {
        @Override public boolean enabled() { return false; }
        @Override public AiReading read(byte[] image) {
            throw new AiUnavailableException("AI reading is not set up.");
        }
    };

    private final JdbcTemplate jdbc;
    private final OcrEngine ocr;
    private final AiReader ai;
    private final Clock clock;

    @Autowired
    public DocumentImportService(JdbcTemplate jdbc, OcrEngine ocr, AiReader ai) {
        this(jdbc, ocr, ai, Clock.systemDefaultZone());
    }

    DocumentImportService(JdbcTemplate jdbc, OcrEngine ocr, Clock clock) {
        this(jdbc, ocr, NO_AI, clock);
    }

    DocumentImportService(JdbcTemplate jdbc, OcrEngine ocr, AiReader ai, Clock clock) {
        this.jdbc = jdbc;
        this.ocr = ocr;
        this.ai = ai;
        this.clock = clock;
    }

    @Transactional
    public DocumentOutcome upload(long householdId, long userId, byte[] image) {
        return upload(householdId, userId, image, false);
    }

    /**
     * {@code useAi}: when true and AI reading is set up for this household, a value the local (Tesseract) reading
     * left blank is filled in from one AI reading of this same photo. A value the local reading already found is
     * never replaced. Every value, from either source, is checked again by {@link DocumentVerifier} before it can
     * be saved; nothing is ever saved on the strength of the AI reading alone.
     */
    @Transactional
    public DocumentOutcome upload(long householdId, long userId, byte[] image, boolean useAi) {
        String unusable = checkImage(image);
        if (unusable != null) {
            return DocumentOutcome.rejected(unusable);
        }
        String sha = sha256(image);
        List<ExistingImport> found = jdbc.query("""
                SELECT id, status, kind, proposed_vendor, proposed_amount, proposed_subtotal, proposed_tax, proposed_date, ai_attempted
                FROM document_imports WHERE household_id = ? AND file_sha256 = ? AND status <> 'CANCELLED'
                """, (rs, n) -> new ExistingImport(rs.getLong(1), rs.getString(2),
                        DocumentKind.valueOf(rs.getString(3)), rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6),
                        rs.getBigDecimal(7), rs.getObject(8, LocalDate.class), rs.getBoolean(9)),
                householdId, sha);
        if (!found.isEmpty()) {
            ExistingImport e = found.get(0);
            if (!"NEEDS_CONFIRMATION".equals(e.status())) {
                return new DocumentOutcome(DocumentOutcome.Status.ALREADY_IMPORTED, null, null, null,
                        List.of("This exact photo was already uploaded. Nothing was changed."));
            }
            return resumePending(householdId, userId, image, useAi, e);
        }

        List<String> readings;
        try {
            readings = ocr.readAll(image);
        } catch (OcrUnavailableException | UnusablePhotoException e) {
            return DocumentOutcome.rejected(e.getMessage());
        } catch (IOException | RuntimeException e) {
            return DocumentOutcome.rejected("The photo could not be read. Nothing was saved."
                    + " Try a clearer photo, or enter the item by hand.");
        }
        if (readings == null || readings.stream().allMatch(t -> t == null || t.isBlank())) {
            return DocumentOutcome.rejected("No text could be read from this photo. Nothing was saved."
                    + " Retake it flat, in good light and in focus, or enter the item by hand.");
        }
        DocumentExtractor.Extracted x = DocumentExtractor.extractAll(readings.stream().map(t -> t == null ? "" : t).toList());

        DocumentKind kind = x.kind();
        String vendor = x.vendor();
        java.math.BigDecimal total = x.total();
        java.math.BigDecimal subtotal = x.subtotal();
        java.math.BigDecimal tax = x.tax();
        LocalDate documentDate = x.documentDate();
        LocalDate dueDate = x.dueDate();
        List<String> notes = new ArrayList<>(x.notes());
        boolean aiAttempted = false;

        if (useAi) {
            if (looksLikeFullNumber(readings)) {
                notes.add("This photo may show a full card or account number, so it was not sent to AI."
                        + " Enter it by hand, or retake it with the number covered, if you want AI reading on it.");
            } else if (!ai.enabled()) {
                notes.add("AI reading is not set up for this app, so only the local reading was used.");
            } else {
                try {
                    AiReading a = ai.read(image);
                    aiAttempted = true;
                    boolean filledAny = false;
                    if ((kind == null || kind == DocumentKind.UNKNOWN) && a.kind() != null && a.kind() != DocumentKind.UNKNOWN) {
                        kind = a.kind();
                        filledAny = true;
                    }
                    if ((vendor == null || vendor.isBlank()) && a.vendor() != null) {
                        vendor = a.vendor();
                        filledAny = true;
                    }
                    if (total == null && a.total() != null) {
                        total = a.total();
                        filledAny = true;
                    }
                    if (subtotal == null && a.subtotal() != null) {
                        subtotal = a.subtotal();
                        filledAny = true;
                    }
                    if (tax == null && a.tax() != null) {
                        tax = a.tax();
                        filledAny = true;
                    }
                    if (documentDate == null && a.date() != null) {
                        documentDate = a.date();
                        filledAny = true;
                    }
                    if (dueDate == null && a.dueDate() != null) {
                        dueDate = a.dueDate();
                        filledAny = true;
                    }
                    if (filledAny) {
                        notes.add("Some values were filled in by AI reading of the photo. Check them against the paper before saving.");
                    }
                } catch (IOException | RuntimeException e) {
                    notes.add("AI reading did not complete, so only the local reading was used.");
                }
            }
        }

        if (total == null && documentDate == null && dueDate == null) {
            return DocumentOutcome.rejected("No amount or date could be read from this photo. Nothing was saved."
                    + " Retake it flat, in good light and in focus, or enter the item by hand.");
        }

        LocalDate proposedDate = kind == DocumentKind.BILL && dueDate != null ? dueDate : documentDate;
        DocumentFields proposal = new DocumentFields(kind, vendor, total, proposedDate, subtotal, tax);

        long importId = jdbc.queryForObject("""
                INSERT INTO document_imports (household_id, file_sha256, kind, status, proposed_vendor, proposed_amount,
                                              proposed_subtotal, proposed_tax, proposed_date, uploaded_by_user_id, ai_attempted)
                VALUES (?, ?, ?, 'NEEDS_CONFIRMATION', ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, householdId, sha, kind.name(), vendor, total, subtotal, tax,
                proposedDate, userId, aiAttempted);
        audit(householdId, userId, "DOCUMENT_UPLOADED", importId);

        List<String> messages = new ArrayList<>(notes);
        messages.addAll(DocumentVerifier.verify(proposal, LocalDate.now(clock)));
        return new DocumentOutcome(DocumentOutcome.Status.NEEDS_CONFIRMATION, importId, null, proposal, messages);
    }

    /**
     * Re-picking the same photo while it is still NEEDS_CONFIRMATION lands here instead of being refused: its
     * stored values are the starting point, and (if asked for, and not already tried) one AI reading is merged in
     * the same way as a fresh upload. The local (Tesseract) reading is not re-run, so values already in the
     * database are not recomputed; only a fresh OCR pass is made, and only to re-check for a possible full card
     * or account number before any AI call, since that check is never persisted.
     */
    private DocumentOutcome resumePending(long householdId, long userId, byte[] image, boolean useAi, ExistingImport e) {
        DocumentKind kind = e.kind();
        String vendor = e.vendor();
        java.math.BigDecimal total = e.amount();
        java.math.BigDecimal subtotal = e.subtotal();
        java.math.BigDecimal tax = e.tax();
        LocalDate date = e.date();
        List<String> notes = new ArrayList<>();

        if (useAi) {
            if (e.aiAttempted()) {
                notes.add("AI reading was already tried for this upload.");
            } else if (!ai.enabled()) {
                notes.add("AI reading is not set up for this app, so only the local reading was used.");
            } else {
                boolean guardTripped;
                try {
                    List<String> readings = ocr.readAll(image);
                    guardTripped = readings != null && looksLikeFullNumber(readings);
                } catch (IOException | RuntimeException ex) {
                    guardTripped = false;   // OCR already succeeded once on this photo; a re-read hiccup should not block AI.
                }
                if (guardTripped) {
                    notes.add("This photo may show a full card or account number, so it was not sent to AI."
                            + " Enter it by hand, or retake it with the number covered, if you want AI reading on it.");
                } else {
                    try {
                        AiReading a = ai.read(image);
                        boolean filledAny = false;
                        if ((kind == null || kind == DocumentKind.UNKNOWN) && a.kind() != null && a.kind() != DocumentKind.UNKNOWN) {
                            kind = a.kind();
                            filledAny = true;
                        }
                        if ((vendor == null || vendor.isBlank()) && a.vendor() != null) {
                            vendor = a.vendor();
                            filledAny = true;
                        }
                        if (total == null && a.total() != null) {
                            total = a.total();
                            filledAny = true;
                        }
                        if (subtotal == null && a.subtotal() != null) {
                            subtotal = a.subtotal();
                            filledAny = true;
                        }
                        if (tax == null && a.tax() != null) {
                            tax = a.tax();
                            filledAny = true;
                        }
                        if (date == null) {
                            LocalDate preferred = kind == DocumentKind.BILL
                                    ? (a.dueDate() != null ? a.dueDate() : a.date())
                                    : (a.date() != null ? a.date() : a.dueDate());
                            if (preferred != null) {
                                date = preferred;
                                filledAny = true;
                            }
                        }
                        jdbc.update("""
                                UPDATE document_imports
                                SET kind = ?, proposed_vendor = ?, proposed_amount = ?, proposed_subtotal = ?,
                                    proposed_tax = ?, proposed_date = ?, ai_attempted = TRUE
                                WHERE id = ?
                                """, kind.name(), vendor, total, subtotal, tax, date, e.id());
                        audit(householdId, userId, "DOCUMENT_AI_RETRIED", e.id());
                        notes.add(filledAny
                                ? "Some values were filled in by AI reading of the photo. Check them against the paper before saving."
                                : "AI reading did not find anything new; the local reading is unchanged.");
                    } catch (IOException | RuntimeException ex) {
                        // ai_attempted is left false, so a later retry on this same upload can try again.
                        notes.add("AI reading did not complete, so only the local reading was used.");
                    }
                }
            }
        }

        DocumentFields proposal = new DocumentFields(kind, vendor, total, date, subtotal, tax);
        List<String> messages = new ArrayList<>(notes);
        messages.addAll(DocumentVerifier.verify(proposal, LocalDate.now(clock)));
        return new DocumentOutcome(DocumentOutcome.Status.NEEDS_CONFIRMATION, e.id(), null, proposal, messages);
    }

    /** True when the OCR text has something shaped like a full card or account number; see the two patterns above. */
    private static boolean looksLikeFullNumber(List<String> readings) {
        for (String r : readings) {
            if (r == null) {
                continue;
            }
            for (String line : r.split("\\R")) {
                if (GROUPED_CARD_NUMBER.matcher(line).find()) {
                    return true;
                }
                if (UNBROKEN_LONG_NUMBER.matcher(line).find() && CARD_OR_ACCOUNT_WORD.matcher(line).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One row already staged for this exact photo, read back from {@code document_imports}. */
    private record ExistingImport(long id, String status, DocumentKind kind, String vendor, java.math.BigDecimal amount,
                                  java.math.BigDecimal subtotal, java.math.BigDecimal tax, LocalDate date, boolean aiAttempted) {}

    /**
     * Saves the user's confirmed values as one transaction, if they pass every check. Otherwise nothing is saved and
     * the problems come back so the user can correct them and confirm again. {@code categoryCode} and
     * {@code accountId} are optional; when given they must belong to this household's categories and accounts.
     */
    @Transactional
    public DocumentOutcome confirm(long householdId, long importId, long userId, DocumentFields confirmed,
                                   String categoryCode, Long accountId) {
        Objects.requireNonNull(confirmed, "confirmed");
        List<String[]> found = jdbc.query("""
                SELECT status, file_sha256 FROM document_imports WHERE id = ? AND household_id = ?
                """, (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, importId, householdId);
        if (found.isEmpty() || !"NEEDS_CONFIRMATION".equals(found.get(0)[0])) {
            throw new IllegalArgumentException("No upload waiting for confirmation with that id in this household.");
        }
        String sha = found.get(0)[1];

        if (categoryCode != null) {
            Integer category = jdbc.queryForObject("SELECT count(*) FROM categories WHERE code = ?", Integer.class, categoryCode);
            if (category == null || category == 0) {
                throw new IllegalArgumentException("That category does not exist.");
            }
        }
        if (accountId != null) {
            Integer account = jdbc.queryForObject(
                    "SELECT count(*) FROM financial_accounts WHERE id = ? AND household_id = ?", Integer.class, accountId, householdId);
            if (account == null || account == 0) {
                throw new IllegalArgumentException("That account does not belong to this household.");
            }
        }

        List<String> problems = DocumentVerifier.verify(confirmed, LocalDate.now(clock));
        if (!problems.isEmpty()) {
            return new DocumentOutcome(DocumentOutcome.Status.NEEDS_CONFIRMATION, importId, null, confirmed, problems);
        }

        String status = confirmed.kind() == DocumentKind.BILL ? "PENDING" : "POSTED";
        long transactionId = jdbc.queryForObject("""
                INSERT INTO transactions (household_id, account_id, posted_on, vendor, amount, direction, category_code,
                                          source, status, external_ref, entered_by_user_id)
                VALUES (?, ?, ?, ?, ?, 'EXPENSE', ?, 'PHOTO', ?, ?, ?) RETURNING id
                """, Long.class, householdId, accountId, confirmed.date(), confirmed.vendor().trim(), confirmed.amount(),
                categoryCode, status, "photo-" + sha, userId);
        jdbc.update("""
                UPDATE document_imports
                SET status = 'COMMITTED', transaction_id = ?, completed_at = now(),
                    proposed_vendor = NULL, proposed_amount = NULL, proposed_subtotal = NULL, proposed_tax = NULL, proposed_date = NULL
                WHERE id = ?
                """, transactionId, importId);
        audit(householdId, userId, "DOCUMENT_CONFIRMED", importId);
        return new DocumentOutcome(DocumentOutcome.Status.COMMITTED, importId, transactionId, confirmed,
                List.of("Saved 1 transaction."));
    }

    @Transactional
    public DocumentOutcome cancel(long householdId, long importId, long userId) {
        int changed = jdbc.update("""
                UPDATE document_imports
                SET status = 'CANCELLED', completed_at = now(),
                    proposed_vendor = NULL, proposed_amount = NULL, proposed_subtotal = NULL, proposed_tax = NULL, proposed_date = NULL
                WHERE id = ? AND household_id = ? AND status = 'NEEDS_CONFIRMATION'
                """, importId, householdId);
        if (changed == 0) {
            throw new IllegalArgumentException("No upload waiting for confirmation with that id in this household.");
        }
        audit(householdId, userId, "DOCUMENT_CANCELLED", importId);
        return new DocumentOutcome(DocumentOutcome.Status.CANCELLED, importId, null, null,
                List.of("Upload cancelled. Nothing was saved."));
    }

    /** PNG and JPEG only, up to 10 MB. Returns a plain message, or null when the bytes look like a usable image. */
    private static String checkImage(byte[] image) {
        if (image == null || image.length == 0) {
            return "The photo is empty. Nothing was saved.";
        }
        if (image.length > MAX_IMAGE_BYTES) {
            return "The photo is larger than 10 MB. Nothing was saved.";
        }
        boolean png = image.length > 8 && (image[0] & 0xFF) == 0x89 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G';
        boolean jpeg = image.length > 3 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8 && (image[2] & 0xFF) == 0xFF;
        if (png || jpeg) {
            return null;
        }
        boolean heic = image.length > 12 && image[4] == 'f' && image[5] == 't' && image[6] == 'y' && image[7] == 'p';
        if (heic) {
            return "HEIC photos are not supported yet. Share or export the photo as JPEG and try again. Nothing was saved.";
        }
        return "Only JPEG and PNG photos are supported. Nothing was saved.";
    }

    private void audit(long householdId, long userId, String action, long importId) {
        jdbc.update("""
                INSERT INTO audit_log (household_id, user_id, action, entity, entity_id)
                VALUES (?, ?, ?, 'document_imports', ?)
                """, householdId, userId, action, importId);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
