package com.household.finance.document;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads proposed values out of OCR text from a bill or receipt. Pure: no database, no clock, no logging.
 * <p>
 * It only proposes. When the text has two different candidates for a value (two totals, two dates, two tax
 * lines) it proposes nothing for that value and says so in {@code notes}, so the user is asked instead of guessed at.
 * Notes are built from numbers only, never from document text. Dates are read as month/day/year (US).
 */
public final class DocumentExtractor {

    public record Extracted(DocumentKind kind, String vendor, BigDecimal total, BigDecimal subtotal, BigDecimal tax,
                            LocalDate documentDate, LocalDate dueDate, List<String> notes) {

        public boolean isEmpty() {
            return vendor == null && total == null && subtotal == null && tax == null
                    && documentDate == null && dueDate == null;
        }
    }

    /** An amount with exactly 2 decimals. A trailing % (a tax rate such as 8.25%) is not an amount. */
    private static final Pattern MONEY =
            Pattern.compile("(?<![\\d.,])\\$?\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)\\.(\\d{2})(?![\\d%])");
    private static final Pattern BILL_TOTAL = Pattern.compile("\\b(amount|total|balance)\\s+due\\b");
    private static final Pattern RECEIPT_TOTAL = Pattern.compile("^\\s*(grand\\s+)?total\\b");
    private static final Pattern SUBTOTAL = Pattern.compile("^\\s*sub\\s?-?total\\b");
    private static final Pattern TAX = Pattern.compile("^\\s*(sales\\s+)?tax\\b");
    private static final Pattern DUE = Pattern.compile("\\bdue\\b");
    private static final Pattern BILL_DATE_LABEL =
            Pattern.compile("(statement|bill|billing|invoice|issue)\\s+date|date\\s+(issued|of\\s+bill)");
    private static final Pattern NOT_A_DOCUMENT_DATE =
            Pattern.compile("\\bdue\\b|period|return|expire|\\bexp\\b|valid|through|\\bto\\b");
    private static final Pattern NUMERIC_DATE = Pattern.compile("(?<!\\d)(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4}|\\d{2})(?!\\d)");
    private static final Pattern ISO_DATE = Pattern.compile("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)");
    private static final Pattern NAMED_DATE = Pattern.compile(
            "\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b");
    private static final String MONTHS = "janfebmaraprmayjunjulaugsepoctnovdec";
    private static final Pattern NOT_A_VENDOR = Pattern.compile(
            "^(sales\\s+)?(receipt|invoice|statement|bill)\\b|^welcome\\b|^thank you\\b|^(customer|merchant) copy\\b");

    private DocumentExtractor() {}

    public static Extracted extract(String ocrText) {
        List<String> notes = new ArrayList<>();
        if (ocrText == null || ocrText.isBlank()) {
            return new Extracted(DocumentKind.UNKNOWN, null, null, null, null, null, null, notes);
        }
        List<String> lines = ocrText.lines().toList();

        Set<BigDecimal> billTotals = new LinkedHashSet<>();
        Set<BigDecimal> receiptTotals = new LinkedHashSet<>();
        List<BigDecimal> subtotals = new ArrayList<>();
        List<BigDecimal> taxes = new ArrayList<>();
        Set<LocalDate> dueDates = new LinkedHashSet<>();
        Set<LocalDate> billDates = new LinkedHashSet<>();
        Set<LocalDate> otherDates = new LinkedHashSet<>();
        boolean billLabelSeen = false;
        boolean receiptLabelSeen = false;

        for (String raw : lines) {
            String line = raw.toLowerCase(Locale.ROOT);
            BigDecimal money = lastMoney(raw);
            boolean pastDue = line.contains("past due");

            if (BILL_TOTAL.matcher(line).find() && !line.contains("minimum") && !pastDue) {
                billLabelSeen = true;
                if (money != null) {
                    billTotals.add(money);
                }
            } else if (RECEIPT_TOTAL.matcher(line).find() && !containsAny(line, "tax", "saving", "discount", "tender")) {
                receiptLabelSeen = true;
                if (money != null) {
                    receiptTotals.add(money);
                }
            } else if (SUBTOTAL.matcher(line).find()) {
                if (money != null) {
                    subtotals.add(money);
                }
            } else if (TAX.matcher(line).find()) {
                if (money != null) {
                    taxes.add(money);
                }
            }

            List<LocalDate> dates = datesIn(line);
            if (dates.isEmpty()) {
                continue;
            }
            if (DUE.matcher(line).find()) {
                if (!pastDue) {
                    dueDates.addAll(dates);
                }
            } else if (BILL_DATE_LABEL.matcher(line).find()) {
                billDates.addAll(dates);
            } else if (!NOT_A_DOCUMENT_DATE.matcher(line).find()) {
                otherDates.addAll(dates);
            }
        }

        DocumentKind kind = billLabelSeen ? DocumentKind.BILL : receiptLabelSeen ? DocumentKind.RECEIPT : DocumentKind.UNKNOWN;
        Set<BigDecimal> totals = billLabelSeen ? billTotals : receiptTotals;

        BigDecimal total = single(totals, "total", notes);
        BigDecimal subtotal = single(new LinkedHashSet<>(subtotals), "subtotal", notes);
        BigDecimal tax = taxes.size() > 1 ? null : single(new LinkedHashSet<>(taxes), "tax", notes);
        if (taxes.size() > 1) {
            notes.add("More than one tax line was found, so the tax was not read.");
        }
        LocalDate due = singleDate(dueDates, "due date", notes);
        Set<LocalDate> documentDates = !billDates.isEmpty() ? billDates : otherDates;
        LocalDate documentDate = singleDate(documentDates, "date", notes);

        return new Extracted(kind, guessVendor(lines), total, subtotal, tax, documentDate, due, List.copyOf(notes));
    }

