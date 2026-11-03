package za.co.mafori.creditadvisory.imports.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;


public final class FakeWeeklyRegisterWorkbook {

    public static final List<String> DAYS = List.of(
            "Monday", "Tuesday", "Wednsday", "Thursday", "Friday", "Saturday", "Sunday");

    /** Data rows per day. Saturday is empty like the real file; Sunday has rows so its defect shows. */
    public static final int[] ROWS = { 12, 10, 8, 6, 7, 0, 3 };

    public static final int FORMULA_ONLY_ROWS = 40;

    public static final List<String> CONSULTANTS = List.of(
            "TEST CONSULTANT ONE", "TEST CONSULTANT TWO", "TEST CONSULTANT THREE", "TEST CONSULTANT FOUR",
            "TESTLABEL");

    /** Appears once (Thursday, 3rd row) and is in no consultant list: must block confirm until mapped. */
    public static final String UNMAPPED_CONSULTANT = "UNMAPPED PERSON";

    private static final List<String> FIRST = List.of(
            "ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL", "INDIA", "JULIET", "KILO",
            "LIMA");

    private static final List<String> EMPLOYERS = List.of(
            "AFRIT", "BLOEM WATER (SOC) LTD", "BLOEM WATER", "TEST LOCAL MUNICIPALITY", "  TEST LOCAL  MUNICIPALITY ");

    private static final List<String> SHEET_ORDER = List.of(
            "Sheet2", "Sheet3", "Sheet4", "Sheet1", "Monday", "Tuesday", "Sheet10", "Sheet8", "Wednsday",
            "Thursday", "Sunday", "Friday", "Saturday", "Report", "Breakdown", "Dashboard");

    // ---------- layout copied from the real workbook ----------

    private static final List<String> MON_TUE = Arrays.asList(
            "DATE", "NAME", "SURNAME", "ID NUMBER ", "QUOTATION", "COMBO", "IVC", "INSURANCE", "MAFORI CARD",
            "MAFORI VOUCHER", "CREDIT RESCUE", "CREDIT REPAIR", "CREDIT REPAIR DEBIT ORDER", "SPEED SERVICE",
            "CASH ADVANCE LITE", "GROCERY", "INSURANCE", "HOUSING ADMIN", "MAFORI WELLNESS",
            "VOLUTARY DISTRIBUTION", "AMOUNT TO CLIENT", null, "3RD PARTY SETTLEMENT", "BANKED", "PAYMENT TERM",
            "EMPLOYER", "STRUCTURE", "CONSULTANT", "PRIMARY STRUCTURE BUDGET LIST", "PRIMARY STRUCTURE LOAN AMOUNT",
            "SECONDARY STRUCTURE BUDGET LIST", "SECONDARY STRUCTURE LOAN AMOUNT ");

    private static final List<String> WED_FRI = Arrays.asList(
            "DATE", "NAME", "SURNAME", "ID NUMBER ", "QUOTATION", "COMBO", "IVC", "INSURANCE", "MAFORI CARD",
            "MAFORI VOUCHER", "CREDIT RESCUE", "CREDIT REPAIR", "CREDIT REPAIR DEBIT ORDER", "SPEED SERVICE",
            "CASH ADVANCE LITE", "GROCERY", "HOUSING ADMIN", "MAFORI WELLNESS", "VOLUTARY DISTRIBUTION",
            "AMOUNT TO CLIENT", "3RD PARTY SETTLEMENT", "BANKED", "PAYMENT TERM", "EMPLOYER", "STRUCTURE",
            "CONSULTANT", "PRIMARY STRUCTURE BUDGET LIST", "PRIMARY STRUCTURE LOAN AMOUNT",
            "SECONDARY STRUCTURE BUDGET LIST", "SECONDARY STRUCTURE LOAN AMOUNT ");

