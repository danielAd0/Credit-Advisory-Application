package za.co.mafori.creditadvisory.imports.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.imports.excel.PoiWorkbookReader;
import za.co.mafori.creditadvisory.imports.excel.RawCell;
import za.co.mafori.creditadvisory.imports.excel.RawSheet;
import za.co.mafori.creditadvisory.imports.excel.RawWorkbook;
import za.co.mafori.creditadvisory.imports.excel.SimpleRawSheet;
import za.co.mafori.creditadvisory.imports.register.ImportDictionary;
import za.co.mafori.creditadvisory.imports.register.ParseResult;
import za.co.mafori.creditadvisory.imports.register.WeeklyRegisterParser;
import za.co.mafori.creditadvisory.imports.support.FakeWeeklyRegisterWorkbook;
import za.co.mafori.creditadvisory.imports.support.FakeWeeklyRegisterWorkbook.FakeRow;
import za.co.mafori.creditadvisory.imports.support.TestDictionary;

/**
 * Stage 4 on the FAKE weekly register, whose Report and Breakdown sheets copy
 * the real
 * workbook's formula defects (see FakeWeeklyRegisterWorkbook). The real
 * workbook is never used.
 */
class ReportReconcilerTest {

    private static final LocalDate WEEK = LocalDate.of(2026, 9, 14);

    private final ImportDictionary dictionary = TestDictionary.standard();
    private final ReportReconciler reconciler = new ReportReconciler();
    private RawWorkbook workbook;
    private ParseResult parsed;
    private ReportReconciler.Reconciliation result;
    private final Map<String, ReportFigure> cells = new HashMap<>();

    @BeforeEach
    void reconcileTheFakeWorkbook() {
        workbook = new PoiWorkbookReader(PoiWorkbookReader.DEFAULT_MAX_BYTES)
                .read(FakeWeeklyRegisterWorkbook.build(), "fake.xlsx");
        parsed = new WeeklyRegisterParser().parse(workbook, WEEK, dictionary);
        result = reconciler.reconcile(workbook, parsed, dictionary);
        result.figures().forEach(f -> cells.put(f.sheetName() + "!" + f.cellRef(), f));
    }

    private ReportFigure cell(String ref) {
        ReportFigure f = cells.get(ref);
        assertThat(f).as(ref + " was read").isNotNull();
        return f;
    }

    private void assertDiffers(String ref, String defect) {
        ReportFigure f = cell(ref);
        assertThat(f.isDifference()).as(ref + " differs").isTrue();
        assertThat(f.defectCode()).as(ref + " defect").isEqualTo(defect);
    }

    /** A formula that is wrong in itself but shows the right number this week. */
    private void assertLatent(String ref, String defect) {
        ReportFigure f = cell(ref);
        assertThat(f.matches()).as(ref + " matches this week").isTrue();
        assertThat(f.defectCode()).as(ref + " defect").isEqualTo(defect);
    }

    private static long sum(int day, String consultant, String product) {
        long total = 0;
        for (FakeRow r : FakeWeeklyRegisterWorkbook.allRows()) {
            if (r.dayIndex() == day && (consultant == null || consultant.equals(r.consultant()))) {
                total += r.products().getOrDefault(product, 0);
            }
        }
        return total;
    }

    @Test
    void everyFormulaCellIsReadAndRecalculated() {
        assertThat(result.sheetsRead()).containsExactly("Report", "Breakdown");
        assertThat(result.status()).isEqualTo("DIFFERENCES");
        assertThat(result.figures()).hasSizeGreaterThan(250);
        assertThat(result.figures()).allSatisfy(f -> {
            assertThat(f.matches()).as(f.cellRef() + " compared").isNotNull();
            assertThat(f.defectCode()).as(f.sheetName() + "!" + f.cellRef() + " explained").isNotEqualTo("UNEXPLAINED");
        });
        // Every difference is explained by a defect code.
        assertThat(result.figures().stream().filter(ReportFigure::isDifference))
                .allSatisfy(f -> assertThat(f.defectCode()).isNotNull());
        assertThat(result.differences()).isEqualTo(
                (int) result.figures().stream().filter(ReportFigure::isDifference).count());
    }