    private static BigDecimal single(Set<BigDecimal> values, String what, List<String> notes) {
        if (values.size() == 1) {
            return values.iterator().next();
        }
        if (values.size() > 1) {
            Set<String> sorted = new TreeSet<>();
            values.forEach(v -> sorted.add(v.toPlainString()));
            notes.add("More than one " + what + " was found (" + String.join(" and ", sorted) + "). Say which is right.");
        }
        return null;
    }

    private static LocalDate singleDate(Set<LocalDate> values, String what, List<String> notes) {
        if (values.size() == 1) {
            return values.iterator().next();
        }
        if (values.size() > 1) {
            notes.add("More than one " + what + " was found. Say which is right.");
        }
        return null;
    }

    private static boolean containsAny(String line, String... words) {
        for (String word : words) {
            if (line.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** The last amount on the line, because on a receipt the price is at the right-hand end. */
    private static BigDecimal lastMoney(String line) {
        Matcher m = MONEY.matcher(line);
        BigDecimal found = null;
        while (m.find()) {
            found = new BigDecimal(m.group(1).replace(",", "") + "." + m.group(2));
        }
        return found;
    }

    private static List<LocalDate> datesIn(String line) {
        List<LocalDate> dates = new ArrayList<>();
        Matcher iso = ISO_DATE.matcher(line);
        while (iso.find()) {
            add(dates, Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
        }
        Matcher numeric = NUMERIC_DATE.matcher(line);
        while (numeric.find()) {
            int year = Integer.parseInt(numeric.group(3));
            add(dates, year < 100 ? 2000 + year : year, Integer.parseInt(numeric.group(1)), Integer.parseInt(numeric.group(2)));
        }
        Matcher named = NAMED_DATE.matcher(line);
        while (named.find()) {
            int month = MONTHS.indexOf(named.group(1)) / 3 + 1;
            add(dates, Integer.parseInt(named.group(3)), month, Integer.parseInt(named.group(2)));
        }
        return dates;
    }

    private static void add(List<LocalDate> dates, int year, int month, int day) {
        try {
            LocalDate date = LocalDate.of(year, month, day);
            if (!dates.contains(date)) {
                dates.add(date);
            }
        } catch (DateTimeException e) {
            // Not a real date (for example 13/45/2026): leave it out rather than guess a day/month swap.
        }
    }

    /** The first of the top lines that looks like a name: mostly letters, no amount, no date, not a heading word. */
    private static String guessVendor(List<String> lines) {
        int seen = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (++seen > 6) {
                break;
            }
            String text = line.trim().replaceAll("\\s+", " ");
            long letters = text.chars().filter(Character::isLetter).count();
            if (letters < 3 || letters * 2 < text.replace(" ", "").length()) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            if (MONEY.matcher(text).find() || !datesIn(lower).isEmpty() || NOT_A_VENDOR.matcher(lower).find()) {
                continue;
            }
            return text.length() > DocumentVerifier.MAX_VENDOR_LENGTH
                    ? text.substring(0, DocumentVerifier.MAX_VENDOR_LENGTH).trim() : text;
        }
        return null;
    }
}
