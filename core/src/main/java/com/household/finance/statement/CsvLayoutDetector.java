package com.household.finance.statement;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Works out what the columns of an unfamiliar bank CSV mean, so the app can ask the user to confirm instead of
 * refusing the file. It only proposes: nothing here saves anything, and anything it is unsure about becomes a
 * question in plain words. Pure: no database.
 * <p>
 * Handles a few lines of preamble above the column names, no header row at all, one signed amount column or
 * separate debit and credit columns, an optional running balance, and the common date styles.
 */
public final class CsvLayoutDetector {

    static final List<String> DATE_FORMATS = List.of("M/d/uuuu", "M/d/uu", "uuuu-M-d", "d/M/uuuu", "M-d-uuuu");
    private static final int SAMPLE = 200;
    private static final double MOSTLY = 0.9;
    private static final Pattern PLAIN_AMOUNT = Pattern.compile("\\d+(\\.\\d{1,2})?");
    private static final Pattern DESC_HINT = Pattern.compile("desc|memo|payee|detail|name|merchant|narrat", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEBIT_HINT = Pattern.compile("debit|withdraw|charge|paid out|money out", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDIT_HINT = Pattern.compile("credit|deposit|paid in|money in", Pattern.CASE_INSENSITIVE);

    private CsvLayoutDetector() {}

    /** True when the pattern is one of the date styles the detector understands. */
    public static boolean isSupportedDateFormat(String pattern) {
        return DATE_FORMATS.contains(pattern);
    }

    public static LayoutProposal detect(Reader csv) throws IOException {
        return detect(CsvReader.read(csv));
    }

    /** Identifies the file's layout: its column names, or its width when there is no header row. Null if no data rows. */
    public static String fingerprint(Reader csv) throws IOException {
        return fingerprint(CsvReader.read(csv));
    }

    static String fingerprint(List<CsvReader.Row> rows) {
        int start = firstDataRow(rows);
        if (start < 0) {
            return null;
        }
        int width = rows.get(start).fields().size();
        List<String> header = headerOf(rows, start, width);
        if (header == null) {
            return "noheader:" + width;
        }
        return String.join("|", header.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList());
    }

    // ------------------------------------------------------------------ detection

    static LayoutProposal detect(List<CsvReader.Row> rows) {
        int start = firstDataRow(rows);
        if (start < 0) {
            return incomplete(List.of(), List.of("I could not find any row that has both a date and an amount."
                    + " Is this a transaction list? Nothing was saved."));
        }
        int width = rows.get(start).fields().size();
        List<String> header = headerOf(rows, start, width);
        List<String> names = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            names.add(header != null && !header.get(c).isBlank() ? header.get(c).trim() : "column " + (c + 1));
        }
        List<CsvReader.Row> data = new ArrayList<>();
        for (int i = start; i < rows.size() && data.size() < SAMPLE; i++) {
            CsvReader.Row r = rows.get(i);
            if (r.fields().size() == width && looksLikeData(r)) {
                data.add(r);
            }
        }
        String fingerprint = fingerprint(rows);
        List<String> questions = new ArrayList<>();
        int ignored = header != null ? start - 1 : start;
        if (ignored > 0) {
            questions.add("I ignored " + ignored + " line(s) above the column names (a summary or title)."
                    + " Is that right?");
        }

        // Classify every column.
        List<Integer> dateCols = new ArrayList<>();
        List<Integer> moneyCols = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            List<String> cells = nonBlank(data, c);
            if (cells.isEmpty()) {
                continue;
            }
            if (!formatsAccepting(cells).isEmpty()) {
                dateCols.add(c);
            } else if (share(cells, v -> isMoneyLike(v)) >= MOSTLY) {
                moneyCols.add(c);
            }
        }
        if (dateCols.isEmpty()) {
            return incomplete(names, List.of("I could not tell which column holds the date."));
        }

        int dateCol = dateCols.get(0);
        if (dateCols.size() > 1) {
            Integer posting = null;
            for (int c : dateCols) {
                if (header != null && header.get(c).toLowerCase(Locale.ROOT).contains("post")) {
                    posting = c;
                    break;
                }
            }
            dateCol = posting != null ? posting : dateCols.get(0);
            questions.add("Two columns look like dates (" + joinNames(names, dateCols) + "). I used '"
                    + names.get(dateCol) + "'. Is that the day the money moved?");
        }
        List<String> dateCells = nonBlank(data, dateCol);
        List<String> accepted = formatsAccepting(dateCells);
        String dateFormat = accepted.get(0);
        if (accepted.contains("M/d/uuuu") && accepted.contains("d/M/uuuu")) {
            questions.add("Dates such as " + dateCells.get(0) + " could be month first or day first."
                    + " I assumed month first (US style). Is that right?");
        }

        // Description: a text column, preferring a descriptive header, otherwise the longest text.
        int descCol = -1;
        double best = -1;
        for (int c = 0; c < width; c++) {
            if (c == dateCol || moneyCols.contains(c) || dateCols.contains(c)) {
                continue;
            }
            List<String> cells = nonBlank(data, c);
            if (cells.isEmpty()) {
                continue;
            }
            double score = cells.stream().mapToInt(String::length).average().orElse(0);
            if (header != null && DESC_HINT.matcher(header.get(c)).find()) {
                score += 1000;
            }
            if (score > best) {
                best = score;
                descCol = c;
            }
        }
        if (descCol < 0) {
            return incomplete(names, List.of("I could not tell which column describes the transaction."));
        }

        // Balance column (only recognised by its name), then the amount column(s).
        int balanceCol = -1;
        for (int c : moneyCols) {
            if (header != null && header.get(c).toLowerCase(Locale.ROOT).contains("bal")) {
                balanceCol = c;
            }
        }
        List<Integer> amountCols = new ArrayList<>(moneyCols);
        if (balanceCol >= 0) {
            amountCols.remove(Integer.valueOf(balanceCol));
        }
        if (header == null && amountCols.size() == 2 && bothAlwaysFilled(data, amountCols)) {
            return incomplete(names, List.of("There are two number columns on every row and no column names."
                    + " Which one is the amount and which is the running balance?"));
        }

        CsvLayout.AmountMode mode;
        int amountCol = -1;
        int debitCol = -1;
        int creditCol = -1;
        boolean outIsNegative = true;
        if (amountCols.size() == 1) {
            mode = CsvLayout.AmountMode.SINGLE;
            amountCol = amountCols.get(0);
        } else if (amountCols.size() == 2 && neverBothFilled(data, amountCols)) {
            mode = CsvLayout.AmountMode.DEBIT_CREDIT;
            int a = amountCols.get(0);
            int b = amountCols.get(1);
            boolean aDebit = header != null && DEBIT_HINT.matcher(header.get(a)).find();
            boolean bDebit = header != null && DEBIT_HINT.matcher(header.get(b)).find();
            boolean aCredit = header != null && CREDIT_HINT.matcher(header.get(a)).find();
            boolean bCredit = header != null && CREDIT_HINT.matcher(header.get(b)).find();
            if (bDebit || aCredit) {
                debitCol = b;
                creditCol = a;
            } else {
                debitCol = a;
                creditCol = b;
                if (!(aDebit || bCredit)) {
                    questions.add("There are two amount columns ('" + names.get(a) + "' and '" + names.get(b)
                            + "'). I assumed the first is money going out and the second is money coming in."
                            + " Is that right?");
                }
            }
        } else {
            return incomplete(names, List.of("I found " + amountCols.size() + " number columns ("
                    + joinNames(names, amountCols) + ") and could not tell which is the amount."));
        }

        if (mode == CsvLayout.AmountMode.SINGLE) {
            List<BigDecimal> values = new ArrayList<>();
            for (CsvReader.Row r : data) {
                BigDecimal v = parseMoney(r.fields().get(amountCol));
                if (v != null) {
                    values.add(v);
                }
            }
            boolean anyNegative = values.stream().anyMatch(v -> v.signum() < 0);
            boolean anyPositive = values.stream().anyMatch(v -> v.signum() > 0);
            if (!anyNegative) {
                outIsNegative = false;
                questions.add("Every amount is positive. I assumed a positive number is money spent (typical of a"
                        + " credit card file). Is that right? If positive means money received, say so.");
            } else {
                outIsNegative = true;
                if (balanceCol >= 0) {
                    Boolean byBalance = signFromBalance(data, amountCol, balanceCol);
                    if (byBalance != null) {
                        outIsNegative = byBalance;
                    } else {
                        questions.add("The running balance does not follow from the amounts in either direction, so I"
                                + " could not use it to check which sign means money out.");
                    }
                }
                if (!anyPositive) {
                    questions.add("Every amount is negative. I assumed negative means money spent. Is that right?");
                } else {
                    questions.add("I read negative numbers as money going out and positive numbers as money coming in."
                            + " Is that right?");
                }
            }
        }

        CsvLayout layout = new CsvLayout(fingerprint, dateCol, dateFormat, descCol, mode, amountCol, debitCol,
                creditCol, outIsNegative, balanceCol);
        List<String> preview = new ArrayList<>();
        try {
            List<StatementLine> sample = new LearnedCsvParser(layout, false).parseRows(rows);
            for (int i = 0; i < Math.min(3, sample.size()); i++) {
                StatementLine l = sample.get(i);
                preview.add(l.postedOn() + " | " + l.description() + " | "
                        + (l.direction() == StatementLine.Direction.EXPENSE ? "money out " : "money in ")
                        + l.amount().toPlainString());
            }
        } catch (StatementParseException e) {
            return incomplete(names, List.of("I guessed the columns but a row did not fit (" + e.getMessage()
                    + "). Please check the file or tell me the columns."));
        }
        questions.add(0, "I read this file as: date = '" + names.get(dateCol) + "', description = '"
                + names.get(descCol) + "', " + (mode == CsvLayout.AmountMode.SINGLE
                ? "amount = '" + names.get(amountCol) + "'"
                : "money out = '" + names.get(debitCol) + "', money in = '" + names.get(creditCol) + "'")
                + (balanceCol >= 0 ? ", running balance = '" + names.get(balanceCol) + "'" : "")
                + ". The first rows below show how I would import them. Is this right?");
        return new LayoutProposal(true, layout, names, List.copyOf(questions), List.copyOf(preview));
    }

