package za.co.mafori.creditadvisory.imports.clientlist;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import za.co.mafori.creditadvisory.client.PaymentMethod;
import za.co.mafori.creditadvisory.common.idnumber.InvalidIdNumberException;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;
import za.co.mafori.creditadvisory.common.phone.PhoneNumbers;
import za.co.mafori.creditadvisory.imports.excel.RawCell;
import za.co.mafori.creditadvisory.imports.excel.RawSheet;
import za.co.mafori.creditadvisory.imports.excel.RawWorkbook;
import za.co.mafori.creditadvisory.imports.register.ParseIssue;
import za.co.mafori.creditadvisory.imports.register.ParseIssue.Severity;

/**
 * Reads the launch client list (docs/api-client-list-import.md, section 2).
 * Pure: no database, so every rule has a unit test (ClientListParserTest).
 *
 * <ul>
 * <li>The list is the one visible sheet whose header row (in its first 20
 * rows) has Name, Surname and ID number. Headers are matched through
 * client_list_header_aliases, never by position.</li>
 * <li>Name, Surname and a valid SA ID number are required: without them the
 * row is not imported (an ERROR on that row). Everything else is optional: a
 * value that cannot be stored (a phone that is not a phone, an email without
 * an @) is a WARNING and left out, and the client is still imported.</li>
 * <li>"DEBIT ORDER ..." in the employer column is not an employer: the client
 * gets payment method DEBIT_ORDER and no employer.</li>
 * <li>Text that was saved with the wrong encoding somewhere before the file
 * reached us (garbled letters in place of an accented one) is a WARNING, so it
 * can be fixed in the file. The value is still imported as it is.</li>
 * <li>Messages never contain a value from the sheet, only the column and the
 * reason (an ID number or a name must never reach import_errors).</li>
 * </ul>
 */
@Component
public class ClientListParser {

    static final int HEADER_SEARCH_ROWS = 20;
    static final Set<ClientListField> REQUIRED = EnumSet.of(ClientListField.NAME, ClientListField.SURNAME,
            ClientListField.ID_NUMBER);

    // Issue codes (docs section 5)
    public static final String FILE_NO_LIST = "file:no-client-list";
    public static final String FILE_SEVERAL_LISTS = "file:several-client-lists";
    public static final String SHEET_DUPLICATE_COLUMN = "sheet:duplicate-column";
    public static final String SHEET_UNKNOWN_COLUMN = "sheet:unknown-column";
    public static final String SHEET_EMPTY = "sheet:empty";
    public static final String ROW_MISSING_NAME = "row:missing-name";
    public static final String ROW_MISSING_SURNAME = "row:missing-surname";
    public static final String ROW_VALUE_TOO_LONG = "row:value-too-long";
    public static final String ROW_MISSING_ID = "row:missing-id-number";
    public static final String ROW_INVALID_ID = "row:invalid-id-number";
    public static final String ROW_ID_LEADING_ZERO = "row:id-leading-zero-restored";
    public static final String ROW_INVALID_PHONE = "row:invalid-phone";
    public static final String ROW_PHONE_LEADING_ZERO = "row:phone-leading-zero-restored";
    public static final String ROW_INVALID_EMAIL = "row:invalid-email";
    public static final String ROW_INVALID_CONSULTANT_EMAIL = "row:invalid-consultant-email";
    public static final String ROW_EMAIL_WITHOUT_CONSULTANT = "row:email-without-consultant";
    public static final String ROW_INVALID_FILE_REFERENCE = "row:invalid-file-reference";
    public static final String ROW_FORMULA_ERROR_IGNORED = "row:formula-error-ignored";
    public static final String ROW_GARBLED_TEXT = "row:garbled-text";
    public static final String ROW_NO_CLIENT = "row:no-client";

    private static final String EMAIL = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";
    private static final String FILE_REFERENCE = "^[0-9]{1,20}$";

