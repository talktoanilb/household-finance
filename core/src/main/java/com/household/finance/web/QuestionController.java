package com.household.finance.web;

import com.household.finance.ingest.Answer;
import com.household.finance.ingest.ImportOutcome;
import com.household.finance.ingest.ImportService;
import com.household.finance.ingest.OpenQuestion;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST layer over the statement questions: list what is waiting, answer one, or cancel a whole import.
 * Uploading a statement through the web is not built yet (it needs an account picker); this answers questions on
 * imports that were started another way.
 */
@RestController
@RequestMapping("/api")
public class QuestionController {

    /** CONFIRM, EXCLUDE or CORRECT_AMOUNT (with {@code correctedAmount}). */
    public record AnswerRequest(Answer.Type type, BigDecimal correctedAmount) {}

    private final ImportService imports;
    private final LocalIdentity identity;

    public QuestionController(ImportService imports, LocalIdentity identity) {
        this.imports = imports;
        this.identity = identity;
    }

    @GetMapping("/questions")
    public List<OpenQuestion> questions() {
        return imports.openQuestions(identity.current().householdId());
    }

    @PostMapping("/questions/{id}/answer")
    public ImportOutcome answer(@PathVariable("id") long id, @RequestBody AnswerRequest body) {
        if (body == null || body.type() == null) {
            throw new IllegalArgumentException("Say how to answer: CONFIRM, EXCLUDE or CORRECT_AMOUNT.");
        }
        LocalIdentity.Who who = identity.current();
        return imports.answer(who.householdId(), id, who.userId(), new Answer(body.type(), body.correctedAmount()));
    }

    @PostMapping("/imports/{id}/cancel")
    public ImportOutcome cancel(@PathVariable("id") long id) {
        LocalIdentity.Who who = identity.current();
        return imports.cancel(who.householdId(), id, who.userId());
    }
}
