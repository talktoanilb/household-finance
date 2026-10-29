package com.household.finance.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.household.finance.document.OcrEngine;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tests of the thin REST layer against PostgreSQL. Each test is rolled back.
 * The OCR engine and the identity are replaced, so these tests need neither Tesseract nor an existing household.
 * Hand-checked numbers: the receipt is 4.19 + 3.49 = 7.68 subtotal, plus 0.63 tax = 8.31 total.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WebApiTest {

    private static final String RECEIPT_TEXT = """
            CORNER GROCERY
            123 Main St
            10/18/2026 14:32
            MILK 1 GAL        4.19
            BREAD             3.49
            SUBTOTAL          7.68
            TAX 8.25%         0.63
            TOTAL             8.31
            """;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean OcrEngine ocr;
    @MockitoBean LocalIdentity identity;

    long household;

    @BeforeEach
    void seed() throws Exception {
        household = jdbc.queryForObject("INSERT INTO households (name) VALUES ('test') RETURNING id", Long.class);
        long user = jdbc.queryForObject("""
                INSERT INTO app_users (household_id, email, display_name, password_hash)
                VALUES (?, 'web@example.com', 'Test', 'x') RETURNING id
                """, Long.class, household);
        when(identity.current()).thenReturn(new LocalIdentity.Who(household, user));
        when(ocr.readAll(any())).thenReturn(List.of(RECEIPT_TEXT));
    }

    private static MockMultipartFile photo(int seed) {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, (byte) seed, 1, 2, 3};
        return new MockMultipartFile("file", "receipt.png", "image/png", png);
    }

    private int transactions() {
        return jdbc.queryForObject("SELECT count(*) FROM transactions WHERE household_id = ?", Integer.class, household);
    }

    private static String confirmJson(String amount) {
        return """
                {"kind":"RECEIPT","vendor":"Corner Grocery","amount":"%s","date":"%s",
                 "subtotal":"7.68","tax":"0.63","categoryCode":"groceries"}
                """.formatted(amount, LocalDate.now().minusDays(3));
    }

    private long uploadAndGetImportId() throws Exception {
        String body = mvc.perform(multipart("/api/documents").file(photo(1)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.importId")).longValue();
    }

    @Test
    void uploadProposesValuesAndSavesNothing() throws Exception {
        mvc.perform(multipart("/api/documents").file(photo(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CONFIRMATION"))
                .andExpect(jsonPath("$.proposal.kind").value("RECEIPT"))
                .andExpect(jsonPath("$.proposal.amount").value(8.31))
                .andExpect(jsonPath("$.proposal.date").value("2026-10-18"));
        assertEquals(0, transactions());
    }

    @Test
    void confirmingSavesExactlyOneTransaction() throws Exception {
        long id = uploadAndGetImportId();
        mvc.perform(post("/api/documents/" + id + "/confirm").contentType(MediaType.APPLICATION_JSON).content(confirmJson("8.31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));
        assertEquals(1, transactions());
    }

    @Test
    void valuesThatFailTheJavaChecksSaveNothing() throws Exception {
        long id = uploadAndGetImportId();
        mvc.perform(post("/api/documents/" + id + "/confirm").contentType(MediaType.APPLICATION_JSON).content(confirmJson("8.41")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CONFIRMATION"))
                .andExpect(jsonPath("$.messages[0]").value(
                        "The subtotal 7.68 plus tax 0.63 is 8.31, but the total is 8.41. One of them is misread."));
        assertEquals(0, transactions());
    }

    @Test
    void aFileThatIsNotAPhotoIsRefusedWithAPlainMessage() throws Exception {
        MockMultipartFile text = new MockMultipartFile("file", "notes.txt", "text/plain", "hello world".getBytes());
        mvc.perform(multipart("/api/documents").file(text))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertEquals(0, transactions());
    }

    @Test
    void anUnreadableAmountIsAPlainBadRequest() throws Exception {
        long id = uploadAndGetImportId();
        mvc.perform(post("/api/documents/" + id + "/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"RECEIPT\",\"amount\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
        assertEquals(0, transactions());
    }

    @Test
    void cancellingDiscardsTheUpload() throws Exception {
        long id = uploadAndGetImportId();
        mvc.perform(post("/api/documents/" + id + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/documents/" + id + "/confirm").contentType(MediaType.APPLICATION_JSON).content(confirmJson("8.31")))
                .andExpect(status().isBadRequest());
        assertEquals(0, transactions());
    }

    @Test
    void anotherHouseholdsUploadCannotBeConfirmed() throws Exception {
        long id = uploadAndGetImportId();
        long other = jdbc.queryForObject("INSERT INTO households (name) VALUES ('other') RETURNING id", Long.class);
        long otherUser = jdbc.queryForObject("""
                INSERT INTO app_users (household_id, email, display_name, password_hash)
                VALUES (?, 'other@example.com', 'Other', 'x') RETURNING id
                """, Long.class, other);
        when(identity.current()).thenReturn(new LocalIdentity.Who(other, otherUser));
        mvc.perform(post("/api/documents/" + id + "/confirm").contentType(MediaType.APPLICATION_JSON).content(confirmJson("8.31")))
                .andExpect(status().isBadRequest());
        assertEquals(0, transactions());
    }

    @Test
    void withNoHouseholdMemberTheAppSaysSo() throws Exception {
        when(identity.current()).thenThrow(new NoLocalUserException());
        mvc.perform(multipart("/api/documents").file(photo(1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void categoriesAreListed() throws Exception {
        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'groceries')]").exists());
    }

    @Test
    void noOpenQuestionsIsAnEmptyList() throws Exception {
        mvc.perform(get("/api/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void answeringAnUnknownQuestionIsAPlainBadRequest() throws Exception {
        mvc.perform(post("/api/questions/999999/answer").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"CONFIRM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No open question with that id in this household."));
    }
}