    private static final List<String> THU = Arrays.asList(
            "DATE", "NAME", "SURNAME", "ID NUMBER ", "QUOTATION", "COMBO", "IVC", "INSURANCE", "MAFORI CARD",
            "MAFORI VOUCHER", "CREDIT RESCUE", "CREDIT REPAIR", "CREDIT REPAIR DEBIT ORDER", "SPEED SERVICE",
            "CASH ADVANCE LITE", "GROCERY", "MAFORI WELLNESS", "VOLUTARY DISTRIBUTION", "AMOUNT TO CLIENT",
            "3RD PARTY SETTLEMENT", "BANKED", "PAYMENT TERM", "EMPLOYER", "STRUCTURE", "CONSULTANT",
            "PRIMARY STRUCTURE BUDGET LIST", "PRIMARY STRUCTURE LOAN AMOUNT", "SECONDARY STRUCTURE BUDGET LIST",
            "SECONDARY STRUCTURE LOAN AMOUNT ");

    private static final List<String> SAT = Arrays.asList(
            "DATE", "NAME", "SURNAME", "ID NUMBER ", "QUOTATION", "COMBO", "IVC", "MAFORI VOUCHER", "CREDIT RESCUE",
            "CREDIT REPAIR", "CREDIT REPAIR DEBIT ORDER", "SPEED SERVICE", null, "GROECRY", "CASH ADVANCE LITE",
            "HOUSING ADMIN", "MAFORI WELLNESS", "AMOUNT TO CLIENT", "3RD PARTY SETTLEMENT", "BANKED",
            "PAYMENT TERM", "EMPLOYER", "STRUCTURE", "CONSULTANT", "PRIMARY STRUCTURE BUDGET LIST",
            "PRIMARY STRUCTURE LOAN AMOUNT", "SECONDARY STRUCTURE BUDGET LIST", "SECONDARY STRUCTURE LOAN AMOUNT ");

    private static final List<String> SUN = Arrays.asList(
            "DATE", "NAME", "SURNAME", "ID NUMBER ", "QUOTATION", "COMBO", "IVC", "MAFORI VOUCHER", "CREDIT RESCUE",
            "CREDIT REPAIR", "CREDIT REPAIR DEBIT ORDER", "SPEED SERVICE", null, "GROECRY", "HOUSING ADMIN",
            "POLICY REPLACEMENT", "AMOUNT TO CLIENT", "3RD PARTY SETTLEMENT", "BANKED", "PAYMENT TERM", "EMPLOYER",
            "STRUCTURE", "CONSULTANT", "PRIMARY STRUCTURE BUDGET LIST", "PRIMARY STRUCTURE LOAN AMOUNT",
            "SECONDARY STRUCTURE BUDGET LIST", "SECONDARY STRUCTURE LOAN AMOUNT ");

    private static final Map<String, List<String>> HEADERS = Map.of(
            "Monday", MON_TUE, "Tuesday", MON_TUE, "Wednsday", WED_FRI, "Thursday", THU, "Friday", WED_FRI,
            "Saturday", SAT, "Sunday", SUN);

