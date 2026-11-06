package za.co.mafori.creditadvisory.imports.report;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.PageResponse;
import za.co.mafori.creditadvisory.imports.excel.RawWorkbook;
import za.co.mafori.creditadvisory.imports.register.ImportDictionary;
import za.co.mafori.creditadvisory.imports.register.ParseResult;
import za.co.mafori.creditadvisory.imports.report.ReconciliationViews.DefectSummary;
import za.co.mafori.creditadvisory.imports.report.ReconciliationViews.FigureView;
import za.co.mafori.creditadvisory.imports.report.ReconciliationViews.ReconciliationSummary;
import za.co.mafori.creditadvisory.imports.report.ReconciliationViews.SectionSummary;
import za.co.mafori.creditadvisory.imports.report.ReportFigureStore.Filter;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportStore;

@Service
public class ReconciliationService {

    private static final int MAX_PAGE_SIZE = 500;
    private static final Set<String> SECTIONS = Set.of(ReportReconciler.DAILY_BANKED,
            ReportReconciler.DAILY_QUOTATION, ReportReconciler.DAILY_TARGET, ReportReconciler.DAILY_CLIENT_COUNT,
            ReportReconciler.CONSULTANT_PRODUCT, ReportReconciler.CONSULTANT_CLIENT_COUNT,
            ReportReconciler.CONSULTANT_QUOTATION, ReportReconciler.CONSULTANT_TOTAL, ReportReconciler.FINANCE,
            ReportReconciler.SHEET_DIFFERENCE, ReportReconciler.BUDGET_BY_STRUCTURE, ReportReconciler.BREAKDOWN,
            ReportReconciler.BREAKDOWN_SUMMARY, ReportReconciler.OTHER);

    private final ReportReconciler reconciler;
    private final ReportFigureStore figures;
    private final WeeklyImportStore imports;

    public ReconciliationService(ReportReconciler reconciler, ReportFigureStore figures, WeeklyImportStore imports) {
        this.reconciler = reconciler;
        this.figures = figures;
        this.imports = imports;
    }

    @Transactional
    public ReportReconciler.Reconciliation storeForPreview(long jobId, LocalDate weekStarting, RawWorkbook workbook,
            ParseResult parsed, ImportDictionary dictionary) {
        ReportReconciler.Reconciliation r = reconciler.reconcile(workbook, parsed, dictionary);
        if (!r.figures().isEmpty()) {
            Map<String, WeeklyImportStore.ConsultantRef> consultants = imports.consultantsByName();
            Map<String, Long> structures = imports.structuresByName();
            List<ReportFigureStore.NewFigure> rows = new ArrayList<>(r.figures().size());
            for (ReportFigure f : r.figures()) {
                WeeklyImportStore.ConsultantRef c = f.consultantLabel() == null ? null
                        : consultants.get(WeeklyImportStore.key(f.consultantLabel()));
                Long productId = f.productCode() == null ? null
                        : dictionary.productByCode(f.productCode()).map(ImportDictionary.Product::id).orElse(null);
                Long structureId = f.structureLabel() == null ? null
                        : structures.get(WeeklyImportStore.key(f.structureLabel()));
                rows.add(new ReportFigureStore.NewFigure(f, c == null ? null : c.id(), productId, structureId));
            }
            figures.insert(jobId, figures.ensureWeek(weekStarting), rows);
        }
        figures.setReconciliation(jobId, r.status(), r.differences());
        return r;
    }

    public ReportFigureStore.JobReconciliation jobStatus(long jobId) {
        return figures.jobReconciliation(jobId).orElse(new ReportFigureStore.JobReconciliation("NOT_CHECKED", 0));
    }

    public ReconciliationSummary summary(long jobId) {
        ReportFigureStore.JobReconciliation job = figures.jobReconciliation(jobId)
                .orElseThrow(() -> notFound(jobId));
        List<SectionSummary> sections = figures.sectionCounts(jobId).stream()
                .map(s -> new SectionSummary(s.sheetName(), s.section(), s.figures(), s.compared(), s.differences()))
                .toList();
        List<DefectSummary> defects = figures.defectCounts(jobId).stream()
                .map(d -> new DefectSummary(d.defectCode(), d.figures(), d.differences(),
                        ReconciliationViews.DEFECTS.get(d.defectCode())))
                .toList();
        long total = sections.stream().mapToLong(SectionSummary::figures).sum();
        long compared = sections.stream().mapToLong(SectionSummary::compared).sum();
        long differences = sections.stream().mapToLong(SectionSummary::differences).sum();
        return new ReconciliationSummary(jobId, job.status(), total, compared, differences, sections, defects);
    }

    public PageResponse<FigureView> figures(long jobId, String sheet, String section, String defect,
            boolean onlyDifferences, int page, int size) {
        figures.jobReconciliation(jobId).orElseThrow(() -> notFound(jobId));
        String sheetName = blankToNull(sheet);
        if (sheetName != null && ReportReconciler.SHEETS.stream().noneMatch(s -> s.equalsIgnoreCase(sheetName))) {
            throw bad("sheet", "sheet must be Report or Breakdown.");
        }
        String sec = upper(section);
        if (sec != null && !SECTIONS.contains(sec)) {
            throw bad("section", "Unknown section " + sec + ".");
        }
        String def = upper(defect);
        if (def != null && !ReconciliationViews.DEFECTS.containsKey(def)) {
            throw bad("defect", "Unknown defect code " + def + ".");
        }
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int p = Math.max(page, 0);
        Filter filter = new Filter(sheetName, sec, def, onlyDifferences);
        List<FigureView> items = figures.figures(jobId, filter, s, (long) p * s).stream()
                .map(f -> new FigureView(f.id(), f.sheetName(), f.section(), f.blockTitle(), f.rowLabel(),
                        f.columnLabel(), f.dayOfWeek(), f.consultantId(), f.productCode(), f.structureId(),
                        f.cellRef(), f.sheetValue(), f.sheetText(), f.sheetError(), f.formula(), f.computedValue(),
                        f.computedText(), f.difference(), f.matches(), f.defectCode(),
                        f.defectCode() == null ? null : ReconciliationViews.DEFECTS.get(f.defectCode())))
                .toList();
        long total = figures.countFigures(jobId, filter);
        return new PageResponse<>(items, p, s, total, (int) ((total + s - 1) / s));
    }

    private static ApiException notFound(long jobId) {
        return ApiException.notFound("import:not-found", "Import " + jobId + " does not exist.");
    }

    private static ApiException bad(String field, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "reconciliation:invalid-" + field, "Invalid filter", detail);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String upper(String s) {
        String v = blankToNull(s);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }
}
