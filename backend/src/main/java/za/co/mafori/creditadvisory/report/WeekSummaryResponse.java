package za.co.mafori.creditadvisory.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/reports/weeks/{weekStarting}/summary (docs/api-reports.md section
 * 3): the workbook's Report sheet. Always seven days, Monday first.
 *
 * <p>
 * Targets are company targets, so they are only filled in for scope ALL. For
 * a CONSULTANT (scope OWN) the target fields and unattributedCount are null.
 */
public record WeekSummaryResponse(
        String scope,
        LocalDate weekStarting,
        LocalDate weekEnding,
        List<Day> days,
        Week week) {

    public record Day(
            int dayOfWeek,
            LocalDate date,
            long transactionCount,
            long clientCount,
            BigDecimal quotationTotal,
            BigDecimal bankedTotal,
            BigDecimal dailyTarget,
            Boolean targetMet) {
    }

    public record Week(
            long transactionCount,
            long clientCount,
            Long unattributedCount,
            BigDecimal quotationTotal,
            BigDecimal bankedTotal,
            BigDecimal weeklyTarget,
            Boolean targetMet) {
    }
}
