package za.co.mafori.creditadvisory.report;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Writes report exports as CSV (RFC 4180) that Excel opens with a double-click.
 *
 * <p>
 * Two safety rules for every text cell:
 * <ul>
 * <li>Text that starts with = + - @ (or a tab / carriage return) gets a '
 * in front, so Excel shows it as text instead of running it as a formula
 * ("CSV injection"). Names and budget structures come from the register, so
 * anyone who typed into the workbook could otherwise plant a formula.</li>
 * <li>Text with a comma, quote or line break is put in quotes, with quotes
 * doubled.</li>
 * </ul>
 * Numbers are written plainly (1900.00), never guarded, so Excel can still add
 * them up.
 */
final class ReportCsv {

    /**
     * Excel only reads a CSV file as UTF-8 (names with accents) when it starts with
     * this byte-order mark.
     */
    static final String BYTE_ORDER_MARK = "\uFEFF";

    private static final String LINE_END = "\r\n";

    private ReportCsv() {
    }

    /** The whole file: byte-order mark, header line, one line per row. */
    static String document(List<String> header, List<List<Object>> rows) {
        String headerLine = header.stream().map(ReportCsv::cell).collect(Collectors.joining(","));
        String body = rows.stream()
                .map(row -> row.stream().map(ReportCsv::cell).collect(Collectors.joining(",")))
                .collect(Collectors.joining(LINE_END));
        return BYTE_ORDER_MARK + headerLine + LINE_END + (body.isEmpty() ? "" : body + LINE_END);
    }

    /** One cell. null is an empty cell. */
    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal number) {
            return number.toPlainString();
        }
        if (value instanceof Number || value instanceof LocalDate) {
            return value.toString();
        }
        if (value instanceof Boolean yes) {
            return yes ? "Yes" : "No";
        }
        return text(value.toString());
    }

    /** "Monday" for 1 ... "Sunday" for 7. */
    /**
     * The Target cell of a summary row. Company reports (scope ALL) say "Not
     * configured" when no target was set for that day or week, instead of
     * leaving it blank; a consultant's own report never has targets, so it
     * stays empty.
     */
    static Object target(String scope, BigDecimal target) {
        if (target != null) {
            return target;
        }
        return ReportService.ALL.equals(scope) ? NOT_CONFIGURED : null;
    }

    static final String NOT_CONFIGURED = "Not configured";

    static String dayName(int dayOfWeek) {
        return DayOfWeek.of(dayOfWeek).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static String text(String raw) {
        String value = raw;
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return needsQuotes ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