    /** Day -> (0-based column -> formula template without "="; {r} = row number). */
    private static final Map<String, Map<Integer, String>> FORMULAS = Map.of(
            "Monday", Map.of(4, "SUM(F{r}:N{r})+R{r}+Q{r}+S{r}+T{r}",
                    23, "F{r}+G{r}+J{r}+K{r}+N{r}+Q{r}+R{r}+S{r}+T{r}+H{r}+I{r}"),
            "Tuesday", Map.of(4, "SUM(F{r}:N{r})+R{r}+Q{r}+S{r}+T{r}",
                    23, "F{r}+G{r}+J{r}+K{r}+N{r}+Q{r}+R{r}+S{r}+T{r}+H{r}+I{r}"),
            "Wednsday", Map.of(4, "SUM(F{r}:N{r})+Q{r}+O{r}+S{r}+R{r}",
                    21, "F{r}+G{r}+J{r}+K{r}+N{r}+O{r}+Q{r}+S{r}+R{r}+H{r}+I{r}"),
            "Thursday", Map.of(4, "SUM(F{r}:N{r})+Q{r}+O{r}+S{r}+R{r}",
                    20, "F{r}+G{r}+J{r}+K{r}+N{r}+O{r}+Q{r}+S{r}+R{r}+H{r}+I{r}"),
            "Friday", Map.of(4, "SUM(F{r}:P{r})+S{r}+Q{r}+U{r}+T{r}",
                    21, "F{r}+G{r}+J{r}+K{r}+N{r}+O{r}+Q{r}+S{r}+R{r}+H{r}"),
            "Saturday", Map.of(4, "SUM(F{r}:L{r})+P{r}+O{r}+S{r}+Q{r}", 19, "F{r}+G{r}+H{r}+I{r}+L{r}+O{r}+P{r}",
                    25, "L{r}+M{r}+N{r}+O{r}+R{r}+U{r}+V{r}", 27, "N{r}+O{r}+P{r}+Q{r}+T{r}+W{r}+X{r}"),
            "Sunday", Map.of(18, "F{r}+G{r}+H{r}+I{r}+L{r}+M{r}+O{r}+P{r}",
                    24, "L{r}+M{r}+N{r}+O{r}+R{r}+S{r}+U{r}+V{r}", 26, "N{r}+O{r}+P{r}+Q{r}+T{r}+U{r}+W{r}+X{r}"));

    private static final Map<String, String> BANKED_COL = Map.of(
            "Monday", "X", "Tuesday", "X", "Wednsday", "V", "Thursday", "U", "Friday", "V", "Saturday", "T",
            "Sunday", "S");

    private static final Map<String, String> CONSULTANT_COL = Map.of(
            "Monday", "AB", "Tuesday", "AB", "Wednsday", "Z", "Thursday", "Y", "Friday", "Z", "Saturday", "X",
            "Sunday", "W");

    private static final Map<String, String> CREDIT_RESCUE_COL = Map.of(
            "Monday", "K", "Tuesday", "K", "Wednsday", "K", "Thursday", "K", "Friday", "K", "Saturday", "I",
            "Sunday", "I");

    private static final Pattern RANGE = Pattern.compile("SUM\\(([A-Z]+)\\{r}:([A-Z]+)\\{r}\\)");

    private FakeWeeklyRegisterWorkbook() {
    }

    // ---------- fake data rules (identical to tools/fake_register.py) ----------

    /** One fake data row. Values keyed by trimmed header; products separately. */
    public record FakeRow(String day, int dayIndex, int rowIndex, String firstName, String surname, String idNumber,
            boolean idStoredAsText, String consultant, String employer, String structure, String paymentTerm,
            Map<String, Integer> products, Integer secondInsurance, String noHeaderValue, Integer amountToClient,
            Integer thirdPartySettlement, String primaryBudgetList, int primaryLoanAmount) {

        /** What Excel shows in the ID cell: a number drops a leading zero. */
        public Object idCellValue() {
            return idStoredAsText ? idNumber : (Object) Long.parseLong(idNumber);
        }
    }

