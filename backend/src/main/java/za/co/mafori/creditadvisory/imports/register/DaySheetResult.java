package za.co.mafori.creditadvisory.imports.register;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

public record DaySheetResult(String sheetName, DayOfWeek day, LocalDate date, Integer headerRow,
    Map<String, String>columns, int dataRows, int rejectedRows) {

        public DaySheetResult {
            columns = Map.copyOf(columns);
        }
}
