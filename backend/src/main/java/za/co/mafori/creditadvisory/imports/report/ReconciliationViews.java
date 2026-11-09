package za.co.mafori.creditadvisory.imports.report;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class ReconciliationViews {

    private ReconciliationViews() {
    }

    public static final Map<String, String> DEFECTS = Map.ofEntries(
            Map.entry(ReportReconciler.WRONG_COLUMN,
                    "The formula adds up the wrong column (another product or field)."),
            Map.entry(ReportReconciler.WRONG_CRITERIA_COLUMN,
                    "The formula looks for the consultant (or structure) in the wrong column."),
            Map.entry(ReportReconciler.ROW_OFFSET,
                    "The formula uses another row's label, so it shows another consultant's (or structure's) figure."),
            Map.entry(ReportReconciler.WRONG_SHEET, "The formula reads another day's sheet."),
            Map.entry(ReportReconciler.TEXT_ID_NOT_COUNTED,
                    "COUNT() skips ID numbers stored as text, so clients are left out of the count."),
            Map.entry(ReportReconciler.RANGE_INCOMPLETE, "The total leaves out some of the cells it should add up."),
            Map.entry(ReportReconciler.REF_ERROR, "The formula refers to a deleted cell or column (#REF!)."),
            Map.entry(ReportReconciler.VALUE_ERROR, "The formula adds up text (#VALUE!)."),
            Map.entry(ReportReconciler.BLANK_CONSULTANT_EXCLUDED,
                    "The total adds only the listed consultants: rows without a consultant (or with an unlisted one) are left out."),
            Map.entry(ReportReconciler.THRESHOLD_GREATER_THAN,
                    "The target test uses \">\", so exactly reaching the target shows \"Not Reached\"."),
            Map.entry(ReportReconciler.UNEXPLAINED, "The figure differs for a reason the import could not identify."));

    public record ReconciliationSummary(long importJobId, String status, long figures, long compared,
            long differences, List<SectionSummary> sections, List<DefectSummary> defects) {
    }

    public record SectionSummary(String sheetName, String section, long figures, long compared, long differences) {
    }

    public record DefectSummary(String defectCode, long figures, long differences, String description) {
    }

    public record FigureView(long id, String sheetName, String section, String blockTitle, String rowLabel,
            String columnLabel, Integer dayOfWeek, Long consultantId, String productCode, Long structureId,
            String cellRef, BigDecimal sheetValue, String sheetText, String sheetError, String formula,
            BigDecimal computedValue, String computedText, BigDecimal difference, Boolean matches,
            String defectCode, String defectDescription) {
    }
}
