package za.co.mafori.creditadvisory.report;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;

/**
 * Report exports (FR-22, docs/api-reports.md section 7): the same reports as
 * ReportService, written as CSV files.
 *
 * <p>
 * Every figure comes from ReportService, so an export can never disagree with
 * the screen, and the scoping is exactly the same: a CONSULTANT only ever
 * exports their own figures.
 *
 * <p>
 * Every export is audited (REPORT_EXPORTED), because a downloaded file can
 * leave the building (SRS: "restricted exports"). A refused export (400, 403,
 * 404) writes nothing.
 */
@Service
public class ReportExportService {

    /** A finished export: the name the browser should save it as, and the CSV. */
    public record CsvFile(String fileName, String content) {
    }

    private final ReportService reports;
    private final AuditLogWriter audit;

    public ReportExportService(ReportService reports, AuditLogWriter audit) {
        this.reports = reports;
        this.audit = audit;
    }

    // ---------- summary.csv (Report sheet) ----------

    @Transactional
    public CsvFile summary(AppUserPrincipal caller, String rawWeekStarting) {
        WeekSummaryResponse report = reports.summary(caller, rawWeekStarting);

        List<List<Object>> rows = new ArrayList<>();
        for (WeekSummaryResponse.Day day : report.days()) {
            // Arrays.asList, not List.of: for scope OWN the target cells are null (empty).
            rows.add(Arrays.<Object>asList(ReportCsv.dayName(day.dayOfWeek()), day.date(), day.transactionCount(),
                    day.clientCount(), day.quotationTotal(), day.bankedTotal(), day.dailyTarget(),
                    day.targetMet()));
        }
        WeekSummaryResponse.Week week = report.week();
        rows.add(Arrays.<Object>asList("Week", report.weekStarting() + " to " + report.weekEnding(),
                week.transactionCount(), week.clientCount(), week.quotationTotal(), week.bankedTotal(),
                week.weeklyTarget(), week.targetMet()));

        String csv = ReportCsv.document(
                List.of("Day", "Date", "Sales", "Clients", "Quotation", "Banked", "Target", "Target met"), rows);
        audited("summary", report.weekStarting().toString(), report.scope(), null);
        return new CsvFile(fileName("summary", report.weekStarting().toString(), null), csv);
    }

    // ---------- consultants.csv ----------

    @Transactional
    public CsvFile consultants(AppUserPrincipal caller, String rawWeekStarting) {
        ConsultantReportResponse report = reports.consultants(caller, rawWeekStarting);

        List<String> header = new ArrayList<>(List.of("Consultant code", "Consultant"));
        for (int day = 1; day <= 7; day++) {
            header.add(ReportCsv.dayName(day) + " quotation");
            header.add(ReportCsv.dayName(day) + " banked");
        }
        header.addAll(List.of("Week sales", "Week clients", "Week quotation", "Week banked"));

        List<List<Object>> rows = new ArrayList<>();
        for (ConsultantReportResponse.Row row : report.consultants()) {
            List<Object> cells = new ArrayList<>();
            if (row.consultant() == null) {
                cells.add(null);
                cells.add("No consultant");
            } else {
                cells.add(row.consultant().consultantCode());
                cells.add(row.consultant().fullName());
            }
            for (ConsultantReportResponse.Day day : row.days()) {
                cells.add(day.quotationTotal());
                cells.add(day.bankedTotal());
            }
            cells.add(row.week().transactionCount());
            cells.add(row.week().clientCount());
            cells.add(row.week().quotationTotal());
            cells.add(row.week().bankedTotal());
            rows.add(cells);
        }

        String csv = ReportCsv.document(header, rows);
        audited("consultants", report.weekStarting().toString(), report.scope(), null);
        return new CsvFile(fileName("consultants", report.weekStarting().toString(), null), csv);
    }

    // ---------- products.csv (Breakdown sheet) ----------

    /** @param consultantId the same ?consultantId= filter as the JSON report */
    @Transactional
    public CsvFile products(AppUserPrincipal caller, String rawWeekStarting, Long consultantId) {
        ProductReportResponse report = reports.products(caller, rawWeekStarting, consultantId);

        List<String> header = new ArrayList<>(List.of("Product code", "Product"));
        for (int day = 1; day <= 7; day++) {
            header.add(ReportCsv.dayName(day));
        }
        header.addAll(List.of("Week sales", "Week total"));

        List<List<Object>> rows = new ArrayList<>();
        for (ProductReportResponse.Row row : report.products()) {
            List<Object> cells = new ArrayList<>();
            cells.add(row.product().code());
            cells.add(row.product().name());
            for (ProductReportResponse.Day day : row.days()) {
                cells.add(day.totalAmount());
            }
            cells.add(row.week().lineCount());
            cells.add(row.week().totalAmount());
            rows.add(cells);
        }

        String csv = ReportCsv.document(header, rows);
        audited("products", report.weekStarting().toString(), report.scope(), report.consultantId());
        return new CsvFile(fileName("products", report.weekStarting().toString(), report.consultantId()), csv);
    }

    // ---------- budget-structures.csv ----------

    /** MANAGER and ADMIN only - checked in the controller. */
    @Transactional
    public CsvFile budgetStructures(String rawWeekStarting) {
        BudgetStructureReportResponse report = reports.budgetStructures(rawWeekStarting);

        List<String> header = new ArrayList<>(List.of("Budget structure"));
        for (int day = 1; day <= 7; day++) {
            header.add(ReportCsv.dayName(day));
        }
        header.addAll(List.of("Week entries", "Week loan total"));

        List<List<Object>> rows = new ArrayList<>();
        for (BudgetStructureReportResponse.Row row : report.structures()) {
            List<Object> cells = new ArrayList<>();
            cells.add(row.budgetStructure());
            for (BudgetStructureReportResponse.Day day : row.days()) {
                cells.add(day.loanAmountTotal());
            }
            cells.add(row.week().entryCount());
            cells.add(row.week().loanAmountTotal());
            rows.add(cells);
        }

        String csv = ReportCsv.document(header, rows);
        audited("budget-structures", report.weekStarting().toString(), ReportService.ALL, null);
        return new CsvFile(fileName("budget-structures", report.weekStarting().toString(), null), csv);
    }

    // ---------- helpers ----------

    private void audited(String report, String weekStarting, String scope, Long consultantId) {
        audit.record("REPORT_EXPORTED", null, null,
                meta("report", report, "weekStarting", weekStarting, "scope", scope, "consultantId", consultantId));
    }

    /**
     * report-summary-2026-09-14.csv, or report-products-2026-09-14-consultant-4.csv
     */
    private static String fileName(String report, String weekStarting, Long consultantId) {
        String forConsultant = consultantId == null ? "" : "-consultant-" + consultantId;
        return "report-" + report + "-" + weekStarting + forConsultant + ".csv";
    }
}