    public static FakeRow row(int d, int i) {
        String day = DAYS.get(d);
        int k = Arrays.stream(ROWS, 0, d).sum() + i;
        if (d == 1 && i == 0) {
            k = 0; // the same person on Monday and Tuesday
        }
        int yy = k == 5 ? 5 : 60 + k % 35; // k == 5 was born in 2005: the ID starts with 0
        String d12 = String.format("%02d%02d%02d%04d08", yy, 1 + k % 12, 1 + k % 28, 5000 + k);
        String id = d12 + luhnDigit(d12);
        boolean asText = k % 3 == 2 && k != 5;

        String consultant = CONSULTANTS.get((i + d) % 5);
        if (d == 1 && (i == 1 || i == 4 || i == 7)) {
            consultant = null; // D2: no consultant
        }
        if (d == 3 && i == 2) {
            consultant = UNMAPPED_CONSULTANT;
        }

        Map<String, Integer> p = new LinkedHashMap<>();
        if (i % 5 != 4) {
            p.put("COMBO", i % 4 != 3 ? 1500 : 1275);
        }
        if (i % 3 != 2) {
            p.put("INSURANCE", 225);
        }
        if (i % 2 == 0) {
            p.put("MAFORI CARD", 175);
        }
        if (i % 5 == 1) {
            p.put("CREDIT RESCUE", 2875);
        }
        if (i % 5 == 3) {
            p.put("CREDIT RESCUE", 1150);
        }
        if (i % 4 == 2) {
            p.put("CREDIT REPAIR DEBIT ORDER", 2875);
        }
        if (i % 6 == 4) {
            p.put("CREDIT REPAIR", 575);
        }
        if (d == 4 && i == 0) {
            p.put("HOUSING ADMIN", 1500);
        }
        if (d == 2 && i == 1) {
            p.put("GROCERY", 300);
        }
        if (d == 6 && i == 1) {
            p.put("GROECRY", 300);
        }
        if (d == 3 && i == 1) {
            p.put("CASH ADVANCE LITE", 500);
        }
        if (d == 6 && i == 2) {
            p.put("POLICY REPLACEMENT", 900);
        }

        return new FakeRow(day, d, i,
                FIRST.get(k % 12) + (k % 5 == 0 ? " " : ""),
                (k % 7 == 3 ? " " : "") + "TESTPERSON" + k,
                id, asText, consultant,
                i % 6 == 5 ? null : EMPLOYERS.get((i + 2 * d) % 5),
                i % 2 == 0 ? "TEST STRUCTURE" : null,
                i % 7 == 6 ? null : "ONCE OFF PAYMENT",
                p,
                d == 0 && i == 3 ? 225 : null,
                d == 0 && i == 0 ? "SEE NOTE" : null,
                d == 3 && i == 3 ? 1000 : null,
                d == 4 && i == 2 ? 250 : null,
                d == 0 && i == 1 ? "Mafori Housing" : null,
                d == 0 && i == 1 ? 500 : 0);
    }

    public static List<FakeRow> allRows() {
        List<FakeRow> rows = new ArrayList<>();
        for (int d = 0; d < DAYS.size(); d++) {
            for (int i = 0; i < ROWS[d]; i++) {
                rows.add(row(d, i));
            }
        }
        return rows;
    }

    /** Luhn check digit for the first 12 digits of an SA ID number. */
    static String luhnDigit(String d12) {
        int sum = 0;
        for (int i = 0; i < d12.length(); i++) {
            int x = d12.charAt(d12.length() - 1 - i) - '0';
            if (i % 2 == 0) {
                x *= 2;
                if (x > 9) {
                    x -= 9;
                }
            }
            sum += x;
        }
        return String.valueOf((10 - sum % 10) % 10);
    }

    // ---------- what the workbook shows (cached values) ----------

    /**
     * Figures as the workbook itself shows them, and the true figures. Tests assert the
     * import stores the sheet values AND recalculates the true ones.
     */
    public record Expected(Map<String, Long> rows, Map<String, Long> sheetQuotation, Map<String, Long> sheetBanked,
            Map<String, Long> numericIdCount, Map<String, Long> comboByConsultant, long distinctPeople) {

        public long totalRows() {
            return rows.values().stream().mapToLong(Long::longValue).sum();
        }

        /** Report!J10: COUNT() skips IDs stored as text (defect TEXT_ID_NOT_COUNTED). */
        public long sheetClientTotal() {
            return numericIdCount.values().stream().mapToLong(Long::longValue).sum();
        }

        /** Report!C9: Sunday's banked cell sums Saturday's column (defect WRONG_SHEET). */
        public long sheetSundayBanked() {
            return sheetBanked.get("Saturday");
        }
    }

