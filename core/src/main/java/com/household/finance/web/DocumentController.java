package com.household.finance.web;

import com.household.finance.document.DocumentImportService;
import com.household.finance.document.DocumentOutcome;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Thin REST layer over the photo flow: upload (read and propose), confirm (save what the user checked) and cancel.
 * No logic lives here. Reading, the Java checks and the save all happen in {@link DocumentImportService}.
 * Responses carry plain messages only; the photo and its text are never logged or echoed back.
 */
@RestController
@RequestMapping("/api")
public class DocumentController {

    public record Category(String code, String label) {}

    private final DocumentImportService documents;
    private final LocalIdentity identity;
    private final JdbcTemplate jdbc;

    public DocumentController(DocumentImportService documents, LocalIdentity identity, JdbcTemplate jdbc) {
        this.documents = documents;
        this.identity = identity;
        this.jdbc = jdbc;
    }

    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentOutcome> upload(@RequestParam("file") MultipartFile file) throws IOException {
        LocalIdentity.Who who = identity.current();
        return respond(documents.upload(who.householdId(), who.userId(), file.getBytes()));
    }

    @PostMapping("/documents/{id}/confirm")
    public ResponseEntity<DocumentOutcome> confirm(@PathVariable("id") long id, @RequestBody ConfirmRequest body) {
        LocalIdentity.Who who = identity.current();
        return respond(documents.confirm(who.householdId(), id, who.userId(), body.fields(),
                body.categoryOrNull(), body.accountId()));
    }

    @PostMapping("/documents/{id}/cancel")
    public ResponseEntity<DocumentOutcome> cancel(@PathVariable("id") long id) {
        LocalIdentity.Who who = identity.current();
        return respond(documents.cancel(who.householdId(), id, who.userId()));
    }

    @GetMapping("/categories")
    public List<Category> categories() {
        return jdbc.query("SELECT code, label FROM categories ORDER BY label",
                (rs, n) -> new Category(rs.getString(1), rs.getString(2)));
    }

    /** A photo that could not be used is 422; every other outcome is a normal answer for the page to show. */
    private static ResponseEntity<DocumentOutcome> respond(DocumentOutcome out) {
        HttpStatus status = out.status() == DocumentOutcome.Status.REJECTED ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.OK;
        return ResponseEntity.status(status).body(out);
    }
}
