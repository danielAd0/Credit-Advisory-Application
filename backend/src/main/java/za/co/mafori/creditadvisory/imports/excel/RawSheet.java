package za.co.mafori.creditadvisory.imports.excel;

import java.util.Map;
import java.util.NavigableMap;

/**
 * One worksheet, sparse: only non-empty cells. Rows and columns are 0-based here; reports
 * show Excel's 1-based row numbers and column letters.
 */
public interface RawSheet {

    String name();

    boolean hidden();

    /** Rows that have at least one non-empty cell, in order: row index -> (column index -> cell). */
    NavigableMap<Integer, Map<Integer, RawCell>> rows();

    default RawCell cell(int row, int column) {
        Map<Integer, RawCell> r = rows().get(row);
        return r == null ? null : r.get(column);
    }

    default boolean isEmpty() {
        return rows().isEmpty();
    }
}