    @Test
    void daySummaryDefects() {
        FakeWeeklyRegisterWorkbook.Expected expected = FakeWeeklyRegisterWorkbook.expected();
        ReportFigure sundayBanked = cell("Report!C9");
        assertThat(sundayBanked.section()).isEqualTo("DAILY_BANKED");
        assertThat(sundayBanked.dayOfWeek()).isEqualTo(7);
        assertThat(sundayBanked.sheetValue()).isEqualByComparingTo("0");
        assertThat(sundayBanked.computedValue()).isEqualByComparingTo(
                BigDecimal.valueOf(expected.sheetBanked().get("Sunday")));
        assertDiffers("Report!C9", "WRONG_SHEET");
        assertDiffers("Report!C10", "WRONG_SHEET"); // the total inherits it

        ReportFigure mondayClients = cell("Report!J3");
        assertThat(mondayClients.computedValue())
                .isEqualByComparingTo(BigDecimal.valueOf(expected.rows().get("Monday")));
        assertThat(mondayClients.sheetValue()).isEqualByComparingTo(
                BigDecimal.valueOf(expected.numericIdCount().get("Monday")));
        assertDiffers("Report!J3", "TEXT_ID_NOT_COUNTED");
        assertDiffers("Report!J10", "TEXT_ID_NOT_COUNTED");

        assertThat(cell("Report!E3").matches()).isTrue();
        assertThat(cell("Report!E3").defectCode()).isNull();
        assertThat(cell("Report!C3").defectCode()).isNull();
        assertLatent("Report!H3", "THRESHOLD_GREATER_THAN");
        assertThat(cell("Report!H3").section()).isEqualTo("DAILY_TARGET");
    }

    @Test
    void reportBlocksAreFoundByTheirLabels() {
        ReportFigure fourWednesday = cell("Report!E17");
        assertThat(fourWednesday.section()).isEqualTo("CONSULTANT_PRODUCT");
        assertThat(fourWednesday.blockTitle()).isEqualTo("Groceries");
        assertThat(fourWednesday.rowLabel()).isEqualTo("TEST CONSULTANT FOUR");
        assertThat(fourWednesday.consultantLabel()).isEqualTo("TEST CONSULTANT FOUR");
        assertThat(fourWednesday.columnLabel()).isEqualTo("WEDNSDAY");
        assertThat(fourWednesday.dayOfWeek()).isEqualTo(3);
        assertThat(fourWednesday.productCode()).isEqualTo("GROCERY");
        assertThat(fourWednesday.computedValue())
                .isEqualByComparingTo(BigDecimal.valueOf(sum(2, "TEST CONSULTANT FOUR", "GROCERY")));
        assertDiffers("Report!E17", "WRONG_CRITERIA_COLUMN");
        assertLatent("Report!E14", "WRONG_CRITERIA_COLUMN");
        // The Sunday part (no title of its own) belongs to the same block.
        assertThat(cell("Report!C23").blockTitle()).isEqualTo("Groceries");
        assertThat(cell("Report!C23").matches()).isTrue();
        ReportFigure labelThursday = cell("Report!H18");
        assertThat(labelThursday.productCode()).isEqualTo("CASH_ADVANCE_LITE"); // what it really adds
        assertThat(labelThursday.computedValue()).isEqualByComparingTo("0"); // TESTLABEL's Thursday groceries
        assertDiffers("Report!H18", "WRONG_COLUMN");
        assertDiffers("Report!H25", "WRONG_COLUMN"); // TESTLABEL's week total
        assertDiffers("Report!H24", "WRONG_CRITERIA_COLUMN"); // FOUR's week total
        assertDiffers("Report!H26", "WRONG_CRITERIA_COLUMN"); // TOTAL row

        assertThat(cell("Report!G30").section()).isEqualTo("CONSULTANT_CLIENT_COUNT");
        assertDiffers("Report!G30", "WRONG_CRITERIA_COLUMN"); // Friday counts in STRUCTURE
        assertDiffers("Report!D35", "BLANK_CONSULTANT_EXCLUDED"); // Tuesday rows without a consultant
        assertDiffers("Report!F35", "BLANK_CONSULTANT_EXCLUDED"); // Thursday's unlisted consultant
        assertThat(cell("Report!C35").matches()).isTrue();
    }

    @Test
    void financeBudgetAndDifference() {
        ReportFigure vatIncluded = cell("Report!B39");
        assertThat(vatIncluded.section()).isEqualTo("FINANCE");
        assertDiffers("Report!B39", "RANGE_INCOMPLETE"); // Friday left out
        assertDiffers("Report!C39", "RANGE_INCOMPLETE");
        assertThat(cell("Report!C39").computedValue()).isEqualByComparingTo(
                vatIncluded.computedValue().multiply(BigDecimal.valueOf(15))
                        .divide(BigDecimal.valueOf(115), 2, java.math.RoundingMode.HALF_UP));

        ReportFigure housing = cell("Report!C43");
        assertThat(housing.section()).isEqualTo("BUDGET_BY_STRUCTURE");
        assertThat(housing.structureLabel()).isEqualTo("Mafori Housing");
        assertThat(housing.consultantLabel()).isNull();
        assertThat(housing.computedValue()).isEqualByComparingTo("500");
        assertThat(housing.matches()).isTrue();
        assertThat(housing.defectCode()).isNull();
        assertDiffers("Report!J45", "RANGE_INCOMPLETE"); // the TOTAL adds only the second row

        assertThat(cell("Report!B49").section()).isEqualTo("SHEET_DIFFERENCE");
        assertThat(cell("Report!B49").computedValue()).isEqualByComparingTo("0");
        assertThat(cell("Report!B49").isDifference()).isTrue();
    }

