package za.co.mafori.creditadvisory.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/reports/weeks/{weekStarting}/budget-structures (docs/api-reports.md
 * section 6): primary and secondary structure loan amounts, added up under the
 * budget-list structure each one names. MANAGER and ADMIN only.
 */
public record BudgetStructureReportResponse(LocalDate weekStarting, List<Row> structures) {

    public record Row(String budgetStructure, List<Day> days, Week week) {
    }

    public record Day(int dayOfWeek, long entryCount, BigDecimal loanAmountTotal) {
    }

    public record Week(long entryCount, BigDecimal loanAmountTotal) {
    }
}
