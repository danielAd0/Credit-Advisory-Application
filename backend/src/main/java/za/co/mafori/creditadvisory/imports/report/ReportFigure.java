package za.co.mafori.creditadvisory.imports.report;

import java.math.BigDecimal;

public record ReportFigure(String sheetName, String section, String blockTitle, String rowLabel,
        String columnLabel, Integer dayOfWeek, String consultantLabel, String productCode, String structureLabel,
        String cellRef, BigDecimal sheetValue, String sheetText, String sheetError, String formula,
        BigDecimal computedValue, String computedText, BigDecimal difference, Boolean matches, String defectCode) {

    public boolean isDifference() {
        return Boolean.FALSE.equals(matches) || sheetError != null;
    }
}