    /**
     * What UTF-8 text looks like after it was read as Windows-1252, once or more
     * (mojibake): an accented letter such as e-acute turns into A-tilde followed
     * by a second character, a degree sign into A-circumflex and the sign, and
     * curly quotes and dashes into a-circumflex, euro sign and a third character.
     * Also the replacement character, left where a byte could not be read at all.
     *
     * <ul>
     * <li>A-tilde followed by one of the characters Windows-1252 shows for the
     * bytes 0x80 to 0xBF.</li>
     * <li>A-circumflex followed by a character from U+00A0 to U+00BF.</li>
     * <li>a-circumflex followed by the euro sign.</li>
     * <li>U+FFFD.</li>
     * </ul>
     * Real names with accents (Zoe with a diaeresis, Sao with a tilde) never
     * match: an accented letter is never followed by one of these characters.
     */
    static final Pattern GARBLED = Pattern.compile("\u00C3[\u0080-\u00BF\u0152\u0153\u0160\u0161\u0178"
            + "\u017D\u017E\u0192\u02C6\u02DC\u2013\u2014\u2018-\u201E\u2020-\u2022\u2026\u2030"
            + "\u2039\u203A\u20AC\u2122]|\u00C2[\u00A0-\u00BF]|\u00E2\u20AC|\uFFFD");

    /** True if the text looks garbled (see GARBLED). */
    static boolean looksGarbled(String text) {
        return text != null && GARBLED.matcher(text).find();
    }

    /**
     * One column of the sheet: its header as written, and the field it is (null =
     * not imported).
     */
    public record Column(int index, String header, ClientListField field) {
    }

    /**
     * A client row as read. Everything already cleaned; null = not in the sheet or
     * left out.
     */
    public record Row(int rowNumber, String firstName, String lastName, SaIdNumber idNumber, String consultantName,
            String consultantEmail, String email, String cellphone, String workPhone, String homePhone,
            String employerName, PaymentMethod paymentMethod, String productQuotation, String fileNumber,
            String fileId, boolean rejected) {

        /** Never prints the values. */
        @Override
        public String toString() {
            return "Row[" + rowNumber + (rejected ? ", rejected" : "") + "]";
        }
    }

    /**
     * @param sheetName null when no sheet is a client list (then there is a file
     *                  error in issues)
     * @param headerRow the header's row number as Excel shows it
     */
    public record Result(String sheetName, Integer headerRow, List<Column> columns, List<Row> rows,
            List<ParseIssue> issues) {

        public boolean blocked() {
            return issues.stream().anyMatch(ParseIssue::blocksImport);
        }
    }

    /**
     * Text from the sheet that goes into a message (a header, a sheet name): long
     * runs of digits are hidden, so a stray ID number can never reach
     * import_errors, and it is cut to fit the column.
     */
    static String safe(String text) {
        if (text == null) {
            return null;
        }
        String masked = text.replaceAll("\\d{6,}", "******");
        return masked.length() > 60 ? masked.substring(0, 60) : masked;
    }

