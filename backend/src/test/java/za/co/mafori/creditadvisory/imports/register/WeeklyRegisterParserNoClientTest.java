package za.co.mafori.creditadvisory.imports.register;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static za.co.mafori.creditadvisory.imports.support.GridWorkbook.sheet;
import static za.co.mafori.creditadvisory.imports.support.GridWorkbook.with;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.imports.excel.RawCell;
import za.co.mafori.creditadvisory.imports.support.GridWorkbook;
import za.co.mafori.creditadvisory.imports.support.TestDictionary;

class WeeklyRegisterParserNoClientTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);
    private static final String ID_A = "8001015009087";

    private final WeeklyRegisterParser parser = new WeeklyRegisterParser();

    private static GridWorkbook.Sheet monday() {
        return sheet("Monday").row("DATE", "NAME", "SURNAME", "ID NUMBER", "QUOTATION", "COMBO", "BANKED",
                "CONSULTANT");
    }

    private List<ParseIssue> noClientIssues(GridWorkbook.Sheet sheet) {
        ParseResult r = parser.parse(with(sheet), MONDAY, TestDictionary.standard());
        return r.issues().stream().filter(i -> i.code().equals(IssueCodes.ROW_NO_CLIENT)).toList();
    }

    @Test
    void aConsultantWithoutAClientIsReported() {
        List<ParseIssue> issues = noClientIssues(monday()
                .row(null, "ALPHA", "PERSON", ID_A, 1500, 1500, 1500, "TEST CONSULTANT")
                .row(null, null, null, null, null, null, null, "TEST CONSULTANT"));

        assertEquals(1, issues.size());
        ParseIssue issue = issues.get(0);
        assertEquals(ParseIssue.Severity.WARNING, issue.severity());
        assertEquals("Monday", issue.sheet());
        assertEquals(Integer.valueOf(3), issue.row());
        assertNull(issue.field());
        assertTrue(issue.message().contains("not imported"));
    }

    @Test
    void aTypedAmountWithoutAClientIsReported() {
        List<ParseIssue> issues = noClientIssues(monday()
                .row(null, "ALPHA", "PERSON", ID_A, 1500, 1500, 1500, "TEST CONSULTANT")
                .row(null, null, null, null, null, 900, null, null));
        assertEquals(1, issues.size());
        assertEquals(Integer.valueOf(3), issues.get(0).row());
    }

    @Test
    void emptyRowsZerosAndTotalFormulasAreNotReported() {
        List<ParseIssue> issues = noClientIssues(monday()
                .row(null, "ALPHA", "PERSON", ID_A, 1500, 1500, 1500, "TEST CONSULTANT")
                .row(null, null, null, null, 0, 0, 0, "  ")
                .skipRow()
                // A totals row under the data: formulas, no consultant.
                .row(null, null, null, null, RawCell.number(new BigDecimal("1500"), "SUM(E2:E3)"),
                        RawCell.number(new BigDecimal("1500"), "SUM(F2:F3)"), null, null));
        assertEquals(List.of(), issues);
    }

    @Test
    void theRowIsStillNotImported() {
        ParseResult r = parser.parse(with(monday()
                .row(null, "ALPHA", "PERSON", ID_A, 1500, 1500, 1500, "TEST CONSULTANT")
                .row(null, null, null, null, null, null, null, "TEST CONSULTANT")), MONDAY, TestDictionary.standard());
        assertEquals(1, r.rows().size());
    }
}
