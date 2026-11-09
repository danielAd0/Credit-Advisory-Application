package za.co.mafori.creditadvisory.imports.report;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.common.web.PageResponse;
import za.co.mafori.creditadvisory.imports.report.ReconciliationViews.FigureView;
import za.co.mafori.creditadvisory.imports.report.ReconciliationViews.ReconciliationSummary;

@RestController
@RequestMapping("/api/imports")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @GetMapping("/{id}/reconciliation")
    public ReconciliationSummary summary(@PathVariable Long id) {
        return service.summary(id);
    }

    @GetMapping("/{id}/report-figures")
    public PageResponse<FigureView> figures(@PathVariable long id, @RequestParam(required = false) String sheet,
            @RequestParam(required = false) String section, @RequestParam(required = false) String defect,
            @RequestParam(defaultValue = "false") boolean onlyDifferences, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return service.figures(id, sheet, section, defect, onlyDifferences, page, size);
    }

}
