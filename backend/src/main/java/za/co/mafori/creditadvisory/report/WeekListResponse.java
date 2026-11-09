package za.co.mafori.creditadvisory.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/reports/weeks (docs/api-reports.md section 2): every week that has
 * an imported register, newest first.
 *
 * @param scope "ALL" for MANAGER / ADMIN (company totals), "OWN" for a
 *              CONSULTANT (only their own sales)
 */
public record WeekListResponse(String scope, List<Week> weeks) {

    public record Week(
            LocalDate weekStarting,
            long transactionCount,
            BigDecimal quotationTotal,
            BigDecimal bankedTotal) {
    }
}