    private static LayoutProposal incomplete(List<String> names, List<String> questions) {
        return new LayoutProposal(false, null, names, questions, List.of());
    }

    // ------------------------------------------------------------------ helpers

    /** Index of the first row that has both a date and an amount; -1 if none. */
    static int firstDataRow(List<CsvReader.Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (looksLikeData(rows.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean looksLikeData(CsvReader.Row row) {
        boolean date = false;
        boolean money = false;
        for (String f : row.fields()) {
            date |= isDateLike(f);
            money |= isMoneyLike(f);
        }
        return date && money;
    }

    /** The column-name row: the line just above the data, if it has the same width and is not itself data. */
    private static List<String> headerOf(List<CsvReader.Row> rows, int start, int width) {
        if (start == 0) {
            return null;
        }
        CsvReader.Row candidate = rows.get(start - 1);
        if (candidate.fields().size() != width || looksLikeData(candidate)) {
            return null;
        }
        long filled = candidate.fields().stream().filter(f -> !f.isBlank()).count();
        return filled >= 2 ? candidate.fields() : null;
    }

    private static List<String> nonBlank(List<CsvReader.Row> data, int col) {
        List<String> out = new ArrayList<>();
        for (CsvReader.Row r : data) {
            String v = col < r.fields().size() ? r.fields().get(col).trim() : "";
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private static double share(List<String> cells, java.util.function.Predicate<String> test) {
        return cells.stream().filter(test).count() / (double) cells.size();
    }

    private static List<String> formatsAccepting(List<String> cells) {
        List<String> ok = new ArrayList<>();
        for (String pattern : DATE_FORMATS) {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern, Locale.US).withResolverStyle(ResolverStyle.STRICT);
            if (share(cells, v -> parseDate(v, f) != null) >= MOSTLY) {
                ok.add(pattern);
            }
        }
        return ok;
    }

    private static boolean bothAlwaysFilled(List<CsvReader.Row> data, List<Integer> cols) {
        return data.stream().allMatch(r -> !r.fields().get(cols.get(0)).isBlank() && !r.fields().get(cols.get(1)).isBlank());
    }

    private static boolean neverBothFilled(List<CsvReader.Row> data, List<Integer> cols) {
        return data.stream().noneMatch(r -> !r.fields().get(cols.get(0)).isBlank() && !r.fields().get(cols.get(1)).isBlank());
    }

    private static String joinNames(List<String> names, List<Integer> cols) {
        return String.join(", ", cols.stream().map(c -> "'" + names.get(c) + "'").toList());
    }

    /** True or false when exactly one sign convention makes the running balance follow; null when neither does. */
    private static Boolean signFromBalance(List<CsvReader.Row> data, int amountCol, int balanceCol) {
        boolean negativeOut = balanceFollows(data, amountCol, balanceCol, true);
        boolean positiveOut = balanceFollows(data, amountCol, balanceCol, false);
        if (negativeOut == positiveOut) {
            return null;
        }
        return negativeOut;
    }

    private static boolean balanceFollows(List<CsvReader.Row> data, int amountCol, int balanceCol, boolean outIsNegative) {
        int checked = 0;
        int oldestFirst = 0;
        int newestFirst = 0;
        for (int i = 1; i < data.size(); i++) {
            BigDecimal prevBal = parseMoney(data.get(i - 1).fields().get(balanceCol));
            BigDecimal curBal = parseMoney(data.get(i).fields().get(balanceCol));
            BigDecimal prevAmt = parseMoney(data.get(i - 1).fields().get(amountCol));
            BigDecimal curAmt = parseMoney(data.get(i).fields().get(amountCol));
            if (prevBal == null || curBal == null || prevAmt == null || curAmt == null) {
                continue;
            }
            checked++;
            BigDecimal curEffect = outIsNegative ? curAmt : curAmt.negate();
            BigDecimal prevEffect = outIsNegative ? prevAmt : prevAmt.negate();
            if (prevBal.add(curEffect).compareTo(curBal) == 0) {
                oldestFirst++;
            }
            if (curBal.add(prevEffect).compareTo(prevBal) == 0) {
                newestFirst++;
            }
        }
        return checked > 0 && Math.max(oldestFirst, newestFirst) >= MOSTLY * checked;
    }

    // ------------------------------------------------------------------ shared parsing

    static LocalDate parseDate(String raw, DateTimeFormatter formatter) {
        try {
            return LocalDate.parse(raw.trim(), formatter);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    static boolean isDateLike(String raw) {
        for (String pattern : DATE_FORMATS) {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern, Locale.US).withResolverStyle(ResolverStyle.STRICT);
            if (parseDate(raw, f) != null) {
                return true;
            }
        }
        return false;
    }

    /** A money-looking cell must show decimals, so years, counts and check numbers are not mistaken for amounts. */
    static boolean isMoneyLike(String raw) {
        return raw.contains(".") && parseMoney(raw) != null;
    }

    /** "$1,234.56", "-45.20", "(45.20)", "45.20-" and "-$45.20" all parse; anything else is null. */
    static BigDecimal parseMoney(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.endsWith("-")) {
            negative = true;
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.startsWith("-")) {
            negative = true;
            s = s.substring(1).trim();
        }
        s = s.replace("$", "").replace(",", "").trim();
        if (s.startsWith("-")) {
            negative = true;
            s = s.substring(1).trim();
        }
        if (s.startsWith("+")) {
            s = s.substring(1).trim();
        }
        if (!PLAIN_AMOUNT.matcher(s).matches()) {
            return null;
        }
        BigDecimal value = new BigDecimal(s).setScale(2);
        return negative ? value.negate() : value;
    }
}
