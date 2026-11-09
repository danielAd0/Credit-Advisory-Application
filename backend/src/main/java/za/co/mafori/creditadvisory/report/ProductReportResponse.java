package za.co.mafori.creditadvisory.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/reports/weeks/{weekStarting}/products (docs/api-reports.md section
 * 5): the workbook's Breakdown sheet - every product, every day.
 *
 * <p>
 * Every active product is listed, also with zero sales, so the grid is always
 * complete; an inactive product is listed only if it was sold that week.
 *
 * @param consultantId whose sales these are: the filter a manager asked for,
 *                     the consultant's own id for a CONSULTANT, or null for
 *                     everyone
 */
public record ProductReportResponse(String scope, LocalDate weekStarting, Long consultantId, List<Row> products) {

    public record Row(Product product, List<Day> days, Week week) {
    }

    public record Product(long id, String code, String name) {
    }

    /** @param lineCount how many sales included this product */
    public record Day(int dayOfWeek, long lineCount, BigDecimal totalAmount) {
    }

    public record Week(long lineCount, BigDecimal totalAmount) {
    }
}
