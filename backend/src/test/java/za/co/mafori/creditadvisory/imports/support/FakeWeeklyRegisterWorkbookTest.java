package za.co.mafori.creditadvisory.imports.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;

/**
 * Checks the fake workbook really has the real workbook's shape, read back with POI the
 * way the import will read it (cached values, no formula evaluation). No Spring, no DB.
 */
class FakeWeeklyRegisterWorkbookTest {

    private static XSSFWorkbook wb;
    private static FakeWeeklyRegisterWorkbook.Expected expected;

    @BeforeAll
    static void build() throws IOException {
        wb = new XSSFWorkbook(new ByteArrayInputStream(FakeWeeklyRegisterWorkbook.build()));
        expected = FakeWeeklyRegisterWorkbook.expected();
    }

    @Test
    void sheetsAreInTheRealOrderWithHiddenEmptySheets() {
        List<String> names = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            names.add(wb.getSheetName(i));
            if (wb.getSheetVisibility(i) == SheetVisibility.HIDDEN) {
                hidden.add(wb.getSheetName(i));
            }
        }
        assertThat(names).containsExactly("Sheet2", "Sheet3", "Sheet4", "Sheet1", "Monday", "Tuesday", "Sheet10",
                "Sheet8", "Wednsday", "Thursday", "Sunday", "Friday", "Saturday", "Report", "Breakdown",
                "Dashboard");
        assertThat(hidden).containsExactlyInAnyOrder("Sheet2", "Sheet3", "Sheet4", "Sheet1", "Sheet10", "Sheet8");
    }

    @Test
    void eachDayHasItsOwnLayout() {
        assertThat(header(wb.getSheet("Monday"), 16)).isEqualTo("INSURANCE");      // second INSURANCE column
        assertThat(header(wb.getSheet("Monday"), 21)).isNull();                    // column V has no header
        assertThat(header(wb.getSheet("Wednsday"), 21)).isEqualTo("BANKED");
        assertThat(header(wb.getSheet("Thursday"), 20)).isEqualTo("BANKED");
        assertThat(header(wb.getSheet("Saturday"), 13)).isEqualTo("GROECRY");
        assertThat(header(wb.getSheet("Sunday"), 15)).isEqualTo("POLICY REPLACEMENT");
        assertThat(header(wb.getSheet("Monday"), 3)).isEqualTo("ID NUMBER ");       // trailing space, as in the real file
    }

    @Test
    void formulaCellsKeepFormulaAndCachedValue() {
        Cell banked = wb.getSheet("Monday").getRow(1).getCell(23); // X2
        assertThat(banked.getCellType()).isEqualTo(CellType.FORMULA);
        assertThat(banked.getCellFormula()).isEqualTo("F2+G2+J2+K2+N2+Q2+R2+S2+T2+H2+I2");
        assertThat(banked.getCachedFormulaResultType()).isEqualTo(CellType.NUMERIC);
        assertThat(banked.getNumericCellValue()).isEqualTo(1900.0);
    }

    @Test
    void reportShowsTheCopiedDefects() {
        XSSFSheet report = wb.getSheet("Report");
        // Clients: COUNT() skips IDs stored as text.
        assertThat(report.getRow(9).getCell(9).getNumericCellValue())
                .isEqualTo((double) expected.sheetClientTotal())
                .isLessThan((double) expected.totalRows());
        // Sunday banked sums Saturday's column.
        Cell sundayBanked = report.getRow(8).getCell(2);
        assertThat(sundayBanked.getCellFormula()).isEqualTo("SUM(Saturday!T:T)");
        assertThat(sundayBanked.getNumericCellValue()).isZero();
        assertThat(expected.sheetBanked().get("Sunday")).isPositive();
    }

    @Test
    void breakdownHasRowOffsetAndRefError() {
        XSSFSheet bd = wb.getSheet("Breakdown");
        assertThat(bd.getRow(11).getCell(1).getCellFormula()).contains("Breakdown!A4"); // B12 looks at row 4
        Cell ref = bd.getRow(3).getCell(12); // M4
        assertThat(ref.getCachedFormulaResultType()).isEqualTo(CellType.ERROR);
        assertThat(FormulaError.forInt(ref.getErrorCellValue())).isEqualTo(FormulaError.REF);
    }

    @Test
    void fakeIdNumbersAreValidAndOneLostItsLeadingZero() {
        long twelveDigitNumbers = 0;
        for (FakeWeeklyRegisterWorkbook.FakeRow row : FakeWeeklyRegisterWorkbook.allRows()) {
            assertThat(SaIdNumber.isValid(row.idNumber())).as("fake ID must pass the SA ID check").isTrue();
            if (row.idCellValue() instanceof Long n && String.valueOf(n).length() == 12) {
                twelveDigitNumbers++;
            }
        }
        assertThat(twelveDigitNumbers).isEqualTo(1);
    }

    @Test
    void someIdsAreTextAndSomeAreNumbers() {
        XSSFSheet monday = wb.getSheet("Monday");
        long text = 0;
        long numeric = 0;
        for (int r = 1; r <= FakeWeeklyRegisterWorkbook.ROWS[0]; r++) {
            CellType t = monday.getRow(r).getCell(3).getCellType();
            text += t == CellType.STRING ? 1 : 0;
            numeric += t == CellType.NUMERIC ? 1 : 0;
        }
        assertThat(text).isPositive();
        assertThat(numeric).isPositive();
    }

    private static String header(XSSFSheet sheet, int column) {
        Cell c = sheet.getRow(0).getCell(column);
        return c == null ? null : c.getStringCellValue();
    }
}
