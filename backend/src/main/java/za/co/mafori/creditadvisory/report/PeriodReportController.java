package za.co.mafori.creditadvisory.report;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.report.PeriodReportViews.BudgetResponse;
import za.co.mafori.creditadvisory.report.PeriodReportViews.ConsultantsResponse;
import za.co.mafori.creditadvisory.report.PeriodReportViews.MonthsResponse;
import za.co.mafori.creditadvisory.report.PeriodReportViews.ProductsResponse;
import za.co.mafori.creditadvisory.report.PeriodReportViews.SummaryResponse;

/**
 * Reports for any period (docs/api-reports.md section 9), and their CSV
 * downloads. Any signed-in user; PeriodReportService decides what each caller
 * sees. Budget-by-structure is MANAGER and ADMIN only, as in the weekly
 * reports.
 *
 * <p>
 * {@code from} and {@code to} are dates written as yyyy-mm-dd, both included,
 * at most 366 days apart. Every CSV download is audited (REPORT_EXPORTED).
 */
@RestController
@RequestMapping("/api/reports")
public class PeriodReportController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final PeriodReportService reports;
    private final AuditLogWriter audit;

    public PeriodReportController(PeriodReportService reports, AuditLogWriter audit) {
        this.reports = reports;
        this.audit = audit;
    }

    @GetMapping("/months")
    public MonthsResponse months(@AuthenticationPrincipal AppUserPrincipal caller) {
        return reports.months(caller);
    }

    @GetMapping("/range/summary")
    public SummaryResponse summary(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return reports.summary(caller, from, to);
    }

    @GetMapping("/range/consultants")
    public ConsultantsResponse consultants(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return reports.consultants(caller, from, to);
    }

    @GetMapping("/range/products")
    public ProductsResponse products(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Long consultantId) {
        return reports.products(caller, from, to, consultantId);
    }

    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    @GetMapping("/range/budget-structures")
    public BudgetResponse budgetStructures(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return reports.budgetStructures(from, to);
    }

    // ---------- CSV ----------

    @GetMapping("/range/summary.csv")
    public ResponseEntity<byte[]> summaryCsv(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        SummaryResponse r = reports.summary(caller, from, to);
        List<List<Object>> rows = new ArrayList<>();
        for (PeriodReportViews.Day d : r.days()) {
            // Arrays.asList: for scope OWN the target cells are null (empty).
            rows.add(Arrays.<Object>asList(ReportCsv.dayName(d.dayOfWeek()), d.date(), d.transactionCount(),
                    d.clientCount(), d.quotationTotal(), d.bankedTotal(), ReportCsv.target(r.scope(), d.dailyTarget()),
                    d.transactionCount() == 0 ? null : d.targetMet()));
        }
        for (PeriodReportViews.WeekPart w : r.weeks()) {
            rows.add(Arrays.<Object>asList(w.wholeWeek() ? "Week" : "Part of week", w.from() + " to " + w.to(),
                    w.transactionCount(), null, w.quotationTotal(), w.bankedTotal(),
                    ReportCsv.target(r.scope(), w.weeklyTarget()), w.targetMet()));
        }
        PeriodReportViews.Totals t = r.totals();
        rows.add(Arrays.<Object>asList("Period", r.from() + " to " + r.to(), t.transactionCount(), t.clientCount(),
                t.quotationTotal(), t.bankedTotal(), null, null));
        String csv = ReportCsv.document(
                List.of("Day", "Date", "Sales", "Clients", "Quotation", "Banked", "Target", "Target met"), rows);
        return download("summary", r.scope(), r.from() + "-to-" + r.to(), null, csv);
    }

    @GetMapping("/range/consultants.csv")
    public ResponseEntity<byte[]> consultantsCsv(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        ConsultantsResponse r = reports.consultants(caller, from, to);
        List<List<Object>> rows = new ArrayList<>();
        for (PeriodReportViews.ConsultantRow row : r.consultants()) {
            rows.add(Arrays.<Object>asList(row.consultant() == null ? null : row.consultant().consultantCode(),
                    row.consultant() == null ? "No consultant" : row.consultant().fullName(),
                    row.transactionCount(), row.clientCount(), row.quotationTotal(), row.bankedTotal()));
        }
        String csv = ReportCsv.document(
                List.of("Consultant code", "Consultant", "Sales", "Clients", "Quotation", "Banked"), rows);
        return download("consultants", r.scope(), r.from() + "-to-" + r.to(), null, csv);
    }

    @GetMapping("/range/products.csv")
    public ResponseEntity<byte[]> productsCsv(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Long consultantId) {
        ProductsResponse r = reports.products(caller, from, to, consultantId);
        List<List<Object>> rows = new ArrayList<>();
        for (PeriodReportViews.ProductRow row : r.products()) {
            rows.add(Arrays.<Object>asList(row.product().code(), row.product().name(), row.lineCount(),
                    row.totalAmount()));
        }
        String csv = ReportCsv.document(List.of("Product code", "Product", "Sold", "Amount"), rows);
        return download("products", r.scope(), r.from() + "-to-" + r.to(), r.consultantId(), csv);
    }

    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    @GetMapping("/range/budget-structures.csv")
    public ResponseEntity<byte[]> budgetStructuresCsv(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        BudgetResponse r = reports.budgetStructures(from, to);
        List<List<Object>> rows = new ArrayList<>();
        for (PeriodReportViews.BudgetRow row : r.structures()) {
            rows.add(Arrays.<Object>asList(row.budgetStructure(), row.entryCount(), row.loanAmountTotal()));
        }
        String csv = ReportCsv.document(List.of("Budget structure", "Entries", "Loan amount"), rows);
        return download("budget-structures", ReportService.ALL, r.from() + "-to-" + r.to(), null, csv);
    }

    /**
     * Audits the export, then sends it as a download named like
     * report-summary-2026-09-01-to-2026-09-30.csv.
     */
    private ResponseEntity<byte[]> download(String report, String scope, String period, Long consultantId,
            String csv) {
        audit.record("REPORT_EXPORTED", null, null,
                meta("report", report, "period", period, "scope", scope, "consultantId", consultantId));
        String name = "report-" + report + "-" + period + (consultantId == null ? "" : "-consultant-" + consultantId)
                + ".csv";
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build()
                        .toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
