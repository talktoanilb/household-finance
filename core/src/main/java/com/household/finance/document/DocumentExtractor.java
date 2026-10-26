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

        /** A photo is only worth showing to the user when at least an amount or a date was found, not just a name. */
        public boolean hasAmountOrDate() {
            return total != null || documentDate != null || dueDate != null;
        }
    }

    /** An amount with exactly 2 decimals. OCR often turns the decimal point into a comma, which is accepted. A trailing % (a tax rate such as 8.25%) is not an amount. */
    private static final Pattern MONEY =
            Pattern.compile("(?<![\\d.,])\\$?\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)[.,](\\d{2})(?![\\d%])");
    private static final Pattern BILL_TOTAL = Pattern.compile("\\b(amount|total|balance)\\s+due\\b");
    /** A word on a payment line: the amount there is normally the total. "cash" is left out on purpose, because the cash handed over is not the total. */
    private static final Pattern TENDER =
            Pattern.compile("charge|visa|mastercard|discover|amex|debit|credit|card|tender|tendered|paid|payment|purchase|onepay");
    private static final Pattern TAX_WORD = Pattern.compile("tax\\d?|tx");
    /** Lines that are never the store name. */
    private static final Pattern NOT_A_NAME_LINE =
            Pattern.compile("@|www\\.|\\.com|http|survey|feedback", Pattern.CASE_INSENSITIVE);
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
        int subtotalLine = -1;
        List<BigDecimal> taxes = new ArrayList<>();
        Set<BigDecimal> tenders = new LinkedHashSet<>();
        List<BigDecimal> amountOnLine = new ArrayList<>();
        Set<LocalDate> dueDates = new LinkedHashSet<>();
        Set<LocalDate> billDates = new LinkedHashSet<>();
        Set<LocalDate> otherDates = new LinkedHashSet<>();
        boolean billLabelSeen = false;
        boolean receiptLabelSeen = false;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String line = raw.toLowerCase(Locale.ROOT);
            BigDecimal money = lastMoney(raw);
            amountOnLine.add(money);
            boolean pastDue = line.contains("past due");
            List<String> head = headWords(line, 4);

            if (BILL_TOTAL.matcher(line).find() && !line.contains("minimum") && !pastDue) {
                billLabelSeen = true;
                if (money != null) {
                    billTotals.add(money);
                }
            } else if (hasSubtotalWord(head)) {
                if (money != null) {
                    subtotals.add(money);
                    subtotalLine = i;
                }
            } else if (hasTotalWord(head) && !containsAny(line, "tax", "saving", "discount", "tender")) {
                receiptLabelSeen = true;
                if (money != null) {
                    receiptTotals.add(money);
                }
            } else if (head.stream().anyMatch(w -> TAX_WORD.matcher(w).matches())) {
                if (money != null) {
                    taxes.add(money);
                }
            } else if (money != null && !line.contains("change") && head.stream().anyMatch(w -> TENDER.matcher(w).matches())) {
                tenders.add(money);
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

        BigDecimal subtotal = single(new LinkedHashSet<>(subtotals), "subtotal", notes);
        BigDecimal tax = taxes.size() > 1 ? null : single(new LinkedHashSet<>(taxes), "tax", notes);
        if (taxes.size() > 1) {
            notes.add("More than one tax line was found, so the tax was not read.");
        }

        DocumentKind kind;
        BigDecimal total;
        if (billLabelSeen) {
            kind = DocumentKind.BILL;
            total = single(billTotals, "total", notes);
        } else {
            if (!receiptTotals.isEmpty()) {
                total = single(receiptTotals, "total", notes);
            } else {
                Fallback fallback = fallbackTotal(subtotal, subtotalLine, taxes, amountOnLine, tenders, notes);
                total = fallback.total();
                if (tax == null && fallback.inferredTax() != null) {
                    tax = fallback.inferredTax();
                }
            }
            kind = receiptLabelSeen || total != null || subtotal != null ? DocumentKind.RECEIPT : DocumentKind.UNKNOWN;
        }

        LocalDate due = singleDate(dueDates, "due date", notes);
        Set<LocalDate> documentDates = !billDates.isEmpty() ? billDates : otherDates;
        LocalDate documentDate = singleDate(documentDates, "date", notes);

        return new Extracted(kind, guessVendor(lines), total, subtotal, tax, documentDate, due, List.copyOf(notes));
    }

    /**
     * Combines several readings of the same photo. For each value the answer most readings agree on wins. A value only
     * one reading found is kept. When the readings split with no clear winner nothing is proposed, and a note gives the
     * numbers so the user can say which is right. Any disagreement at all is reported, so the user checks that value.
     */
    public static Extracted extractAll(List<String> readings) {
        List<Extracted> all = new ArrayList<>();
        for (String reading : readings) {
            all.add(extract(reading));
        }
        if (all.size() == 1) {
            return all.get(0);
        }
        List<String> notes = new ArrayList<>();
        BigDecimal total = vote(all.stream().map(Extracted::total).toList(), "total", notes);
        BigDecimal subtotal = vote(all.stream().map(Extracted::subtotal).toList(), "subtotal", notes);
        BigDecimal tax = vote(all.stream().map(Extracted::tax).toList(), "tax", notes);
        LocalDate date = vote(all.stream().map(Extracted::documentDate).toList(), "date", notes);
        LocalDate due = vote(all.stream().map(Extracted::dueDate).toList(), "due date", notes);
        DocumentKind kind = vote(all.stream().map(x -> x.kind() == DocumentKind.UNKNOWN ? null : x.kind()).toList(), "kind", new ArrayList<>());
        String vendor = all.stream().map(Extracted::vendor).filter(v -> v != null).findFirst().orElse(null);
        // Explanations of how a value was found, from the readings that agree with the final total.
        Set<String> explanations = new LinkedHashSet<>();
        for (Extracted x : all) {
            if (total != null && total.equals(x.total())) {
                explanations.addAll(x.notes());
            }
        }
        if (total == null) {
            for (Extracted x : all) {
                x.notes().stream().filter(n -> n.startsWith("More than one")).forEach(explanations::add);
            }
        }
        List<String> merged = new ArrayList<>(explanations);
        merged.addAll(notes);
        return new Extracted(kind == null ? DocumentKind.UNKNOWN : kind, vendor, total, subtotal, tax, date, due, List.copyOf(merged));
    }

    private static <T> T vote(List<T> values, String what, List<String> notes) {
        java.util.Map<T, Integer> counts = new java.util.LinkedHashMap<>();
        for (T value : values) {
            if (value != null) {
                counts.merge(value, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return null;
        }
        if (counts.size() == 1) {
            return counts.keySet().iterator().next();
        }
        // Readings that differ are not outvoted: a digit misread can repeat, so a majority proves nothing.
        // Nothing is proposed for this value; the note names the candidates and the user decides.
        Set<String> shown = new TreeSet<>();
        counts.keySet().forEach(v -> shown.add(v instanceof BigDecimal b ? b.toPlainString() : String.valueOf(v)));
        if ("kind".equals(what)) {
            return counts.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).get().getKey();
        }
        notes.add("The readings of this photo disagree on the " + what + " (" + String.join(" or ", shown)
                + "). Say which is right.");
        return null;
    }

    private record Fallback(BigDecimal total, BigDecimal inferredTax) {}

    /**
     * Used when no line is clearly labelled as the total (the label is the part of a receipt most often misread).
     * First choice: a total that the numbers themselves prove, because subtotal plus tax equals an amount printed
     * on the receipt. Second choice: the one amount on a payment line. Either way a note tells the user how it was
     * found, and the user still confirms it against the receipt.
     */
    private static Fallback fallbackTotal(BigDecimal subtotal, int subtotalLine, List<BigDecimal> taxes,
                                          List<BigDecimal> amountOnLine, Set<BigDecimal> tenders, List<String> notes) {
        if (subtotal != null) {
            Set<BigDecimal> everywhere = new LinkedHashSet<>();
            amountOnLine.stream().filter(a -> a != null).forEach(everywhere::add);
            if (taxes.size() == 1) {
                BigDecimal sum = subtotal.add(taxes.get(0));
                if (everywhere.contains(sum)) {
                    notes.add("No total line could be read. The total was worked out by adding the subtotal and the tax."
                            + " Check it against the receipt.");
                    mentionPaymentMismatch(sum, tenders, notes);
                    return new Fallback(sum, null);
                }
            } else if (taxes.isEmpty() && subtotalLine >= 0) {
                Set<BigDecimal> near = new LinkedHashSet<>();
                for (int j = subtotalLine + 1; j <= Math.min(amountOnLine.size() - 1, subtotalLine + 4); j++) {
                    if (amountOnLine.get(j) != null) {
                        near.add(amountOnLine.get(j));
                    }
                }
                BigDecimal foundTax = null;
                BigDecimal foundTotal = null;
                int matches = 0;
                for (BigDecimal x : near) {
                    BigDecimal sum = subtotal.add(x);
                    if (x.signum() > 0 && near.contains(sum)) {
                        foundTax = x;
                        foundTotal = sum;
                        matches++;
                    }
                }
                if (matches == 1) {
                    notes.add("No total or tax line could be read. Adding the subtotal and the amount " + foundTax.toPlainString()
                            + " gives " + foundTotal.toPlainString() + ", which is printed on the receipt, so that was used as the total."
                            + " Check it against the receipt.");
                    mentionPaymentMismatch(foundTotal, tenders, notes);
                    return new Fallback(foundTotal, foundTax);
                }
            }
        }
        if (tenders.size() == 1) {
            notes.add("No total line could be read. The total was taken from the payment line. Check it against the receipt.");
            return new Fallback(tenders.iterator().next(), null);
        }
        if (tenders.size() > 1) {
            Set<String> sorted = new TreeSet<>();
            tenders.forEach(v -> sorted.add(v.toPlainString()));
            notes.add("No total line could be read, and more than one payment amount was found (" + String.join(" and ", sorted)
                    + "). Say which is right.");
        }
        return new Fallback(null, null);
    }

    private static void mentionPaymentMismatch(BigDecimal total, Set<BigDecimal> tenders, List<String> notes) {
        if (!tenders.isEmpty() && !tenders.contains(total)) {
            Set<String> sorted = new TreeSet<>();
            tenders.forEach(v -> sorted.add(v.toPlainString()));
            notes.add("A payment line shows " + String.join(" and ", sorted) + ", which does not match " + total.toPlainString()
                    + ". One of them may be misread.");
        }
    }

    /** The first few words of a line in lower case, with punctuation and stray symbols trimmed from each word. */
    private static List<String> headWords(String lowerLine, int count) {
        List<String> words = new ArrayList<>();
        for (String raw : lowerLine.trim().split("\\s+")) {
            String word = raw.replaceAll("^[^a-z0-9]+|[^a-z0-9]+$", "");
            if (!word.isEmpty()) {
                words.add(word);
                if (words.size() == count) {
                    break;
                }
            }
        }
        return words;
    }

    private static boolean hasTotalWord(List<String> head) {
        for (int i = 0; i < head.size(); i++) {
            String w = head.get(i);
            if (i > 0 && head.get(i - 1).equals("sub")) {
                continue;
            }
            if (w.equals("total") || w.equals("totals") || (w.length() >= 4 && w.length() <= 5 && distance(w, "total") <= 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSubtotalWord(List<String> head) {
        for (int i = 0; i < head.size(); i++) {
            String w = head.get(i);
            if (w.length() >= 7 && w.length() <= 9 && distance(w, "subtotal") <= 1) {
                return true;
            }
            // A garbled front such as "btotal" or "ubtotal" is still a subtotal, never the total.
            if (w.endsWith("total") && w.length() >= 6 && w.length() <= 7) {
                return true;
            }
            if (w.equals("sub") && i + 1 < head.size() && distance(head.get(i + 1), "total") <= 1) {
                return true;
            }
        }
        return false;
    }

    /** Edit distance: how many single-letter changes turn one word into the other. Lets "tota1" or "totai" still count as "total". */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[b.length()];
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

    /** The first of the top lines that looks like a name: mostly letters, no amount, no date, not a heading word or a web address. */
    private static String guessVendor(List<String> lines) {
        int seen = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (++seen > 8) {
                break;
            }
            // Drop stray one-character words and symbols the OCR puts in front of the name.
            String text = line.trim().replaceAll("^(?:[^A-Za-z0-9]+|\\S\\s+)+", "").replaceAll("\\s+", " ").trim();
            long letters = text.chars().filter(Character::isLetter).count();
            if (letters < 3 || letters * 3 < text.replace(" ", "").length() * 2) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            if (MONEY.matcher(text).find() || !datesIn(lower).isEmpty() || NOT_A_VENDOR.matcher(lower).find()
                    || NOT_A_NAME_LINE.matcher(text).find()) {
                continue;
            }
            return text.length() > DocumentVerifier.MAX_VENDOR_LENGTH
                    ? text.substring(0, DocumentVerifier.MAX_VENDOR_LENGTH).trim() : text;
        }
        return null;
    }
}