    public static Expected expected() {
        Map<String, Long> rows = new LinkedHashMap<>();
        Map<String, Long> quotation = new LinkedHashMap<>();
        Map<String, Long> banked = new LinkedHashMap<>();
        Map<String, Long> numeric = new LinkedHashMap<>();
        Map<String, Long> combo = new LinkedHashMap<>();
        CONSULTANTS.forEach(c -> combo.put(c, 0L));
        java.util.Set<String> people = new java.util.HashSet<>();
        for (int d = 0; d < DAYS.size(); d++) {
            String day = DAYS.get(d);
            long q = 0;
            long b = 0;
            long n = 0;
            for (int i = 0; i < ROWS[d]; i++) {
                FakeRow r = row(d, i);
                Map<String, Integer> cells = numericCells(r);
                q += FORMULAS.get(day).containsKey(4) ? evaluate(FORMULAS.get(day).get(4), cells) : 0;
                b += evaluate(FORMULAS.get(day).get(bankedIndex(day)), cells);
                n += r.idStoredAsText() ? 0 : 1;
                people.add(r.idNumber());
                if (r.consultant() != null && combo.containsKey(r.consultant())) {
                    combo.merge(r.consultant(), (long) r.products().getOrDefault("COMBO", 0), Long::sum);
                }
            }
            rows.put(day, (long) ROWS[d]);
            quotation.put(day, q);
            banked.put(day, b);
            numeric.put(day, n);
        }
        return new Expected(rows, quotation, banked, numeric, combo, people.size());
    }

    // ---------- building the workbook ----------

    public static byte[] build() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            write(wb);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeTo(OutputStream out) throws IOException {
        out.write(build());
    }

    /** Writes a copy for manual testing: {@code FakeWeeklyRegisterWorkbook <path.xlsx>}. */
    public static void main(String[] args) throws IOException {
        Path path = Path.of(args.length > 0 ? args[0] : "fake-weekly-register.xlsx");
        Files.write(path, build());
        System.out.println("Wrote " + path.toAbsolutePath() + " (fake data only - do not commit)");
    }

    private static void write(XSSFWorkbook wb) {
        for (String name : SHEET_ORDER) {
            wb.createSheet(name);
        }
        int monday = wb.getSheetIndex("Monday");
        wb.setActiveSheet(monday);
        wb.setFirstVisibleTab(monday);
        for (String name : SHEET_ORDER) {
            if (name.startsWith("Sheet")) {
                wb.setSheetVisibility(wb.getSheetIndex(name), SheetVisibility.HIDDEN);
            }
        }

        Expected exp = expected();
        for (int d = 0; d < DAYS.size(); d++) {
            writeDay(wb.getSheet(DAYS.get(d)), d);
        }
        writeReport(wb.getSheet("Report"), exp);
        writeBreakdown(wb.getSheet("Breakdown"));
        XSSFSheet dash = wb.getSheet("Dashboard");
        Row r = dash.createRow(113);
        for (int c = 0; c < 24; c++) {
            r.createCell(c).setCellValue("Dashboard");
        }
    }