    @Test
    void breakdownDefects() {
        assertThat(cell("Breakdown!B3").section()).isEqualTo("BREAKDOWN");
        assertThat(cell("Breakdown!B3").blockTitle()).isEqualTo("COMBO");
        for (int r = 3; r <= 7; r++) {
            assertThat(cell("Breakdown!I" + r).matches()).as("COMBO is correct").isTrue();
            assertThat(cell("Breakdown!I" + r).defectCode()).isNull();
        }
        assertDiffers("Breakdown!B12", "ROW_OFFSET");
        assertLatent("Breakdown!B16", "ROW_OFFSET"); // wrong reference, right number this week
        assertDiffers("Breakdown!I12", "ROW_OFFSET");

        // "CARD" is not a product name: the block is labelled by what it adds up
        // (design Q6).
        ReportFigure card = cell("Breakdown!B21");
        assertThat(card.blockTitle()).isEqualTo("CARD");
        assertThat(card.productCode()).isEqualTo("IVC");
        assertThat(card.matches()).isTrue();
        assertThat(card.defectCode()).isNull();

        long allCombo = 0;
        for (int d = 0; d < 7; d++) {
            allCombo += sum(d, null, "COMBO");
        }
        ReportFigure comboSummary = cell("Breakdown!M2");
        assertThat(comboSummary.section()).isEqualTo("BREAKDOWN_SUMMARY");
        assertThat(comboSummary.computedValue()).isEqualByComparingTo(BigDecimal.valueOf(allCombo));
        assertDiffers("Breakdown!M2", "BLANK_CONSULTANT_EXCLUDED");
        assertDiffers("Breakdown!M3", "ROW_OFFSET");
        assertThat(cell("Breakdown!M5").matches()).isTrue();
        ReportFigure ref = cell("Breakdown!M4");
        assertThat(ref.sheetError()).isEqualTo("#REF!");
        assertThat(ref.defectCode()).isEqualTo("REF_ERROR");
        assertThat(ref.computedValue()).isEqualByComparingTo("900"); // the week's POLICY REPLACEMENT
    }

    @Test
    void cardMeansMaforiCardOnceTheBusinessSaysSo() {
        // Answering Q6 with "CARD is the Mafori Card" is one alias row (a new
        // migration).
        List<String[]> aliases = new ArrayList<>(TestDictionary.aliases());
        aliases.add(new String[] { "CARD", "MAFORI_CARD", "PRODUCT" });
        ImportDictionary withCard = ImportDictionary.of(aliases, TestDictionary.products(true));

        ReportReconciler.Reconciliation r = reconciler.reconcile(workbook, parsed, withCard);

        Map<String, ReportFigure> byCell = new HashMap<>();
        r.figures().forEach(f -> byCell.put(f.sheetName() + "!" + f.cellRef(), f));
        ReportFigure b21 = byCell.get("Breakdown!B21");
        assertThat(b21.productCode()).isEqualTo("IVC"); // what the formula really adds
        assertThat(b21.computedValue()).isEqualByComparingTo(
                BigDecimal.valueOf(sum(0, "TEST CONSULTANT ONE", "MAFORI CARD")));
        assertThat(b21.matches()).isFalse();
        assertThat(b21.defectCode()).isEqualTo("WRONG_COLUMN");
        assertThat(byCell.get("Breakdown!M5").defectCode()).isEqualTo("WRONG_COLUMN");
    }

