package za.co.mafori.creditadvisory.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/reports/weeks/{weekStarting}/consultants (docs/api-reports.md
 * section 4): quotation and banked per consultant per day.
 *
 * <p>
 * Only consultants with at least one sale in the week are listed. A row whose
 * consultant is null holds the sales that had no consultant in the register
 * (scope ALL only). A CONSULTANT gets only their own row, or an empty list.
 */
public record ConsultantReportResponse(String scope, LocalDate weekStarting, List<Row> consultants) {

    /** @param consultant null for the sales without a consultant */
    public record Row(Consultant consultant, List<Day> days, Week week) {
    }

    public record Consultant(long id, String consultantCode, String fullName) {
    }

    public record Day(int dayOfWeek, long transactionCount, BigDecimal quotationTotal, BigDecimal bankedTotal) {
    }

    public record Week(long transactionCount, long clientCount, BigDecimal quotationTotal, BigDecimal bankedTotal) {
    }
}