    /** Trims, upper-cases and collapses inner spaces, as the aliases are stored. */
    static String normalizeHeader(String text) {
        return text == null ? null : text.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    public Result parse(RawWorkbook workbook, Map<String, ClientListField> aliases) {
        List<ParseIssue> issues = new ArrayList<>();

        record Candidate(RawSheet sheet, int headerIndex, List<Column> columns) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (RawSheet sheet : workbook.sheets()) {
            if (sheet.hidden() || sheet.isEmpty()) {
                continue;
            }
            for (Integer r : sheet.rows().navigableKeySet()) {
                if (r >= HEADER_SEARCH_ROWS) {
                    break;
                }
                List<Column> columns = columns(sheet.rows().get(r), aliases);
                Set<ClientListField> found = EnumSet.noneOf(ClientListField.class);
                columns.stream().filter(c -> c.field() != null).forEach(c -> found.add(c.field()));
                if (found.containsAll(REQUIRED)) {
                    candidates.add(new Candidate(sheet, r, columns));
                    break;
                }
            }
        }
        if (candidates.isEmpty()) {
            issues.add(new ParseIssue(Severity.ERROR, FILE_NO_LIST, null, null, null,
                    "No sheet has a header row with Name, Surname and ID number in its first "
                            + HEADER_SEARCH_ROWS + " rows."));
            return new Result(null, null, List.of(), List.of(), issues);
        }
        if (candidates.size() > 1) {
            issues.add(new ParseIssue(Severity.ERROR, FILE_SEVERAL_LISTS, null, null, null,
                    "More than one sheet looks like a client list (" + String.join(", ",
                            candidates.stream().map(c -> safe(c.sheet().name())).toList())
                            + "). Keep the clients on one sheet, or delete the other sheets."));
            return new Result(null, null, List.of(), List.of(), issues);
        }

        Candidate list = candidates.get(0);
        String sheetName = list.sheet().name();
        Map<ClientListField, Integer> columnOf = new EnumMap<>(ClientListField.class);
        for (Column c : list.columns()) {
            if (c.field() == null) {
                issues.add(new ParseIssue(Severity.INFO, SHEET_UNKNOWN_COLUMN, sheetName, null, safe(c.header()),
                        "Column \"" + safe(c.header()) + "\" is not imported."));
            } else if (columnOf.putIfAbsent(c.field(), c.index()) != null) {
                issues.add(new ParseIssue(Severity.ERROR, SHEET_DUPLICATE_COLUMN, sheetName, null, safe(c.header()),
                        "Two columns are " + c.field().label() + ". Keep one."));
            }
        }
        if (issues.stream().anyMatch(ParseIssue::blocksImport)) {
            return new Result(sheetName, list.headerIndex() + 1, list.columns(), List.of(), issues);
        }

        List<Row> rows = new ArrayList<>();
        for (var entry : list.sheet().rows().tailMap(list.headerIndex(), false).entrySet()) {
            RowReader reader = new RowReader(sheetName, entry.getKey() + 1, entry.getValue(), columnOf, issues);
            Row row = reader.read();
            if (row != null) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            issues.add(new ParseIssue(Severity.ERROR, SHEET_EMPTY, sheetName, null, null,
                    "The client list has a header row but no clients under it."));
        }
        return new Result(sheetName, list.headerIndex() + 1, list.columns(), rows, issues);
    }

    private static List<Column> columns(Map<Integer, RawCell> cells, Map<String, ClientListField> aliases) {
        List<Column> columns = new ArrayList<>();
        new java.util.TreeMap<>(cells).forEach((index, cell) -> {
            if (cell.kind() == RawCell.Kind.TEXT && !cell.text().isBlank()) {
                String header = cell.text().strip().replaceAll("\\s+", " ");
                columns.add(new Column(index, header, aliases.get(normalizeHeader(header))));
            }
        });
        return columns;
    }

    /**
     * Reads one sheet row. Collects that row's issues; null for a row that is not a
     * client row.
     */
    private static final class RowReader {
        private final String sheet;
        private final int rowNumber;
        private final Map<Integer, RawCell> cells;
        private final Map<ClientListField, Integer> columnOf;
        private final List<ParseIssue> issues;
        private boolean rejected;

        RowReader(String sheet, int rowNumber, Map<Integer, RawCell> cells, Map<ClientListField, Integer> columnOf,
                List<ParseIssue> issues) {
            this.sheet = sheet;
            this.rowNumber = rowNumber;
            this.cells = cells;
            this.columnOf = columnOf;
            this.issues = issues;
        }

        private void add(Severity severity, String code, ClientListField field, String message) {
            issues.add(new ParseIssue(severity, code, sheet, rowNumber, field == null ? null : field.label(),
                    message));
            if (severity == Severity.ERROR) {
                rejected = true;
            }
        }

        private RawCell cell(ClientListField field) {
            Integer index = columnOf.get(field);
            return index == null ? null : cells.get(index);
        }

        /**
         * A cell as text: trimmed, inner spaces collapsed; whole numbers without ".0".
         */
        private String text(ClientListField field) {
            RawCell cell = cell(field);
            if (cell == null) {
                return null;
            }
            String value = switch (cell.kind()) {
                case TEXT -> cell.text();
                case NUMBER -> plain(cell.number());
                case BOOLEAN -> String.valueOf(cell.bool());
                case ERROR -> {
                    add(Severity.WARNING, ROW_FORMULA_ERROR_IGNORED, field,
                            field.label() + " shows " + cell.error() + "; treated as empty.");
                    yield null;
                }
            };
            if (value == null) {
                return null;
            }
            value = value.strip().replaceAll("\\s+", " ");
            if (value.isEmpty()) {
                return null;
            }
            if (cell.kind() == RawCell.Kind.TEXT && looksGarbled(value)) {
                // The message never repeats the value: only the column and how to recognise it.
                add(Severity.WARNING, ROW_GARBLED_TEXT, field, field.label()
                        + " has garbled letters (for example \u00C3\u00A9 where \u00E9 belongs): the text was "
                        + "saved with the wrong encoding before it reached us. It is imported as it is; fix it in "
                        + "the file.");
            }
            return value;
        }

        private static String plain(BigDecimal n) {
            return n == null ? null : n.stripTrailingZeros().toPlainString();
        }

        private String limited(ClientListField field, String value, int max, boolean required) {
            if (value != null && value.length() > max) {
                add(required ? Severity.ERROR : Severity.WARNING, ROW_VALUE_TOO_LONG, field,
                        field.label() + " is longer than " + max + " characters"
                                + (required ? "." : "; left out."));
                return required ? value : null;
            }
            return value;
        }

        Row read() {
            String firstName = text(ClientListField.NAME);
            String lastName = text(ClientListField.SURNAME);
            RawCell idCell = cell(ClientListField.ID_NUMBER);
            boolean noId = idCell == null || idCell.isBlankText();
            if (firstName == null && lastName == null && noId) {
                if (cells.values().stream().anyMatch(RowReader::hasValue)) {
                    add(Severity.WARNING, ROW_NO_CLIENT, null,
                            "This row has values but no name, surname or ID number. It is not imported.");
                }
                return null;
            }
            if (firstName == null) {
                add(Severity.ERROR, ROW_MISSING_NAME, ClientListField.NAME, "Name is empty.");
            }
            if (lastName == null) {
                add(Severity.ERROR, ROW_MISSING_SURNAME, ClientListField.SURNAME, "Surname is empty.");
            }
            firstName = limited(ClientListField.NAME, firstName, 100, true);
            lastName = limited(ClientListField.SURNAME, lastName, 100, true);
            SaIdNumber id = idNumber(idCell);

            String consultant = limited(ClientListField.CONSULTANT, text(ClientListField.CONSULTANT), 200, false);
            String consultantEmail = email(ClientListField.CONSULTANT_EMAIL, ROW_INVALID_CONSULTANT_EMAIL);
            if (consultantEmail != null && consultant == null) {
                add(Severity.WARNING, ROW_EMAIL_WITHOUT_CONSULTANT, ClientListField.CONSULTANT_EMAIL,
                        "Consultant email without a consultant name; left out.");
                consultantEmail = null;
            }
            String email = email(ClientListField.CLIENT_EMAIL, ROW_INVALID_EMAIL);

            String employer = limited(ClientListField.EMPLOYER, text(ClientListField.EMPLOYER), 200, false);
            PaymentMethod payment = null;
            if (employer != null && normalizeHeader(employer).startsWith("DEBIT ORDER")) {
                payment = PaymentMethod.DEBIT_ORDER;
                employer = null;
            }

            return new Row(rowNumber, firstName, lastName, id, consultant, consultantEmail, email,
                    phone(ClientListField.CELLPHONE), phone(ClientListField.WORK_PHONE),
                    phone(ClientListField.HOME_PHONE), employer, payment,
                    limited(ClientListField.PRODUCT_QUOTATION, text(ClientListField.PRODUCT_QUOTATION), 100, false),
                    fileReference(ClientListField.FILE_NUMBER), fileReference(ClientListField.FILE_ID), rejected);
        }

        private static boolean hasValue(RawCell c) {
            return switch (c.kind()) {
                case TEXT -> !c.text().isBlank();
                case NUMBER -> c.number() != null && c.number().signum() != 0;
                default -> false;
            };
        }

        /**
         * Same rules as the weekly register (WeeklyRegisterParser). Messages give the
         * reason, never the number.
         */
        private SaIdNumber idNumber(RawCell cell) {
            ClientListField f = ClientListField.ID_NUMBER;
            if (cell == null || cell.isBlankText()) {
                add(Severity.ERROR, ROW_MISSING_ID, f, "ID number is empty.");
                return null;
            }
            String digits;
            switch (cell.kind()) {
                case NUMBER -> {
                    BigDecimal n = cell.number().stripTrailingZeros();
                    if (n.signum() < 0 || n.scale() > 0) {
                        add(Severity.ERROR, ROW_INVALID_ID, f, "Not a valid SA ID number: must be exactly 13 digits.");
                        return null;
                    }
                    digits = n.toBigInteger().toString();
                    if (digits.length() == 12) {
                        digits = "0" + digits;
                        add(Severity.WARNING, ROW_ID_LEADING_ZERO, f,
                                "ID number was stored as a number and lost its leading zero; the zero was added "
                                        + "back. Format the column as Text in Excel to avoid this.");
                    }
                }
                case TEXT -> digits = cell.text().replaceAll("[\\s-]", "");
                case ERROR -> {
                    add(Severity.ERROR, ROW_INVALID_ID, f, "ID number shows " + cell.error() + " instead of a number.");
                    return null;
                }
                default -> {
                    add(Severity.ERROR, ROW_INVALID_ID, f, "ID number is not a number.");
                    return null;
                }
            }
            try {
                return SaIdNumber.parse(digits);
            } catch (InvalidIdNumberException e) {
                add(Severity.ERROR, ROW_INVALID_ID, f, "Not a valid SA ID number: " + e.reason().message() + ".");
                return null;
            }
        }

        private String phone(ClientListField field) {
            RawCell cell = cell(field);
            String raw = text(field);
            if (raw == null) {
                return null;
            }
            if (cell.kind() == RawCell.Kind.NUMBER && raw.matches("[1-9]\\d{8}")) {
                // 082 123 4567 typed into a number cell: Excel keeps 821234567.
                raw = "0" + raw;
                add(Severity.INFO, ROW_PHONE_LEADING_ZERO, field,
                        field.label() + " was stored as a number and lost its leading zero; the zero was added back.");
            }
            try {
                return PhoneNumbers.normalise(raw);
            } catch (PhoneNumbers.InvalidPhoneNumberException e) {
                add(Severity.WARNING, ROW_INVALID_PHONE, field,
                        field.label() + " is not a phone number we can store; left out. The client is still imported.");
                return null;
            }
        }

        private String email(ClientListField field, String code) {
            String raw = text(field);
            if (raw == null) {
                return null;
            }
            String email = raw.toLowerCase(Locale.ROOT);
            if (email.length() > 254 || !email.matches(EMAIL)) {
                add(Severity.WARNING, code, field, field.label() + " is not an email address; left out.");
                return null;
            }
            return email;
        }

        private String fileReference(ClientListField field) {
            String raw = text(field);
            if (raw == null) {
                return null;
            }
            if (!raw.matches(FILE_REFERENCE)) {
                add(Severity.WARNING, ROW_INVALID_FILE_REFERENCE, field,
                        field.label() + " must be digits only, at most 20; left out.");
                return null;
            }
            return raw;
        }
    }

    /**
     * The columns in sheet order, for the summary (header -> field label, or null).
     */
    public static Map<String, String> columnSummary(List<Column> columns) {
        Map<String, String> out = new LinkedHashMap<>();
        columns.forEach(c -> out.put(c.header(), c.field() == null ? null : c.field().name()));
        return out;
    }
}
