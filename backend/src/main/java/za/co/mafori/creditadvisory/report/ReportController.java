package za.co.mafori.creditadvisory.report;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;

/**
 * Weekly reports (docs/api-reports.md). Any signed-in user; what each caller
 * sees is decided in ReportService, never here or in the frontend. Only
 * budget-by-structure is limited to MANAGER and ADMIN, because it cannot be
 * split per consultant.
 *
 * <p>
 * {weekStarting} is the Monday of the week, written as yyyy-mm-dd.
 */
@RestController
@RequestMapping("/api/reports/weeks")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping
    public WeekListResponse weeks(@AuthenticationPrincipal AppUserPrincipal caller) {
        return service.weeks(caller);
    }

    @GetMapping("/{weekStarting}/summary")
    public WeekSummaryResponse summary(@AuthenticationPrincipal AppUserPrincipal caller,
            @PathVariable String weekStarting) {
        return service.summary(caller, weekStarting);
    }

    @GetMapping("/{weekStarting}/consultants")
    public ConsultantReportResponse consultants(@AuthenticationPrincipal AppUserPrincipal caller,
            @PathVariable String weekStarting) {
        return service.consultants(caller, weekStarting);
    }

    @GetMapping("/{weekStarting}/products")
    public ProductReportResponse products(@AuthenticationPrincipal AppUserPrincipal caller,
            @PathVariable String weekStarting,
            @RequestParam(required = false) Long consultantId) {
        return service.products(caller, weekStarting, consultantId);
    }

    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    @GetMapping("/{weekStarting}/budget-structures")
    public BudgetStructureReportResponse budgetStructures(@PathVariable String weekStarting) {
        return service.budgetStructures(weekStarting);
    }
}