    private static void writeDay(XSSFSheet sheet, int d) {
        String day = DAYS.get(d);
        List<String> headers = HEADERS.get(day);
        Map<Integer, String> formulas = FORMULAS.get(day);
        Row h = sheet.createRow(0);
        for (int c = 0; c < headers.size(); c++) {
            if (headers.get(c) != null) {
                h.createCell(c).setCellValue(headers.get(c));
            }
        }
        for (int i = 0; i < ROWS[d]; i++) {
            FakeRow fr = row(d, i);
            Row row = sheet.createRow(i + 1);
            boolean insuranceSeen = false;
            for (int c = 0; c < headers.size(); c++) {
                if (formulas.containsKey(c)) {
                    continue;
                }
                String header = headers.get(c) == null ? null : headers.get(c).strip();
                Object value;
                if (header == null) {
                    value = fr.noHeaderValue();
                } else if ("INSURANCE".equals(header)) {
                    value = insuranceSeen ? fr.secondInsurance() : fr.products().get("INSURANCE");
                    insuranceSeen = true;
                } else if (fr.products().containsKey(header)) {
                    value = fr.products().get(header);
                } else {
                    value = field(fr, header);
                }
                set(row, c, value);
            }
        }
        int lastRow = ROWS[d] + 1 + FORMULA_ONLY_ROWS; // 1-based
        for (int r = 2; r <= lastRow; r++) {
            Row row = sheet.getRow(r - 1) != null ? sheet.getRow(r - 1) : sheet.createRow(r - 1);
            boolean dataRow = r - 2 < ROWS[d];
            Map<String, Integer> cells = dataRow ? numericCells(row(d, r - 2)) : Map.of();
            for (Map.Entry<Integer, String> f : formulas.entrySet()) {
                Cell cell = row.createCell(f.getKey());
                cell.setCellFormula(f.getValue().replace("{r}", String.valueOf(r)));
                // Cached result, as Excel would have saved it (0 on filler rows). The junk
                // loan-amount formulas on Saturday / Sunday add text cells: #VALUE! on data rows.
                Long result = dataRow ? evaluate(f.getValue(), cells) : Long.valueOf(0);
                if (result == null) {
                    cell.setCellErrorValue(FormulaError.VALUE.getCode());
                } else {
                    cell.setCellValue(result);
                }
            }
        }
    }

    private static Object field(FakeRow r, String header) {
        return switch (header) {
            case "NAME" -> r.firstName();
            case "SURNAME" -> r.surname();
            case "ID NUMBER" -> r.idCellValue();
            case "CONSULTANT" -> r.consultant();
            case "EMPLOYER" -> r.employer();
            case "STRUCTURE" -> r.structure();
            case "PAYMENT TERM" -> r.paymentTerm();
            case "AMOUNT TO CLIENT" -> r.amountToClient();
            case "3RD PARTY SETTLEMENT" -> r.thirdPartySettlement();
            case "PRIMARY STRUCTURE BUDGET LIST" -> r.primaryBudgetList();
            case "PRIMARY STRUCTURE LOAN AMOUNT" -> r.primaryLoanAmount();
            case "SECONDARY STRUCTURE LOAN AMOUNT" -> 0;
            default -> null; // DATE stays empty, like the real file
        };
    }

    private static void writeReport(XSSFSheet rep, Expected exp) {
        String[] titles = { "WEEKLY REPORT", "BANKED", "WEEKLY REPORT", "QUOTATION", null, "WEEKLY REPORT",
                "DAILY TARGET", "WEEKLY REPORT", "NUMBER OF CLIENTS" };
        Row t = rep.createRow(1);
        for (int c = 0; c < titles.length; c++) {
            if (titles[c] != null) {
                t.createCell(c + 1).setCellValue(titles[c]);
            }
        }
        Map<String, String> bankCol = new HashMap<>(BANKED_COL);
        bankCol.put("Saturday", "T");
        long qTotal = 0;
        long bTotal = 0;
        long cTotal = 0;
        for (int n = 0; n < DAYS.size(); n++) {
            String day = DAYS.get(n);
            int r = 3 + n;
            Row row = rep.createRow(r - 1);
            for (int c : new int[] { 1, 3, 6, 8 }) {
                row.createCell(c).setCellValue(day);
            }
            // Copied defect: Sunday's banked sums Saturday's column T (Sunday's banked is S).
            String bankSheet = "Sunday".equals(day) ? "Saturday" : day;
            String col = "Sunday".equals(day) ? "T" : bankCol.get(day);
            long banked = "Sunday".equals(day) ? exp.sheetSundayBanked() : exp.sheetBanked().get(day);
            formula(row, 2, "SUM(" + bankSheet + "!" + col + ":" + col + ")", banked);
            long q = exp.sheetQuotation().get(day);
            formula(row, 4, "SUM(" + day + "!E:E)", q);
            Cell target = row.createCell(7);
            target.setCellFormula("IF(E" + r + ">120000,\"Target Reached\",\"Target Not Reached\")");
            target.setCellValue(q > 120000 ? "Target Reached" : "Target Not Reached");
            long count = exp.numericIdCount().get(day); // copied defect: COUNT skips text IDs
            formula(row, 9, "COUNT(" + day + "!D:D)", count);
            qTotal += q;
            bTotal += banked;
            cTotal += count;
        }
        Row total = rep.createRow(9);
        for (int c : new int[] { 1, 3, 6, 8 }) {
            total.createCell(c).setCellValue("TOTAL");
        }
        formula(total, 2, "SUM(C3:C9)", bTotal);
        formula(total, 4, "SUM(E3:E9)", qTotal);
        Cell wt = total.createCell(7);
        wt.setCellFormula("IF(E10>600000,\"Weekly Target Reached\",\"Weekly Target Not Reached\")");
        wt.setCellValue(qTotal > 600000 ? "Weekly Target Reached" : "Weekly Target Not Reached");
        formula(total, 9, "SUM(J3:J9)", cTotal);
    }

