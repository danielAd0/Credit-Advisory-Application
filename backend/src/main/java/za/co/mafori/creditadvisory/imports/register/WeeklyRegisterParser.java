package za.co.mafori.creditadvisory.imports.register;

import static za.co.mafori.creditadvisory.imports.register.ParseIssue.Severity.ERROR;
import static za.co.mafori.creditadvisory.imports.register.ParseIssue.Severity.INFO;
import static za.co.mafori.creditadvisory.imports.register.ParseIssue.Severity.WARNING;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import za.co.mafori.creditadvisory.common.idnumber.InvalidIdNumberException;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;
import za.co.mafori.creditadvisory.imports.excel.RawCell;
import za.co.mafori.creditadvisory.imports.excel.RawSheet;
import za.co.mafori.creditadvisory.imports.excel.RawWorkbook;

@Component
public class WeeklyRegisterParser {

    private static final Set<String> REPORT_SHEETS = Set.of("REPORT", "BREAKDOWN", "DASHBOARD");

    private static final int HEADER_SEARCH_ROWS = 10;

    private static final int MAX_NAME = 100;
    private static final int MAX_LABEL = 200;
    private static final int MAX_PAYMENT_TERM = 100;
    private static final int MAX_BUDGET_LIST = 500;
    private static final int MAX_EXTRA_TEXT = 500;

