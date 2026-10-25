package com.household.finance.document;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
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
 */
@Service
public class DocumentImportService {

    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final OcrEngine ocr;
    private final Clock clock;

    @Autowired
    public DocumentImportService(JdbcTemplate jdbc, OcrEngine ocr) {
        this(jdbc, ocr, Clock.systemDefaultZone());
    }

    DocumentImportService(JdbcTemplate jdbc, OcrEngine ocr, Clock clock) {
        this.jdbc = jdbc;
        this.ocr = ocr;
        this.clock = clock;
    }

    @Transactional
    public DocumentOutcome upload(long householdId, long userId, byte[] image) {
        String unusable = checkImage(image);
        if (unusable != null) {
            return DocumentOutcome.rejected(unusable);
        }
        String sha = sha256(image);
        Integer existing = jdbc.queryForObject("""
                SELECT count(*) FROM document_imports
                WHERE household_id = ? AND file_sha256 = ? AND status <> 'CANCELLED'
                """, Integer.class, householdId, sha);
        if (existing != null && existing > 0) {
            return new DocumentOutcome(DocumentOutcome.Status.ALREADY_IMPORTED, null, null, null,
                    List.of("This exact photo was already uploaded. Nothing was changed."));
        }

        String text;
        try {
            text = ocr.read(image);
        } catch (OcrUnavailableException e) {
            return DocumentOutcome.rejected(e.getMessage());
        } catch (IOException | RuntimeException e) {
            return DocumentOutcome.rejected("The photo could not be read. Nothing was saved."
                    + " Try a clearer photo, or enter the item by hand.");
        }
        if (text == null || text.isBlank()) {
            return DocumentOutcome.rejected("No text could be read from this photo. Nothing was saved."
                    + " Retake it flat, in good light and in focus, or enter the item by hand.");
        }
        DocumentExtractor.Extracted x = DocumentExtractor.extract(text);
        if (x.isEmpty()) {
            return DocumentOutcome.rejected("Nothing usable could be read from this photo. Nothing was saved."
                    + " Retake it flat, in good light and in focus, or enter the item by hand.");
        }

        LocalDate proposedDate = x.kind() == DocumentKind.BILL && x.dueDate() != null ? x.dueDate() : x.documentDate();
        DocumentFields proposal = new DocumentFields(x.kind(), x.vendor(), x.total(), proposedDate, x.subtotal(), x.tax());

        long importId = jdbc.queryForObject("""
                INSERT INTO document_imports (household_id, file_sha256, kind, status, proposed_vendor, proposed_amount,
                                              proposed_subtotal, proposed_tax, proposed_date, uploaded_by_user_id)
                VALUES (?, ?, ?, 'NEEDS_CONFIRMATION', ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, householdId, sha, x.kind().name(), x.vendor(), x.total(), x.subtotal(), x.tax(),
                proposedDate, userId);
        audit(householdId, userId, "DOCUMENT_UPLOADED", importId);

        List<String> messages = new ArrayList<>(x.notes());
        messages.addAll(DocumentVerifier.verify(proposal, LocalDate.now(clock)));
        return new DocumentOutcome(DocumentOutcome.Status.NEEDS_CONFIRMATION, importId, null, proposal, messages);
    }

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