    /** COMBO block (correct), CREDIT RESCUE block (Monday ROW_OFFSET defect) and a summary with #REF!. */
    private static void writeBreakdown(XSSFSheet bd) {
        Map<Integer, String> columnA = new HashMap<>(); // 1-based row -> text in column A, to evaluate SUMIF
        int[] combo = block(bd, columnA, "COMBO", 1, "COMBO", 0);
        int[] rescue = block(bd, columnA, "CREDIT RESCUE", 10, "CREDIT RESCUE", 8);

        row(bd, 1).createCell(11).setCellValue("WEEKLY REPORT");
        row(bd, 1).createCell(12).setCellValue("BREAKDOWN");
        row(bd, 2).createCell(11).setCellValue("COMB0");
        formula(row(bd, 2), 12, "SUM(I" + combo[0] + ":I" + combo[1] + ")", sumColumnI(bd, combo));
        row(bd, 3).createCell(11).setCellValue("CREDIT RESCUE");
        formula(row(bd, 3), 12, "SUM(I" + rescue[0] + ":I" + rescue[1] + ")", sumColumnI(bd, rescue));
        row(bd, 4).createCell(11).setCellValue("POLICY REPLACEMENT");
        Cell ref = row(bd, 4).createCell(12);
        ref.setCellFormula("SUM(#REF!)"); // copied defect
        ref.setCellErrorValue(FormulaError.REF.getCode());
    }

    private static int[] block(XSSFSheet bd, Map<Integer, String> columnA, String title, int top, String product,
            int mondayOffset) {
        row(bd, top).createCell(0).setCellValue(title);
        columnA.put(top, title);
        String[] heads = { "CONSULTANTS", "MONDAY", "TUESDAY", "WEDNSDAY", "THURSDAY", "FRIDAY", "SATURDAY",
                "SUNDAY", "TOTAL" };
        for (int c = 0; c < heads.length; c++) {
            row(bd, top + 1).createCell(c).setCellValue(heads[c]);
        }
        columnA.put(top + 1, "CONSULTANTS");
        for (int n = 0; n < CONSULTANTS.size(); n++) {
            int r = top + 2 + n;
            row(bd, r).createCell(0).setCellValue(CONSULTANTS.get(n));
            columnA.put(r, CONSULTANTS.get(n));
        }
        for (int n = 0; n < CONSULTANTS.size(); n++) {
            int r = top + 2 + n;
            long total = 0;
            for (int d = 0; d < DAYS.size(); d++) {
                String day = DAYS.get(d);
                int criteriaRow = d == 0 ? r - mondayOffset : r; // copied defect when offset > 0
                String pc = "COMBO".equals(product) ? "F" : CREDIT_RESCUE_COL.get(day);
                String cc = CONSULTANT_COL.get(day);
                String who = columnA.get(criteriaRow);
                long value = sumIf(d, who, product);
                formula(row(bd, r), 1 + d, "SUMIF(" + day + "!" + cc + ":" + cc + ",Breakdown!A" + criteriaRow + ","
                        + day + "!" + pc + ":" + pc + ")", value);
                total += value;
            }
            formula(row(bd, r), 8, "SUM(B" + r + ":H" + r + ")", total);
        }
        return new int[] { top + 2, top + 1 + CONSULTANTS.size() };
    }