    private static final Pattern PLAIN_AMOUNT = Pattern.compile("-?\\d+([.,]\\d{1,2})?");
    private static final Pattern THOUSANDS_AMOUNT = Pattern.compile("-?\\d{1,3}(,\\d{3})+(\\.\\d{1,2})?");
    private static final Pattern THIRTEEN_DIGITS = Pattern.compile("\\d{13}");
    private static final List<DateTimeFormatter> TEXT_DATES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ROOT),
            DateTimeFormatter.ofPattern("d-M-uuuu", Locale.ROOT),
            DateTimeFormatter.ofPattern("d.M.uuuu", Locale.ROOT),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH));
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    public ParseResult parse(RawWorkbook workbook, LocalDate weekStarting, ImportDictionary dictionary) {
        WeekStarting.requireMonday(weekStarting);
        List<ParseIssue> issues = new ArrayList<>();

        Map<DayOfWeek, RawSheet> daySheets = new EnumMap<>(DayOfWeek.class);
        for (RawSheet sheet : workbook.sheets()) {
            var day = dictionary.day(sheet.name());
            if (day.isPresent()) {
                if (daySheets.containsKey(day.get())) {
                    issues.add(new ParseIssue(ERROR, IssueCodes.SHEET_DUPLICATE_DAY, sheet.name(), null, null,
                            "Sheets \"" + daySheets.get(day.get()).name() + "\" and \"" + sheet.name()
                                    + "\" are both " + dayName(day.get()) + ". Keep only one."));
                    continue;
                }
                daySheets.put(day.get(), sheet);
                if (sheet.hidden() && !sheet.isEmpty()) {
                    issues.add(new ParseIssue(WARNING, IssueCodes.SHEET_HIDDEN_DAY, sheet.name(), null, null,
                            "The " + dayName(day.get()) + " sheet is hidden. It was read anyway."));
                }
            } else if (!REPORT_SHEETS.contains(ImportDictionary.normalize(sheet.name())) && !sheet.isEmpty()) {
                issues.add(new ParseIssue(WARNING, IssueCodes.SHEET_UNEXPECTED_DATA, sheet.name(), null, null,
                        "Sheet \"" + sheet.name() + "\"" + (sheet.hidden() ? " (hidden)" : "")
                                + " has data but is not a day sheet. It was not imported."));
            }
        }

        List<DaySheetResult> days = new ArrayList<>();
        List<ParsedRow> rows = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            LocalDate date = weekStarting.plusDays(day.getValue() - 1L);
            RawSheet sheet = daySheets.get(day);
            if (sheet == null) {
                issues.add(new ParseIssue(WARNING, IssueCodes.SHEET_MISSING_DAY, null, null, null,
                        "There is no " + dayName(day) + " sheet. " + dayName(day)
                                + " is imported as having no sales."));
                continue;
            }
            new DayParser(sheet, day, date, dictionary, issues, rows, days).parse();
        }

        markRepeatClients(rows);
        return new ParseResult(weekStarting, days, rows, collect(issues, rows));
    }

    /** File/sheet issues first, then every row's issues, in sheet and row order. */
    private static List<ParseIssue> collect(List<ParseIssue> sheetIssues, List<ParsedRow> rows) {
        List<ParseIssue> all = new ArrayList<>(sheetIssues);
        rows.forEach(r -> all.addAll(r.issues()));
        return all;
    }

    private static void markRepeatClients(List<ParsedRow> rows) {
        Map<String, ParsedRow> first = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            ParsedRow row = rows.get(i);
            if (row.idNumber() == null) {
                continue;
            }
            ParsedRow earlier = first.putIfAbsent(row.idNumber().value(), row);
            if (earlier != null) {
                List<ParseIssue> issues = new ArrayList<>(row.issues());
                issues.add(new ParseIssue(INFO, IssueCodes.ROW_REPEAT_CLIENT, row.sheetName(), row.rowNumber(),
                        "ID NUMBER", "Same client as " + earlier.sheetName() + " row " + earlier.rowNumber()
                                + ": imported as another transaction for that client."));
                rows.set(i, withIssues(row, issues));
            }
        }
    }

    private static ParsedRow withIssues(ParsedRow r, List<ParseIssue> issues) {
        return new ParsedRow(r.sheetName(), r.day(), r.rowNumber(), r.transactionDate(), r.firstName(),
                r.lastName(), r.idNumber(), r.consultantName(), r.employerName(), r.structureName(),
                r.paymentTermName(), r.quotation(), r.banked(), r.quotationByRule(), r.bankedByRule(),
                r.amountToClient(), r.thirdPartySettlement(), r.primaryBudgetList(), r.primaryLoanAmount(),
                r.secondaryBudgetList(), r.secondaryLoanAmount(), r.productAmounts(), r.extras(), issues);
    }

    static String dayName(DayOfWeek day) {
        String n = day.name();
        return n.charAt(0) + n.substring(1).toLowerCase(Locale.ROOT);
    }

    static String columnLetter(int column) {
        StringBuilder sb = new StringBuilder();
        int c = column + 1;
        while (c > 0) {
            int m = (c - 1) % 26;
            sb.insert(0, (char) ('A' + m));
            c = (c - 1) / 26;
        }
        return sb.toString();
    }

    private static final class DayParser {

        private final RawSheet sheet;
        private final DayOfWeek day;
        private final LocalDate date;
        private final ImportDictionary dictionary;
        private final List<ParseIssue> sheetIssues;
        private final List<ParsedRow> out;
        private final List<DaySheetResult> days;

        private final Map<RegisterField, Integer> fieldColumns = new EnumMap<>(RegisterField.class);
        private final Map<Integer, ImportDictionary.Product> productColumns = new TreeMap<>();
        private final Map<Integer, String> columnLabels = new TreeMap<>();
        private final Map<String, String> mapping = new LinkedHashMap<>();
        private final Map<Integer, Integer> unknownColumnRows = new TreeMap<>();

        DayParser(RawSheet sheet, DayOfWeek day, LocalDate date, ImportDictionary dictionary,
                List<ParseIssue> sheetIssues, List<ParsedRow> out, List<DaySheetResult> days) {
            this.sheet = sheet;
            this.day = day;
            this.date = date;
            this.dictionary = dictionary;
            this.sheetIssues = sheetIssues;
            this.out = out;
            this.days = days;
        }

        void parse() {
            Integer headerRow = findHeaderRow();
            if (headerRow == null) {
                if (sheet.isEmpty()) {
                    sheetIssues.add(sheetIssue(INFO, IssueCodes.SHEET_EMPTY, null, "The sheet is empty."));
                } else {
                    sheetIssues.add(sheetIssue(ERROR, IssueCodes.SHEET_NO_HEADER_ROW, null,
                            "No header row with NAME, SURNAME and ID NUMBER was found in the first "
                                    + HEADER_SEARCH_ROWS + " rows."));
                }
                days.add(new DaySheetResult(sheet.name(), day, date, null, Map.of(), 0, 0));
                return;
            }
            if (!mapHeaders(headerRow)) {
                days.add(new DaySheetResult(sheet.name(), day, date, headerRow + 1, mapping, 0, 0));
                return;
            }

            List<Integer> dataRows = new ArrayList<>();
            for (var entry : sheet.rows().tailMap(headerRow, false).entrySet()) {
                if (isDataRow(entry.getValue())) {
                    dataRows.add(entry.getKey());
                }
            }
            if (dataRows.isEmpty()) {
                sheetIssues.add(sheetIssue(INFO, IssueCodes.SHEET_EMPTY, null, "No client rows."));
            }
            if (!dataRows.isEmpty() && !checkRequiredColumns()) {
                days.add(new DaySheetResult(sheet.name(), day, date, headerRow + 1, mapping, dataRows.size(),
                        dataRows.size()));
                return;
            }

            int before = out.size();
            for (int r : dataRows) {
                out.add(parseRow(r, sheet.rows().get(r)));
            }
            List<ParsedRow> mine = out.subList(before, out.size());
            reportUnknownColumns();
            reportRuleDifferences(mine);
            int rejected = (int) mine.stream().filter(ParsedRow::rejected).count();
            days.add(new DaySheetResult(sheet.name(), day, date, headerRow + 1, mapping, mine.size(), rejected));
        }

        // ---------- header ----------

        /** The first of the first rows with NAME, SURNAME and ID NUMBER headers. */
        private Integer findHeaderRow() {
            for (var entry : sheet.rows().headMap(HEADER_SEARCH_ROWS, false).entrySet()) {
                Set<RegisterField> found = java.util.EnumSet.noneOf(RegisterField.class);
                for (RawCell cell : entry.getValue().values()) {
                    if (cell.kind() == RawCell.Kind.TEXT) {
                        dictionary.field(cell.text()).ifPresent(found::add);
                    }
                }
                if (found.containsAll(Set.of(RegisterField.NAME, RegisterField.SURNAME, RegisterField.ID_NUMBER))) {
                    return entry.getKey();
                }
            }
            return null;
        }

        /**
         * @return false if the header has a duplicate field column (ambiguous: the
         *         sheet is not read)
         */
        private boolean mapHeaders(int headerRow) {
            boolean ok = true;
            for (var entry : sheet.rows().get(headerRow).entrySet()) {
                int column = entry.getKey();
                RawCell cell = entry.getValue();
                String label = cell.kind() == RawCell.Kind.TEXT ? cell.text().strip()
                        : cell.kind() == RawCell.Kind.NUMBER ? cell.number().stripTrailingZeros().toPlainString()
                                : null;
                if (label == null || label.isEmpty()) {
                    continue;
                }
                columnLabels.put(column, label);
                var field = dictionary.field(label);
                if (field.isPresent()) {
                    Integer existing = fieldColumns.putIfAbsent(field.get(), column);
                    if (existing != null) {
                        sheetIssues.add(sheetIssue(ERROR, IssueCodes.SHEET_DUPLICATE_COLUMN, label,
                                "Columns " + columnLetter(existing) + " and " + columnLetter(column) + " are both \""
                                        + label + "\". Remove or rename one."));
                        ok = false;
                    }
                    mapping.put(columnLetter(column), "FIELD:" + field.get());
                    continue;
                }
                var product = dictionary.product(label);
                if (product.isPresent()) {
                    productColumns.put(column, product.get());
                    mapping.put(columnLetter(column), "PRODUCT:" + product.get().code());
                    if (!product.get().active()) {
                        sheetIssues.add(sheetIssue(WARNING, IssueCodes.SHEET_INACTIVE_PRODUCT, label,
                                "Column " + columnLetter(column) + " is product " + product.get().code()
                                        + ", which is deactivated. Its amounts are imported anyway."));
                    }
                }
                // Anything else is an unknown column: kept as extras if it has data
                // (reportUnknownColumns).
            }
            return ok;
        }

        private boolean checkRequiredColumns() {
            List<String> missing = RegisterField.REQUIRED.stream()
                    .filter(f -> !fieldColumns.containsKey(f))
                    .map(f -> f.name().replace('_', ' '))
                    .toList();
            if (missing.isEmpty()) {
                return true;
            }
            sheetIssues.add(sheetIssue(ERROR, IssueCodes.SHEET_MISSING_HEADER, String.join(", ", missing),
                    "The sheet has client rows but no column for: " + String.join(", ", missing) + "."));
            return false;
        }

        private boolean isDataRow(Map<Integer, RawCell> cells) {
            for (RegisterField f : List.of(RegisterField.NAME, RegisterField.SURNAME, RegisterField.ID_NUMBER)) {
                Integer column = fieldColumns.get(f);
                RawCell cell = column == null ? null : cells.get(column);
                if (cell != null && !cell.isBlankText()
                        && !(cell.kind() == RawCell.Kind.NUMBER && cell.number().signum() == 0)) {
                    return true;
                }
            }
            return false;
        }

        // ---------- one row ----------

        private ParsedRow parseRow(int rowIndex, Map<Integer, RawCell> cells) {
            int rowNumber = rowIndex + 1;
            RowIssues issues = new RowIssues(sheet.name(), rowNumber);

            String first = name(cells, RegisterField.NAME, "NAME", issues);
            String last = name(cells, RegisterField.SURNAME, "SURNAME", issues);
            SaIdNumber id = idNumber(cell(cells, RegisterField.ID_NUMBER), issues);
            checkDate(cell(cells, RegisterField.DATE), issues);

            String consultant = label(cells, RegisterField.CONSULTANT, MAX_LABEL, issues);
            if (consultant == null) {
                issues.add(WARNING, IssueCodes.ROW_NO_CONSULTANT, "CONSULTANT",
                        "No consultant: imported as an unassigned client and transaction.");
            }
            String employer = label(cells, RegisterField.EMPLOYER, MAX_LABEL, issues);
            String structure = label(cells, RegisterField.STRUCTURE, MAX_LABEL, issues);
            String paymentTerm = label(cells, RegisterField.PAYMENT_TERM, MAX_PAYMENT_TERM, issues);
            String primaryBudget = label(cells, RegisterField.PRIMARY_BUDGET_LIST, MAX_BUDGET_LIST, issues);
            String secondaryBudget = label(cells, RegisterField.SECONDARY_BUDGET_LIST, MAX_BUDGET_LIST, issues);

            BigDecimal quotation = amount(cells, RegisterField.QUOTATION, true, issues);
            BigDecimal banked = amount(cells, RegisterField.BANKED, true, issues);
            BigDecimal amountToClient = amount(cells, RegisterField.AMOUNT_TO_CLIENT, false, issues);
            BigDecimal thirdParty = amount(cells, RegisterField.THIRD_PARTY_SETTLEMENT, false, issues);
            BigDecimal primaryLoan = amount(cells, RegisterField.PRIMARY_LOAN_AMOUNT, false, issues);
            BigDecimal secondaryLoan = amount(cells, RegisterField.SECONDARY_LOAN_AMOUNT, false, issues);

            Map<String, BigDecimal> products = new LinkedHashMap<>();
            BigDecimal byRuleQ = BigDecimal.ZERO.setScale(2);
            BigDecimal byRuleB = BigDecimal.ZERO.setScale(2);
            for (var pc : productColumns.entrySet()) {
                String header = columnLabels.get(pc.getKey());
                BigDecimal value = amount(cells.get(pc.getKey()), header, true, issues);
                if (value == null || value.signum() == 0) {
                    continue;
                }
                ImportDictionary.Product p = pc.getValue();
                if (products.containsKey(p.code())) {
                    issues.add(WARNING, IssueCodes.ROW_DUPLICATE_PRODUCT_COLUMN, header,
                            "Two " + header + " columns both have an amount on this row; they were added together.");
                }
                products.merge(p.code(), value, BigDecimal::add);
                if (p.countsTowardQuotation()) {
                    byRuleQ = byRuleQ.add(value);
                }
                if (p.countsTowardBanked()) {
                    byRuleB = byRuleB.add(value);
                }
            }

            Map<String, Object> extras = extras(cells, issues);

            return new ParsedRow(sheet.name(), day, rowNumber, date, first, last, id, consultant, employer,
                    structure, paymentTerm, quotation, banked, byRuleQ, byRuleB, amountToClient, thirdParty,
                    primaryBudget, primaryLoan, secondaryBudget, secondaryLoan, products, extras, issues.list);
        }

        private RawCell cell(Map<Integer, RawCell> cells, RegisterField field) {
            Integer column = fieldColumns.get(field);
            return column == null ? null : cells.get(column);
        }

        private String name(Map<Integer, RawCell> cells, RegisterField field, String header, RowIssues issues) {
            String value = text(cell(cells, field), header, issues);
            if (value == null) {
                issues.add(ERROR, IssueCodes.ROW_MISSING_NAME, header, header + " is empty.");
                return null;
            }
            if (value.length() > MAX_NAME) {
                issues.add(ERROR, IssueCodes.ROW_VALUE_TOO_LONG, header,
                        header + " is longer than " + MAX_NAME + " characters.");
                return null;
            }
            return value;
        }

        private String label(Map<Integer, RawCell> cells, RegisterField field, int max, RowIssues issues) {
            String header = field.name().replace('_', ' ');
            String value = text(cell(cells, field), header, issues);
            if (value != null && value.length() > max) {
                issues.add(ERROR, IssueCodes.ROW_VALUE_TOO_LONG, header,
                        header + " is longer than " + max + " characters.");
                return null;
            }
            return value;
        }

        /** Trimmed, single-spaced text; numbers become text; null when empty. */
        private String text(RawCell cell, String header, RowIssues issues) {
            if (cell == null) {
                return null;
            }
            switch (cell.kind()) {
                case TEXT:
                    return cell.isBlankText() ? null : cell.text().strip().replaceAll("\\s+", " ");
                case NUMBER:
                    return cell.number().stripTrailingZeros().toPlainString();
                case BOOLEAN:
                    return cell.bool() ? "TRUE" : "FALSE";
                default:
                    issues.add(WARNING, IssueCodes.ROW_FORMULA_ERROR_IGNORED, header,
                            header + " shows " + cell.error() + "; treated as empty.");
                    return null;
            }
        }

        /** Design 4.2. Messages give the reason, never the number. */
        private SaIdNumber idNumber(RawCell cell, RowIssues issues) {
            if (cell == null || cell.isBlankText()) {
                issues.add(ERROR, IssueCodes.ROW_MISSING_ID, "ID NUMBER", "ID NUMBER is empty.");
                return null;
            }
            String digits;
            switch (cell.kind()) {
                case NUMBER -> {
                    BigDecimal n = cell.number().stripTrailingZeros();
                    if (n.signum() < 0 || n.scale() > 0) {
                        issues.add(ERROR, IssueCodes.ROW_INVALID_ID, "ID NUMBER",
                                "Not a valid SA ID number: must be exactly 13 digits.");
                        return null;
                    }
                    digits = n.toBigInteger().toString();
                    if (digits.length() == 12) {
                        // Excel drops the leading zero of a number (people born 2000 - 2009).
                        digits = "0" + digits;
                        issues.add(WARNING, IssueCodes.ROW_ID_LEADING_ZERO, "ID NUMBER",
                                "ID NUMBER was stored as a number and lost its leading zero; the zero was added back. "
                                        + "Format the column as Text in Excel to avoid this.");
                    }
                }
                case TEXT -> digits = cell.text().replaceAll("[\\s-]", "");
                case ERROR -> {
                    issues.add(ERROR, IssueCodes.ROW_INVALID_ID, "ID NUMBER",
                            "ID NUMBER shows " + cell.error() + " instead of a number.");
                    return null;
                }
                default -> {
                    issues.add(ERROR, IssueCodes.ROW_INVALID_ID, "ID NUMBER", "ID NUMBER is not a number.");
                    return null;
                }
            }
            try {
                return SaIdNumber.parse(digits);
            } catch (InvalidIdNumberException e) {
                issues.add(ERROR, IssueCodes.ROW_INVALID_ID, "ID NUMBER",
                        "Not a valid SA ID number: " + e.reason().message() + ".");
                return null;
            }
        }

        private void checkDate(RawCell cell, RowIssues issues) {
            if (cell == null || cell.isBlankText()) {
                return;
            }
            LocalDate value = null;
            if (cell.kind() == RawCell.Kind.NUMBER) {
                value = cell.date() != null ? cell.date()
                        : EXCEL_EPOCH.plusDays(cell.number().setScale(0, RoundingMode.FLOOR).longValue());
            } else if (cell.kind() == RawCell.Kind.TEXT) {
                for (DateTimeFormatter f : TEXT_DATES) {
                    try {
                        value = LocalDate.parse(cell.text().strip(), f);
                        break;
                    } catch (DateTimeParseException e) {
                        // try the next format
                    }
                }
            }
            if (value == null) {
                issues.add(ERROR, IssueCodes.ROW_INVALID_DATE, "DATE", "DATE is not a date.");
            } else if (!value.equals(date)) {
                issues.add(ERROR, IssueCodes.ROW_DATE_OUTSIDE_DAY, "DATE",
                        "DATE is " + value + " but this is the " + dayName(day) + " sheet (" + date + ").");
            }
        }

        private BigDecimal amount(Map<Integer, RawCell> cells, RegisterField field, boolean essential,
                RowIssues issues) {
            Integer column = fieldColumns.get(field);
            if (column == null) {
                return null;
            }
            return amount(cells.get(column), columnLabels.get(column), essential, issues);
        }

        private BigDecimal amount(RawCell cell, String header, boolean essential, RowIssues issues) {
            if (cell == null || cell.isBlankText()) {
                return null;
            }
            BigDecimal value;
            switch (cell.kind()) {
                case NUMBER -> value = cell.number();
                case TEXT -> {
                    value = parseTextAmount(cell.text());
                    if (value == null) {
                        issues.add(ERROR, IssueCodes.ROW_INVALID_AMOUNT, header, header + " is not an amount.");
                        return null;
                    }
                    if (value.signum() != 0) {
                        issues.add(WARNING, IssueCodes.ROW_AMOUNT_WAS_TEXT, header,
                                header + " was typed as text; read as " + value.setScale(2, RoundingMode.HALF_UP)
                                        + ".");
                    }
                }
                case ERROR -> {
                    if (essential) {
                        issues.add(ERROR, IssueCodes.ROW_FORMULA_ERROR, header,
                                header + " shows " + cell.error() + " instead of an amount.");
                    } else {
                        issues.add(WARNING, IssueCodes.ROW_FORMULA_ERROR_IGNORED, header,
                                header + " shows " + cell.error() + "; treated as empty.");
                    }
                    return null;
                }
                default -> {
                    issues.add(ERROR, IssueCodes.ROW_INVALID_AMOUNT, header, header + " is not an amount.");
                    return null;
                }
            }
            if (value.signum() < 0) {
                issues.add(ERROR, IssueCodes.ROW_NEGATIVE_AMOUNT, header, header + " is negative.");
                return null;
            }
            return value.setScale(2, RoundingMode.HALF_UP);
        }

        static BigDecimal parseTextAmount(String raw) {
            String s = raw.strip().replaceFirst("^[Rr]\\s*", "").replace(" ", "").replace(" ", "");
            if (s.equals("-") || s.isEmpty()) {
                return BigDecimal.ZERO;
            }
            try {
                if (THOUSANDS_AMOUNT.matcher(s).matches()) {
                    return new BigDecimal(s.replace(",", ""));
                }
                if (PLAIN_AMOUNT.matcher(s).matches()) {
                    return new BigDecimal(s.replace(',', '.'));
                }
            } catch (NumberFormatException e) {
                return null;
            }
            return null;
        }

        /** Design 4.7: every non-empty cell of a column the import does not know. */
        private Map<String, Object> extras(Map<Integer, RawCell> cells, RowIssues issues) {
            Map<String, Object> extras = new LinkedHashMap<>();
            for (var entry : cells.entrySet()) {
                int column = entry.getKey();
                if (fieldColumns.containsValue(column) || productColumns.containsKey(column)) {
                    continue;
                }
                RawCell cell = entry.getValue();
                Object value = switch (cell.kind()) {
                    case TEXT -> cell.isBlankText() ? null : cell.text().strip();
                    case NUMBER -> cell.number().signum() == 0 ? null : cell.number();
                    case BOOLEAN -> cell.bool() ? "TRUE" : "FALSE";
                    case ERROR -> cell.error();
                };
                if (value == null) {
                    continue;
                }
                String label = columnLabels.getOrDefault(column, "Column " + columnLetter(column));
                if (value instanceof String s) {
                    if (THIRTEEN_DIGITS.matcher(s).find()) {
                        s = THIRTEEN_DIGITS.matcher(s).replaceAll("[redacted]");
                        issues.add(WARNING, IssueCodes.ROW_EXTRA_LOOKS_LIKE_ID, label,
                                label + " contains what looks like an ID number; it was not kept.");
                    }
                    if (s.length() > MAX_EXTRA_TEXT) {
                        s = s.substring(0, MAX_EXTRA_TEXT);
                    }
                    value = s;
                }
                extras.put(label, value);
                unknownColumnRows.merge(column, 1, Integer::sum);
                mapping.putIfAbsent(columnLetter(column), "EXTRA:" + label);
            }
            return extras;
        }

        // ---------- sheet-level summaries ----------

        private void reportUnknownColumns() {
            for (var e : unknownColumnRows.entrySet()) {
                String label = columnLabels.getOrDefault(e.getKey(), "(no header)");
                sheetIssues.add(sheetIssue(WARNING, IssueCodes.SHEET_UNKNOWN_COLUMN, label,
                        "Column " + columnLetter(e.getKey()) + " \"" + label + "\" is not a known field. Its values on "
                                + e.getValue() + " row(s) were kept as extra data; an admin can map it to a product."));
            }
        }

        /**
         * Design 2.2 / D3: one warning per day when the sheet's own formula differs
         * from the product rule.
         */
        private void reportRuleDifferences(List<ParsedRow> rows) {
            long q = rows.stream().filter(ParsedRow::quotationDiffersFromRule).count();
            long b = rows.stream().filter(ParsedRow::bankedDiffersFromRule).count();
            if (q > 0) {
                sheetIssues.add(sheetIssue(WARNING, IssueCodes.SHEET_QUOTATION_RULE_DIFFERS, "QUOTATION",
                        "On " + q + " of " + rows.size() + " rows, QUOTATION differs from the standard product rule. "
                                + "The sheet's own values were kept."));
            }
            if (b > 0) {
                sheetIssues.add(sheetIssue(WARNING, IssueCodes.SHEET_BANKED_RULE_DIFFERS, "BANKED",
                        "On " + b + " of " + rows.size() + " rows, BANKED differs from the standard product rule. "
                                + "The sheet's own values were kept."));
            }
        }

        private ParseIssue sheetIssue(ParseIssue.Severity severity, String code, String field, String message) {
            return new ParseIssue(severity, code, sheet.name(), null, field, message);
        }
    }

    /** Collects one row's issues. */
    private static final class RowIssues {
        private final String sheet;
        private final int row;
        private final List<ParseIssue> list = new ArrayList<>();

        RowIssues(String sheet, int row) {
            this.sheet = sheet;
            this.row = row;
        }

        void add(ParseIssue.Severity severity, String code, String field, String message) {
            list.add(new ParseIssue(severity, code, sheet, row, field, message));
        }
    }

}
