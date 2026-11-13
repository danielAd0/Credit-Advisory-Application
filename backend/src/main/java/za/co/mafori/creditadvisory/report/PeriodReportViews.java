package za.co.mafori.creditadvisory.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Responses of the date-range reports (docs/api-reports.md section 9): the same
 * figures as the weekly reports, for any period from one day to a year, such
 * as a calendar month. A sale counts on its own date, so a week that runs
 * across month-end is split between the two months exactly.
 *
 * <p>
 * As in the weekly reports, scope "ALL" is MANAGER / ADMIN (the whole company,
 * with targets) and scope "OWN" is a CONSULTANT (their own sales only, and
 * every target field null).
 */
public final class PeriodReportViews {

    private PeriodReportViews() {
    }

    // ---------- GET /api/reports/months ----------

    /** The months that have sales, newest first. */
    public record MonthsResponse(String scope, List<Month> months) {
    }

    /** @param month yyyy-mm; from / to = its first and last day */
    public record Month(String month, LocalDate from, LocalDate to, long transactionCount,
            BigDecimal quotationTotal, BigDecimal bankedTotal) {
    }

    // ---------- GET /api/reports/range/summary ----------

    /**
     * @param days  every day from {@code from} to {@code to}, oldest first, also
     *              the days without sales
     * @param weeks each Monday-to-Sunday week the period touches, with the part of
     *              it inside the period
     */
    public record SummaryResponse(String scope, LocalDate from, LocalDate to, Totals totals, List<Day> days,
            List<WeekPart> weeks) {
    }

    /**
     * @param clientCount       distinct clients over the whole period (not the sum
     *                          of the days)
     * @param unattributedCount sales without a consultant (null for OWN)
     * @param daysWithSales     days that had at least one sale
     * @param daysJudged        of those, the days that had a daily target
     *                          configured (null for OWN). 0 means no target was
     *                          set for any of them: "Target not configured".
     * @param daysTargetMet     of the judged days, those whose quotation reached
     *                          that day's target (null for OWN, and null when no
     *                          day could be judged). Days without sales, such as
     *                          weekends, are not counted as missed.
     */
    public record Totals(long transactionCount, long clientCount, Long unattributedCount,
            BigDecimal quotationTotal, BigDecimal bankedTotal, int daysWithSales, Integer daysJudged,
            Integer daysTargetMet) {
    }

    /**
     * dailyTarget = the target in force on that date; both target fields null for
     * OWN.
     */
    public record Day(LocalDate date, int dayOfWeek, long transactionCount, long clientCount,
            BigDecimal quotationTotal, BigDecimal bankedTotal, BigDecimal dailyTarget, Boolean targetMet) {
    }

    /**
     * One Monday-to-Sunday week, cut to the period.
     *
     * @param weekStarting the Monday (may be before {@code from})
     * @param from         first day of the week inside the period
     * @param to           last day of the week inside the period
     * @param wholeWeek    the period holds all seven days
     * @param weeklyTarget the target in force on that Monday (null for OWN)
     * @param targetMet    only for a whole week (a part-week is never judged
     *                     against a full week's target); null otherwise and for
     *                     OWN
     */
    public record WeekPart(LocalDate weekStarting, LocalDate from, LocalDate to, boolean wholeWeek,
            long transactionCount, BigDecimal quotationTotal, BigDecimal bankedTotal, BigDecimal weeklyTarget,
            Boolean targetMet) {
    }

    // ---------- GET /api/reports/range/consultants ----------

    /** Highest quotation first; the "no consultant" row (consultant null) last. */
    public record ConsultantsResponse(String scope, LocalDate from, LocalDate to, List<ConsultantRow> consultants) {
    }

    public record ConsultantRow(ConsultantReportResponse.Consultant consultant, long transactionCount,
            long clientCount, BigDecimal quotationTotal, BigDecimal bankedTotal) {
    }

    // ---------- GET /api/reports/range/products ----------

    /**
     * consultantId = the consultant shown, or null for everyone. In display order.
     */
    public record ProductsResponse(String scope, LocalDate from, LocalDate to, Long consultantId,
            List<ProductRow> products) {
    }

    public record ProductRow(ProductReportResponse.Product product, long lineCount, BigDecimal totalAmount) {
    }

    // ---------- GET /api/reports/range/budget-structures ----------

    public record BudgetResponse(LocalDate from, LocalDate to, List<BudgetRow> structures) {
    }

    public record BudgetRow(String budgetStructure, long entryCount, BigDecimal loanAmountTotal) {
    }
}