    /** SUMIF over one day's rows: product amount where the consultant cell equals who. */
    private static long sumIf(int d, String who, String product) {
        if (who == null) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < ROWS[d]; i++) {
            FakeRow r = row(d, i);
            if (who.equals(r.consultant())) {
                sum += r.products().getOrDefault(product, 0);
            }
        }
        return sum;
    }

    private static long sumColumnI(XSSFSheet bd, int[] range) {
        long sum = 0;
        for (int r = range[0]; r <= range[1]; r++) {
            sum += (long) bd.getRow(r - 1).getCell(8).getNumericCellValue();
        }
        return sum;
    }

    // ---------- helpers ----------

    /**
     * Column letter -> value of a data row as the day's formulas see it: a number, 0 for an
     * empty cell, or null for a cell holding TEXT (adding it with + gives #VALUE!).
     */
    private static Map<String, Integer> numericCells(FakeRow r) {
        List<String> headers = HEADERS.get(r.day());
        Map<String, Integer> cells = new HashMap<>();
        boolean insuranceSeen = false;
        for (int c = 0; c < headers.size(); c++) {
            String header = headers.get(c) == null ? null : headers.get(c).strip();
            Integer v = 0;
            if (header == null) {
                v = r.noHeaderValue() == null ? 0 : null;
            } else if ("INSURANCE".equals(header)) {
                Integer x = insuranceSeen ? r.secondInsurance() : r.products().get("INSURANCE");
                v = x == null ? 0 : x;
                insuranceSeen = true;
            } else if (r.products().containsKey(header)) {
                v = r.products().get(header);
            } else {
                Object f = field(r, header);
                if (f instanceof Integer number) {
                    v = number;
                } else if (f instanceof String && !"ID NUMBER".equals(header)) {
                    v = null; // text
                }
            }
            cells.put(CellReference.convertNumToColString(c), v);
        }
        return cells;
    }

    /**
     * Evaluates "SUM(F{r}:N{r})+R{r}+..." against one row's cells. Null (= #VALUE!) when a
     * term added with + is text. Text inside SUM(...) is ignored, as in Excel.
     */
    private static Long evaluate(String template, Map<String, Integer> cells) {
        long sum = 0;
        Matcher m = RANGE.matcher(template);
        while (m.find()) {
            int from = CellReference.convertColStringToIndex(m.group(1));
            int to = CellReference.convertColStringToIndex(m.group(2));
            for (int c = from; c <= to; c++) {
                Integer v = cells.get(CellReference.convertNumToColString(c));
                sum += v == null ? 0 : v;
            }
        }
        for (String term : RANGE.matcher(template).replaceAll("").split("\\+")) {
            String col = term.replace("{r}", "").strip();
            if (col.isEmpty()) {
                continue;
            }
            if (cells.containsKey(col) && cells.get(col) == null) {
                return null;
            }
            sum += cells.getOrDefault(col, 0);
        }
        return sum;
    }

    private static int bankedIndex(String day) {
        return CellReference.convertColStringToIndex(BANKED_COL.get(day));
    }

    private static Row row(XSSFSheet sheet, int oneBasedRow) {
        Row r = sheet.getRow(oneBasedRow - 1);
        return r != null ? r : sheet.createRow(oneBasedRow - 1);
    }

    private static void formula(Row row, int col, String formula, long cachedValue) {
        Cell cell = row.createCell(col);
        cell.setCellFormula(formula);
        cell.setCellValue(cachedValue);
    }

    private static void set(Row row, int col, Object value) {
        if (value == null) {
            return;
        }
        Cell cell = row.createCell(col);
        if (value instanceof String s) {
            cell.setCellValue(s);
        } else if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        }
    }
}
