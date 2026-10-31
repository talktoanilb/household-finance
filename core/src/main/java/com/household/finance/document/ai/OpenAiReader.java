package com.household.finance.document.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.finance.document.DocumentKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads one bill or receipt photo with an OpenAI vision model, for a photo the local (Tesseract) reading could not
 * settle. Off unless {@code OPENAI_API_KEY} is set in the environment; even then it only runs when the person ticks
 * "Also read with AI" for that one upload (DocumentImportService decides that, not this class). The key is read
 * from the environment only, never written to a log, and neither the photo nor the model's answer is logged.
 * <p>
 * The model is a setting ({@code household.ai.model}, default {@code gpt-4o-mini}) because available model names
 * change and differ by account; check your OpenAI account if the default is refused and set {@code AI_MODEL}.
 */
@Component
public class OpenAiReader implements AiReader {

    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");
    private static final String SYSTEM_PROMPT = """
            You read a photographed bill or receipt and report only what is printed on it. Never guess a digit you \
            cannot read clearly; use null for it instead. Reply with exactly this JSON and no other text: \
            {"kind":"BILL or RECEIPT or null","vendor":"string or null","total":"string or null",\
            "subtotal":"string or null","tax":"string or null","date":"YYYY-MM-DD or null","due_date":"YYYY-MM-DD or null"}. \
            Amounts are plain decimal strings with exactly 2 decimal places and no currency symbol, for example "12.34". \
            "date" is the purchase date on a receipt; "due_date" is the due date on a bill. "kind" is "BILL" for a \
            bill or invoice awaiting payment, "RECEIPT" for a completed purchase.""";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;

    public OpenAiReader(@Value("${OPENAI_API_KEY:}") String apiKey, @Value("${household.ai.model:gpt-4o-mini}") String model) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    @Override
    public boolean enabled() {
        return !apiKey.isEmpty();
    }

    @Override
    public AiReading read(byte[] image) throws IOException {
        if (!enabled()) {
            throw new AiUnavailableException("AI reading is not set up: no OPENAI_API_KEY is set.");
        }
        String dataUrl = "data:" + (looksLikePng(image) ? "image/png" : "image/jpeg") + ";base64,"
                + Base64.getEncoder().encodeToString(image);

        var body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0);
        body.putObject("response_format").put("type", "json_object");
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        var user = messages.addObject().put("role", "user");
        var content = user.putArray("content");
        content.addObject().put("type", "text").put("text", "Read this photo.");
        content.addObject().put("type", "image_url").putObject("image_url").put("url", dataUrl);

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Reading with AI was interrupted.", e);
        }
        if (response.statusCode() != 200) {
            // The response body can carry account or billing detail, so it is never included in the message or logged.
            throw new AiUnavailableException(
                    "The AI reading service returned an error (HTTP " + response.statusCode() + ").");
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode contentField = root.path("choices").path(0).path("message").path("content");
        if (!contentField.isTextual()) {
            throw new AiUnavailableException("The AI reading service did not return a readable answer.");
        }
        JsonNode fields;
        try {
            fields = mapper.readTree(contentField.asText());
        } catch (IOException e) {
            throw new AiUnavailableException("The AI reading service did not return a readable answer.");
        }
        return new AiReading(
                kindOf(fields.path("kind")),
                textOf(fields.path("vendor")),
                amountOf(fields.path("total")),
                amountOf(fields.path("subtotal")),
                amountOf(fields.path("tax")),
                dateOf(fields.path("date")),
                dateOf(fields.path("due_date")));
    }

    private static boolean looksLikePng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static DocumentKind kindOf(JsonNode n) {
        if (!n.isTextual()) {
            return null;
        }
        try {
            return DocumentKind.valueOf(n.asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String textOf(JsonNode n) {
        if (!n.isTextual()) {
            return null;
        }
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }

    /** At most 2 decimals and zero or more; anything else is treated as unread rather than trusted. */
    private static BigDecimal amountOf(JsonNode n) {
        if (!n.isTextual()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(n.asText().trim());
            return v.signum() >= 0 && v.scale() <= 2 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate dateOf(JsonNode n) {
        if (!n.isTextual()) {
            return null;
        }
        try {
            return LocalDate.parse(n.asText().trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
