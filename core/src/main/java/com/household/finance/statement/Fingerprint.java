package com.household.finance.statement;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the idempotency key stored in {@code transactions.external_ref}.
 * <p>
 * The key hashes format, date, normalised description, signed amount and an optional running balance,
 * plus an occurrence counter so two genuinely identical lines in one file (same day, same vendor,
 * same amount) get different keys. Re-importing the same file reproduces the same keys, so the unique
 * index rejects the repeats. The hash means no statement text is stored in the key.
 */
final class Fingerprint {

    private final String format;
    private final Map<String, Integer> seen = new HashMap<>();

    Fingerprint(String format) {
        this.format = format;
    }

    String next(LocalDate date, String description, BigDecimal signedAmount, String balance) {
        String base = String.join("|",
                format,
                date.toString(),
                description.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT),
                signedAmount.toPlainString(),
                balance == null ? "" : balance);
        int occurrence = seen.merge(base, 1, Integer::sum);
        return sha256(base + "|" + occurrence);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
