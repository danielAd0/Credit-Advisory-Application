package za.co.mafori.creditadvisory.imports.register;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static za.co.mafori.creditadvisory.imports.support.GridWorkbook.error;
import static za.co.mafori.creditadvisory.imports.support.GridWorkbook.hiddenSheet;
import static za.co.mafori.creditadvisory.imports.support.GridWorkbook.sheet;
import static za.co.mafori.creditadvisory.imports.support.GridWorkbook.with;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.imports.excel.RawCell;
import za.co.mafori.creditadvisory.imports.excel.RawWorkbook;
import za.co.mafori.creditadvisory.imports.support.GridWorkbook;
import za.co.mafori.creditadvisory.imports.support.TestDictionary;


class WeeklyRegisterParserRulesTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);
    private static final String ID_A = "8001015009087";
    private static final String ID_B = "7505050123083";
    private static final String HEADER_ID = "ID NUMBER ";

    private final WeeklyRegisterParser parser = new WeeklyRegisterParser();

    /** A Monday sheet with the required columns plus COMBO, AMOUNT TO CLIENT and DATE. */
    private static GridWorkbook.Sheet monday() {
        return sheet("Monday").row("DATE", "NAME", "SURNAME", HEADER_ID, "QUOTATION", "COMBO", "AMOUNT TO CLIENT",
                "BANKED", "CONSULTANT");
    }

    private ParseResult parse(RawWorkbook wb) {
        return parser.parse(wb, MONDAY, TestDictionary.standard());
    }

    private ParsedRow onlyRow(ParseResult r) {
        assertEquals(1, r.rows().size(), "expected exactly one data row");
        return r.rows().get(0);
    }

    private static boolean has(ParsedRow row, String code) {
        return row.issues().stream().anyMatch(i -> i.code().equals(code));
    }

    // ---------- the good path ----------

    @Test
    void readsACleanRow() {
        ParsedRow row = onlyRow(parse(with(monday()
                .row(null, " ALPHA ", "TEST  PERSON", ID_A, 1500, 1500, null, 1500, " TEST  CONSULTANT "))));

        assertFalse(row.rejected());
        assertEquals("ALPHA", row.firstName());
        assertEquals("TEST PERSON", row.lastName());
        assertEquals(ID_A, row.idNumber().value());
        assertEquals("*********9087", row.idNumberMasked());
        assertEquals("TEST CONSULTANT", row.consultantName());
        assertEquals(MONDAY, row.transactionDate());
        assertEquals(2, row.rowNumber());
        assertEquals(0, new BigDecimal("1500.00").compareTo(row.productAmounts().get("COMBO")));
        assertEquals(0, new BigDecimal("1500").compareTo(row.banked()));
        assertEquals(0, new BigDecimal("1500").compareTo(row.bankedByRule()));
        assertFalse(row.bankedDiffersFromRule());
    }

    @Test
    void idNumberMayContainSpacesAndHyphens() {
        ParsedRow row = onlyRow(parse(with(monday().row(null, "A", "B", "800101 5009-087", 0, 0, null, 0, "C"))));
        assertEquals(ID_A, row.idNumber().value());
        assertFalse(row.rejected());
    }

    @Test
    void twelveDigitNumberGetsItsLeadingZeroBack() {
        ParsedRow row = onlyRow(parse(with(monday().row(null, "A", "B", 506075001087L, 0, 0, null, 0, "C"))));
        assertEquals("0506075001087", row.idNumber().value());
        assertTrue(has(row, IssueCodes.ROW_ID_LEADING_ZERO));
        assertFalse(row.rejected());
    }

    @Test
    void headerRowNeedNotBeTheFirstRow() {
        ParseResult r = parse(with(sheet("Monday")
                .row("WEEKLY REGISTER")
                .skipRow()
                .row("NAME", "SURNAME", "ID NUMBER", "QUOTATION", "BANKED", "CONSULTANT")
                .row("A", "B", ID_A, 0, 0, "C")));
        assertEquals(4, onlyRow(r).rowNumber());
        assertEquals(Integer.valueOf(3), r.days().get(0).headerRow());
    }

    @Test
    void formulaOnlyRowsAreNotClientRows() {
        ParseResult r = parse(with(monday()
                .row(null, "A", "B", ID_A, 1500, 1500, null, 1500, "C")
                .row(null, null, null, null, 0, null, null, 0, null)
                .row(null, null, null, null, 0, null, null, 0, null)));
        assertEquals(1, r.rows().size());
    }

    // ---------- rejected rows ----------

    @Test
    void wrongCheckDigitIsRejectedWithoutShowingTheNumber() {
        ParsedRow row = onlyRow(parse(with(monday().row(null, "A", "B", "8001015009088", 0, 0, null, 0, "C"))));
        assertTrue(row.rejected());
        assertNull(row.idNumber());
        ParseIssue issue = row.issues().stream().filter(i -> i.code().equals(IssueCodes.ROW_INVALID_ID))
                .findFirst().orElseThrow();
        assertTrue(issue.message().contains("check digit"));
        assertFalse(issue.message().matches(".*\\d{13}.*"), "message must not contain the ID number");
    }

    @Test
    void missingIdNumberIsRejected() {
        ParsedRow row = onlyRow(parse(with(monday().row(null, "A", "B", null, 0, 0, null, 0, "C"))));
        assertTrue(row.rejected());
        assertTrue(has(row, IssueCodes.ROW_MISSING_ID));
    }

    @Test
    void missingSurnameIsRejected() {
        ParsedRow row = onlyRow(parse(with(monday().row(null, "A", "  ", ID_A, 0, 0, null, 0, "C"))));
        assertTrue(row.rejected());
        assertTrue(has(row, IssueCodes.ROW_MISSING_NAME));
    }

    @Test
    void negativeAmountIsRejected() {
        ParsedRow row = onlyRow(parse(with(monday().row(null, "A", "B", ID_A, 0, -100, null, 0, "C"))));
        assertTrue(row.rejected());
        assertTrue(has(row, IssueCodes.ROW_NEGATIVE_AMOUNT));
    }

    @Test
    void formulaErrorInAProductIsRejectedButInAmountToClientOnlyWarns() {
        ParseResult r = parse(with(monday()
                .row(null, "A", "B", ID_A, 0, error("#REF!"), null, 0, "C")
                .row(null, "C", "D", ID_B, 0, 1500, error("#VALUE!"), 1500, "C")));
        assertTrue(r.rows().get(0).rejected());
        assertTrue(has(r.rows().get(0), IssueCodes.ROW_FORMULA_ERROR));
        assertFalse(r.rows().get(1).rejected());
        assertTrue(has(r.rows().get(1), IssueCodes.ROW_FORMULA_ERROR_IGNORED));
        assertNull(r.rows().get(1).amountToClient());
    }

    // ---------- amounts typed as text ----------

    @Test
    void textAmountsAreReadWithAWarning() {
        ParseResult r = parse(with(monday()
                .row(null, "A", "B", ID_A, 0, "R1 500,50", null, 0, "C")
                .row(null, "A", "B", ID_A, 0, "1,500.00", null, 0, "C")
                .row(null, "A", "B", ID_A, 0, "-", null, 0, "C")
                .row(null, "A", "B", ID_A, 0, "fifteen", null, 0, "C")));
        assertEquals(0, new BigDecimal("1500.50").compareTo(r.rows().get(0).productAmounts().get("COMBO")));
        assertTrue(has(r.rows().get(0), IssueCodes.ROW_AMOUNT_WAS_TEXT));
        assertEquals(0, new BigDecimal("1500.00").compareTo(r.rows().get(1).productAmounts().get("COMBO")));
        assertNull(r.rows().get(2).productAmounts().get("COMBO"), "\"-\" means nothing");
        assertFalse(has(r.rows().get(2), IssueCodes.ROW_AMOUNT_WAS_TEXT));
        assertTrue(r.rows().get(3).rejected());
        assertTrue(has(r.rows().get(3), IssueCodes.ROW_INVALID_AMOUNT));
    }

    // ---------- dates ----------

    @Test
    void dateMustBeTheSheetsDay() {
        ParseResult r = parse(with(monday()
                .row(RawCell.date(new BigDecimal("46279"), MONDAY, null), "A", "B", ID_A, 0, 0, null, 0, "C")
                .row("15/09/2026", "A", "B", ID_A, 0, 0, null, 0, "C")
                .row("soon", "A", "B", ID_A, 0, 0, null, 0, "C")));
        assertFalse(r.rows().get(0).rejected());
        assertTrue(has(r.rows().get(1), IssueCodes.ROW_DATE_OUTSIDE_DAY));
        assertTrue(has(r.rows().get(2), IssueCodes.ROW_INVALID_DATE));
    }

    @Test
    void excelDateSerialWithoutDateFormatIsUnderstood() {
        // 46279 is 2026-09-14 in Excel's day count.
        ParsedRow row = onlyRow(parse(with(monday().row(46279, "A", "B", ID_A, 0, 0, null, 0, "C"))));
        assertFalse(has(row, IssueCodes.ROW_DATE_OUTSIDE_DAY));
    }

    // ---------- consultants, products, extras ----------

    @Test
    void noConsultantIsImportedWithAWarning() {
        ParsedRow row = onlyRow(parse(with(monday().row(null, "A", "B", ID_A, 0, 1500, null, 1500, null))));
        assertFalse(row.rejected());
        assertNull(row.consultantName());
        assertTrue(has(row, IssueCodes.ROW_NO_CONSULTANT));
    }

    @Test
    void twoInsuranceColumnsAreAddedTogether() {
        ParseResult r = parse(with(sheet("Monday")
                .row("NAME", "SURNAME", "ID NUMBER", "QUOTATION", "INSURANCE", "BANKED", "INSURANCE", "CONSULTANT")
                .row("A", "B", ID_A, 450, 225, 450, 225, "C")));
        ParsedRow row = onlyRow(r);
        assertEquals(0, new BigDecimal("450").compareTo(row.productAmounts().get("INSURANCE")));
        assertTrue(has(row, IssueCodes.ROW_DUPLICATE_PRODUCT_COLUMN));
    }

    @Test
    void misspelledHeadersAreRecognised() {
        ParseResult r = parse(with(sheet("Wednsday")
                .row("NAME", "SURNAME", "ID NUMBER", "QUOTATION", "GROECRY", "VOLUTARY DISTRIBUTION", "BANKED",
                        "CONSULTANT")
                .row("A", "B", ID_A, 0, 300, 100, 100, "C")));
        ParsedRow row = onlyRow(r);
        assertEquals(java.time.DayOfWeek.WEDNESDAY, row.day());
        assertEquals(MONDAY.plusDays(2), row.transactionDate());
        assertNotNull(row.productAmounts().get("GROCERY"));
        assertNotNull(row.productAmounts().get("VOLUNTARY_DISTRIBUTION"));
    }

    @Test
    void unknownColumnsAreKeptAsExtrasAndIdNumbersInThemAreRedacted() {
        ParseResult r = parse(with(sheet("Monday")
                .row("NAME", "SURNAME", "ID NUMBER", "QUOTATION", "BANKED", "CONSULTANT", "LOYALTY POINTS", null)
                .row("A", "B", ID_A, 0, 0, "C", 40, "see " + ID_B)));
        ParsedRow row = onlyRow(r);
        assertEquals(0, new BigDecimal("40").compareTo((BigDecimal) row.extras().get("LOYALTY POINTS")));
        assertEquals("see [redacted]", row.extras().get("Column H"));
        assertTrue(has(row, IssueCodes.ROW_EXTRA_LOOKS_LIKE_ID));
        assertEquals(2, r.issues(IssueCodes.SHEET_UNKNOWN_COLUMN).size());
    }

    @Test
    void sheetValuesAreKeptWhenTheyDifferFromTheProductRule() {
        // CREDIT REPAIR does not count toward Banked (V6), but this sheet's BANKED includes it.
        ParseResult r = parse(with(sheet("Friday")
                .row("NAME", "SURNAME", "ID NUMBER", "QUOTATION", "CREDIT REPAIR", "BANKED", "CONSULTANT")
                .row("A", "B", ID_A, 575, 575, 575, "C")));
        ParsedRow row = onlyRow(r);
        assertEquals(0, new BigDecimal("575").compareTo(row.banked()));
        assertEquals(0, BigDecimal.ZERO.compareTo(row.bankedByRule()));
        assertTrue(row.bankedDiffersFromRule());
        assertEquals(1, r.issues(IssueCodes.SHEET_BANKED_RULE_DIFFERS).size());
        assertTrue(r.issues(IssueCodes.SHEET_QUOTATION_RULE_DIFFERS).isEmpty());
    }

    @Test
    void deactivatedProductStillImportsWithAWarning() {
        ParseResult r = parser.parse(with(monday().row(null, "A", "B", ID_A, 1500, 1500, null, 1500, "C")), MONDAY,
                TestDictionary.withInactive("COMBO"));
        assertEquals(1, r.issues(IssueCodes.SHEET_INACTIVE_PRODUCT).size());
        assertNotNull(onlyRow(r).productAmounts().get("COMBO"));
    }

    @Test
    void sameClientTwiceIsOneClientWithTwoTransactions() {
        ParseResult r = parse(with(monday()
                .row(null, "A", "B", ID_A, 0, 1500, null, 1500, "C")
                .row(null, "A", "B", Long.parseLong(ID_A), 0, 1500, null, 1500, "C")));
        assertEquals(2, r.validRows().size());
        assertTrue(has(r.rows().get(1), IssueCodes.ROW_REPEAT_CLIENT));
        assertFalse(has(r.rows().get(0), IssueCodes.ROW_REPEAT_CLIENT));
    }

    // ---------- sheet-level problems ----------

    @Test
    void missingRequiredColumnStopsTheImport() {
        ParseResult r = parse(with(sheet("Monday")
                .row("NAME", "SURNAME", "ID NUMBER", "QUOTATION", "BANKED")
                .row("A", "B", ID_A, 0, 0)));
        assertTrue(r.hasBlockingErrors());
        assertEquals(1, r.issues(IssueCodes.SHEET_MISSING_HEADER).size());
        assertTrue(r.rows().isEmpty());
    }

    @Test
    void duplicateFieldColumnStopsTheImport() {
        ParseResult r = parse(with(sheet("Monday")
                .row("NAME", "SURNAME", "ID NUMBER", "QUOTATION", "BANKED", "CONSULTANT", "CONSULTANT")
                .row("A", "B", ID_A, 0, 0, "C", "D")));
        assertTrue(r.hasBlockingErrors());
        assertEquals(1, r.issues(IssueCodes.SHEET_DUPLICATE_COLUMN).size());
    }

    @Test
    void twoSheetsForTheSameDayStopTheImport() {
        ParseResult r = parse(with(monday(), sheet("Wednesday").row("NAME"), sheet("Wednsday").row("NAME")));
        assertTrue(r.hasBlockingErrors());
        assertEquals(1, r.issues(IssueCodes.SHEET_DUPLICATE_DAY).size());
    }

    @Test
    void sheetWithDataButNoHeaderRowStopsTheImport() {
        ParseResult r = parse(with(sheet("Monday").row("A", "B", ID_A)));
        assertTrue(r.hasBlockingErrors());
        assertEquals(1, r.issues(IssueCodes.SHEET_NO_HEADER_ROW).size());
    }

    @Test
    void missingDaysOtherSheetsAndHiddenSheets() {
        ParseResult r = parse(with(monday().row(null, "A", "B", ID_A, 0, 0, null, 0, "C"),
                hiddenSheet("Sheet1"), sheet("Notes").row("something"), sheet("Report").row("WEEKLY REPORT")));
        assertFalse(r.hasBlockingErrors());
        assertEquals(6, r.issues(IssueCodes.SHEET_MISSING_DAY).size());
        List<ParseIssue> unexpected = r.issues(IssueCodes.SHEET_UNEXPECTED_DATA);
        assertEquals(1, unexpected.size(), "only Notes: Sheet1 is empty and Report is read in stage 4");
        assertEquals("Notes", unexpected.get(0).sheet());
    }

    @Test
    void weekMustStartOnMonday() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(with(monday()), MONDAY.plusDays(1), TestDictionary.standard()));
    }

    @Test
    void noIssueMessageContainsAnIdNumber() {
        ParseResult r = parse(with(monday()
                .row(null, "A", "B", "8001015009088", 0, error("#REF!"), null, 0, null)
                .row(null, "A", "B", ID_A, 0, "R10", null, 0, null)
                .row(null, "A", "B", ID_A, 0, 0, null, 0, "C", "x " + ID_B)));
        for (ParseIssue i : r.issues()) {
            assertFalse(i.message().matches(".*\\d{13}.*"), i.code() + ": " + i.message());
        }
    }
}
