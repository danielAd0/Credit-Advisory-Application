package za.co.mafori.creditadvisory.imports.register;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.imports.excel.PoiWorkbookReader;
import za.co.mafori.creditadvisory.imports.excel.RawWorkbook;
import za.co.mafori.creditadvisory.imports.support.FakeWeeklyRegisterWorkbook;
import za.co.mafori.creditadvisory.imports.support.TestDictionary;


class WeeklyRegisterParserFakeWorkbookTest {

    private static final LocalDate WEEK = LocalDate.of(2026, 9, 14);

    private static ParseResult result;
    private static FakeWeeklyRegisterWorkbook.Expected expected;

    @BeforeAll
    static void parseFakeWorkbook() {
        RawWorkbook wb = new PoiWorkbookReader(PoiWorkbookReader.DEFAULT_MAX_BYTES)
                .read(FakeWeeklyRegisterWorkbook.build(), "Credit Advisory Online Dashboard (14 - 18 Sept 2026).xlsx");
        result = new WeeklyRegisterParser().parse(wb, WEEK, TestDictionary.standard());
        expected = FakeWeeklyRegisterWorkbook.expected();
    }

    private static List<ParsedRow> rowsOf(DayOfWeek day) {
        return result.rows().stream().filter(r -> r.day() == day).toList();
    }

    private static BigDecimal sum(List<ParsedRow> rows, java.util.function.Function<ParsedRow, BigDecimal> f) {
        return rows.stream().map(f).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void nothingBlocksTheImport() {
        assertFalse(result.hasBlockingErrors(), () -> result.issues().stream()
                .filter(ParseIssue::blocksImport).map(ParseIssue::message).toList().toString());
        assertTrue(result.issues(IssueCodes.SHEET_MISSING_DAY).isEmpty());
        assertTrue(result.issues(IssueCodes.SHEET_UNEXPECTED_DATA).isEmpty(), "hidden sheets are empty");
    }

    @Test
    void everyClientRowIsFoundAndNoneIsRejected() {
        assertEquals(expected.totalRows(), result.rows().size());
        assertEquals(expected.totalRows(), result.validRows().size());
        for (int d = 0; d < 7; d++) {
            DayOfWeek day = DayOfWeek.of(d + 1);
            assertEquals(FakeWeeklyRegisterWorkbook.ROWS[d], rowsOf(day).size(), day.toString());
        }
    }

    @Test
    void sheetValuesOfQuotationAndBankedAreKept() {
        for (int d = 0; d < 7; d++) {
            String sheet = FakeWeeklyRegisterWorkbook.DAYS.get(d);
            List<ParsedRow> rows = rowsOf(DayOfWeek.of(d + 1));
            assertEquals(0, BigDecimal.valueOf(expected.sheetBanked().get(sheet)).compareTo(sum(rows, ParsedRow::banked)),
                    "banked " + sheet);
            assertEquals(0, BigDecimal.valueOf(expected.sheetQuotation().get(sheet))
                    .compareTo(sum(rows, ParsedRow::quotation)), "quotation " + sheet);
        }
        // Sunday's QUOTATION column has no formula in the real workbook: no sheet value.
        rowsOf(DayOfWeek.SUNDAY).forEach(r -> assertNull(r.quotation()));
    }

    @Test
    void datesComeFromTheWeekAndTheSheet() {
        rowsOf(DayOfWeek.WEDNESDAY).forEach(r -> {
            assertEquals("Wednsday", r.sheetName());
            assertEquals(WEEK.plusDays(2), r.transactionDate());
        });
    }

    @Test
    void leadingZeroIsRestoredOnce() {
        assertEquals(1, result.issues(IssueCodes.ROW_ID_LEADING_ZERO).size());
        assertTrue(result.rows().stream().anyMatch(r -> r.idNumber().value().startsWith("0")));
    }

    @Test
    void theRepeatedClientIsNoted() {
        List<ParseIssue> repeats = result.issues(IssueCodes.ROW_REPEAT_CLIENT);
        assertEquals(expected.totalRows() - expected.distinctPeople(), repeats.size());
        assertEquals("Tuesday", repeats.get(0).sheet());
        assertTrue(repeats.get(0).message().contains("Monday row 2"));
    }

    @Test
    void rowsWithoutConsultantAreKept() {
        assertEquals(3, result.issues(IssueCodes.ROW_NO_CONSULTANT).size());
        assertEquals(3, rowsOf(DayOfWeek.TUESDAY).stream().filter(r -> r.consultantName() == null).count());
        assertTrue(result.rows().stream().anyMatch(r -> FakeWeeklyRegisterWorkbook.UNMAPPED_CONSULTANT
                .equals(r.consultantName())), "names are only matched to consultants in stage 3");
    }

    @Test
    void secondInsuranceColumnIsAddedToTheFirst() {
        ParsedRow mondayRow5 = rowsOf(DayOfWeek.MONDAY).stream().filter(r -> r.rowNumber() == 5).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("450").compareTo(mondayRow5.productAmounts().get("INSURANCE")));
        assertEquals(1, result.issues(IssueCodes.ROW_DUPLICATE_PRODUCT_COLUMN).size());
    }

