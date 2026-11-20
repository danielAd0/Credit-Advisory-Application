package za.co.mafori.creditadvisory.imports.register;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static za.co.mafori.creditadvisory.imports.support.GridWorkbook.sheet;
import static za.co.mafori.creditadvisory.imports.support.GridWorkbook.with;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.imports.support.GridWorkbook;
import za.co.mafori.creditadvisory.imports.support.TestDictionary;

/**
 * The register of 9-13 November 2026 had every DATE typed as 09\11\2026. Excel
 * keeps that as text (a backslash is not a date separator), and the import
 * used to reject every row with row:invalid-date. Day first, like d/M/uuuu.
 */
class WeeklyRegisterParserBackslashDateTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);
    private static final String ID_A = "8001015009087";

    private final WeeklyRegisterParser parser = new WeeklyRegisterParser();

    private static GridWorkbook.Sheet monday() {
        return sheet("Monday").row("DATE", "NAME", "SURNAME", "ID NUMBER", "QUOTATION", "COMBO", "BANKED",
                "CONSULTANT");
    }

    private List<ParseIssue> dateIssues(Object date) {
        ParseResult r = parser.parse(with(monday().row(date, "ALPHA", "PERSON", ID_A, 1500, 1500, 1500,
                "TEST CONSULTANT")), MONDAY, TestDictionary.standard());
        return r.issues().stream().filter(i -> "DATE".equals(i.field())).toList();
    }

    @Test
    void aBackslashDateOnItsOwnDayIsAccepted() {
        assertEquals(List.of(), dateIssues("14\\09\\2026"));
        assertEquals(List.of(), dateIssues(" 14\\9\\2026 "));
    }

    @Test
    void aBackslashDateIsStillCheckedAgainstTheSheetsDay() {
        List<ParseIssue> issues = dateIssues("15\\09\\2026");
        assertEquals(1, issues.size());
        assertEquals(IssueCodes.ROW_DATE_OUTSIDE_DAY, issues.get(0).code());
        assertTrue(issues.get(0).message().contains("2026-09-15"));
    }

    @Test
    void somethingThatIsNotADateIsStillRejected() {
        List<ParseIssue> issues = dateIssues("14\\13\\2026");
        assertEquals(1, issues.size());
        assertEquals(IssueCodes.ROW_INVALID_DATE, issues.get(0).code());
    }
}
