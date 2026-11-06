package za.co.mafori.creditadvisory.report;

import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;

/**
 * Report exports as CSV downloads (FR-22, docs/api-reports.md section 7). The
 * same reports, scoping and errors as ReportController; only the format
 * differs. Errors are still JSON ProblemDetail.
 */
@RestController
@RequestMapping("/api/reports/weeks")
public class ReportExportController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportExportService exports;

    public ReportExportController(ReportExportService exports) {
        this.exports = exports;
    }

    @GetMapping("/{weekStarting}/summary.csv")
    public ResponseEntity<byte[]> summary(@AuthenticationPrincipal AppUserPrincipal caller,
            @PathVariable String weekStarting) {
        return download(exports.summary(caller, weekStarting));
    }

    @GetMapping("/{weekStarting}/consultants.csv")
    public ResponseEntity<byte[]> consultants(@AuthenticationPrincipal AppUserPrincipal caller,
            @PathVariable String weekStarting) {
        return download(exports.consultants(caller, weekStarting));
    }

    @GetMapping("/{weekStarting}/products.csv")
    public ResponseEntity<byte[]> products(@AuthenticationPrincipal AppUserPrincipal caller,
            @PathVariable String weekStarting,
            @RequestParam(required = false) Long consultantId) {
        return download(exports.products(caller, weekStarting, consultantId));
    }

    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    @GetMapping("/{weekStarting}/budget-structures.csv")
    public ResponseEntity<byte[]> budgetStructures(@PathVariable String weekStarting) {
        return download(exports.budgetStructures(weekStarting));
    }

    /** attachment: the browser saves the file instead of showing it. */
    private static ResponseEntity<byte[]> download(ReportExportService.CsvFile file) {
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.content().getBytes(StandardCharsets.UTF_8));
    }
}