    @Test
    void misspelledGroceryOnSundayIsGrocery() {
        assertTrue(rowsOf(DayOfWeek.SUNDAY).stream()
                .anyMatch(r -> r.productAmounts().containsKey("GROCERY")));
        assertTrue(rowsOf(DayOfWeek.SUNDAY).stream()
                .anyMatch(r -> r.productAmounts().containsKey("POLICY_REPLACEMENT")));
    }

    @Test
    void headerlessColumnIsKeptAsExtra() {
        ParsedRow mondayRow2 = rowsOf(DayOfWeek.MONDAY).get(0);
        assertEquals(Map.of("Column V", "SEE NOTE"), mondayRow2.extras());
        List<ParseIssue> unknown = result.issues(IssueCodes.SHEET_UNKNOWN_COLUMN);
        assertEquals(1, unknown.size());
        assertEquals("Monday", unknown.get(0).sheet());
    }

    @Test
    void brokenLoanFormulasOnSundayOnlyWarn() {
        // The real Sunday loan-amount columns hold formulas that add text: #VALUE! on client rows.
        assertFalse(result.issues(IssueCodes.ROW_FORMULA_ERROR_IGNORED).isEmpty());
        rowsOf(DayOfWeek.SUNDAY).forEach(r -> assertFalse(r.rejected()));
    }

    @Test
    void daysWhoseFormulaDiffersFromTheProductRuleAreFlagged() {
        // Friday's BANKED leaves out Mafori Card; Thursday's adds Amount to Client.
        List<String> bankedDiffers = result.issues(IssueCodes.SHEET_BANKED_RULE_DIFFERS).stream()
                .map(ParseIssue::sheet).toList();
        assertTrue(bankedDiffers.contains("Friday"), bankedDiffers.toString());
        assertTrue(bankedDiffers.contains("Thursday"), bankedDiffers.toString());
        assertFalse(bankedDiffers.contains("Monday"), bankedDiffers.toString());
    }

    @Test
    void saturdayIsEmpty() {
        assertTrue(result.issues(IssueCodes.SHEET_EMPTY).stream().anyMatch(i -> "Saturday".equals(i.sheet())));
        DaySheetResult saturday = result.days().stream().filter(d -> d.day() == DayOfWeek.SATURDAY).findFirst()
                .orElseThrow();
        assertEquals(0, saturday.dataRows());
        assertEquals(Integer.valueOf(1), saturday.headerRow());
    }

    @Test
    void columnsAreMappedByHeaderNotByLetter() {
        Map<String, String> monday = result.days().get(0).columns();
        Map<String, String> thursday = result.days().stream().filter(d -> d.day() == DayOfWeek.THURSDAY)
                .findFirst().orElseThrow().columns();
        assertEquals("FIELD:BANKED", monday.get("X"));
        assertEquals("FIELD:BANKED", thursday.get("U"));
        assertEquals("PRODUCT:INSURANCE", monday.get("Q"));
        assertEquals("FIELD:CONSULTANT", thursday.get("Y"));
    }

    @Test
    void noIssueMessageContainsAnIdNumberOrAName() {
        for (ParseIssue i : result.issues()) {
            assertFalse(i.message().matches(".*\\d{13}.*"), i.message());
            assertFalse(i.message().contains("TESTPERSON"), i.message());
        }
    }
}