    @Test
    void layoutIsFoundWhereverTheBlockIs() {
        // The same kind of block, somewhere else and with other spellings: still
        // understood.
        SimpleRawSheet report = new SimpleRawSheet("REPORT", false);
        report.put(40, 3, RawCell.text("  combo ", null));
        report.put(41, 3, RawCell.text("Consultants", null));
        report.put(41, 4, RawCell.text("Monday", null));
        report.put(41, 5, RawCell.text("TOTAL", null));
        String who = FakeWeeklyRegisterWorkbook.CONSULTANTS.get(0);
        long monday = sum(0, who, "COMBO");
        report.put(42, 3, RawCell.text(who.toLowerCase(), null));
        report.put(42, 4, RawCell.number(BigDecimal.valueOf(monday), "SUMIF(Monday!AB:AB,REPORT!D43,Monday!F:F)"));
        report.put(42, 5, RawCell.number(BigDecimal.valueOf(monday), "E43"));

        ReportReconciler.Reconciliation r = reconciler.reconcile(withReport(report), parsed, dictionary);

        assertThat(r.sheetsRead()).containsExactly("Report");
        ReportFigure e43 = r.figures().stream().filter(f -> f.cellRef().equals("E43")).findFirst().orElseThrow();
        assertThat(e43.sheetName()).isEqualTo("Report");
        assertThat(e43.blockTitle()).isEqualTo("combo");
        assertThat(e43.productCode()).isEqualTo("COMBO");
        assertThat(e43.dayOfWeek()).isEqualTo(1);
        assertThat(e43.matches()).isTrue();
        assertThat(e43.defectCode()).isNull();
        // The week TOTAL column only adds Monday here: the other days are missing.
        ReportFigure f43 = r.figures().stream().filter(f -> f.cellRef().equals("F43")).findFirst().orElseThrow();
        assertThat(f43.computedValue()).isEqualByComparingTo(BigDecimal.valueOf(
                sum(0, who, "COMBO") + sum(1, who, "COMBO") + sum(2, who, "COMBO") + sum(3, who, "COMBO")
                        + sum(4, who, "COMBO") + sum(5, who, "COMBO") + sum(6, who, "COMBO")));
        assertThat(f43.defectCode()).isEqualTo("RANGE_INCOMPLETE");
    }

    @Test
    void reachingTheTargetExactlyIsReached() {
        BigDecimal mondayQuotation = BigDecimal
                .valueOf(FakeWeeklyRegisterWorkbook.expected().sheetQuotation().get("Monday"));
        SimpleRawSheet report = new SimpleRawSheet("Report", false);
        report.put(1, 1, RawCell.text("WEEKLY REPORT", null));
        report.put(1, 2, RawCell.text("QUOTATION", null));
        report.put(1, 3, RawCell.text("WEEKLY REPORT", null));
        report.put(1, 4, RawCell.text("DAILY TARGET", null));
        String[] days = { "Monday", "Tuesday", "Wednsday" };
        for (int i = 0; i < days.length; i++) {
            report.put(2 + i, 1, RawCell.text(days[i], null));
            report.put(2 + i, 3, RawCell.text(days[i], null));
        }
        report.put(2, 2, RawCell.number(mondayQuotation, "SUM(Monday!E:E)"));
        // The sheet's threshold is exactly Monday's quotation: "E3 > threshold" says
        // Not Reached.
        report.put(2, 4, RawCell.text("Target Not Reached",
                "IF(C3>" + mondayQuotation.toPlainString() + ",\"Target Reached\",\"Target Not Reached\")"));

        ReportReconciler.Reconciliation r = reconciler.reconcile(withReport(report), parsed, dictionary);

        ReportFigure target = r.figures().stream().filter(f -> f.cellRef().equals("E3")).findFirst().orElseThrow();
        assertThat(target.section()).isEqualTo("DAILY_TARGET");
        assertThat(target.sheetText()).isEqualTo("Target Not Reached");
        assertThat(target.computedText()).isEqualTo("Target Reached");
        assertThat(target.matches()).isFalse();
        assertThat(target.defectCode()).isEqualTo("THRESHOLD_GREATER_THAN");
    }

    @Test
    void noReportSheetsMeansNotChecked() {
        List<RawSheet> days = new ArrayList<>(workbook.sheets());
        days.removeIf(s -> s.name().equals("Report") || s.name().equals("Breakdown"));
        ReportReconciler.Reconciliation r = reconciler.reconcile(() -> days, parsed, dictionary);
        assertThat(r.figures()).isEmpty();
        assertThat(r.status()).isEqualTo("NOT_CHECKED");
    }

    @Test
    void figuresHoldNoClientData() {
        List<String> names = FakeWeeklyRegisterWorkbook.allRows().stream()
                .flatMap(r -> java.util.stream.Stream.of(r.firstName().strip(), r.surname().strip(), r.idNumber()))
                .toList();
        for (ReportFigure f : result.figures()) {
            String all = String.join("|", String.valueOf(f.rowLabel()), String.valueOf(f.columnLabel()),
                    String.valueOf(f.blockTitle()), String.valueOf(f.formula()), String.valueOf(f.sheetText()));
            assertThat(all).doesNotContainPattern("\\d{13}");
            assertThat(names).noneMatch(n -> n.length() > 4 && all.contains(n));
        }
    }

    private RawWorkbook withReport(RawSheet report) {
        List<RawSheet> sheets = new ArrayList<>(workbook.sheets());
        sheets.removeIf(s -> s.name().equals("Report") || s.name().equals("Breakdown"));
        sheets.add(report);
        return () -> sheets;
    }
}
