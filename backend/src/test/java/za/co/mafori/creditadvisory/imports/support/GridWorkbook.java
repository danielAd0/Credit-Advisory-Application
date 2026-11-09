package za.co.mafori.creditadvisory.imports.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import za.co.mafori.creditadvisory.imports.excel.RawCell;
import za.co.mafori.creditadvisory.imports.excel.RawSheet;
import za.co.mafori.creditadvisory.imports.excel.RawWorkbook;
import za.co.mafori.creditadvisory.imports.excel.SimpleRawSheet;


public final class GridWorkbook {

    private GridWorkbook() {
    }

    public static RawWorkbook with(Sheet... sheets) {
        List<RawSheet> list = new ArrayList<>();
        for (Sheet s : sheets) {
            list.add(s.raw);
        }
        List<RawSheet> copy = List.copyOf(list);
        return () -> copy;
    }

    public static Sheet sheet(String name) {
        return new Sheet(name, false);
    }

    public static Sheet hiddenSheet(String name) {
        return new Sheet(name, true);
    }

    public static RawCell error(String error) {
        return RawCell.error(error, "X1+1");
    }

    public static final class Sheet {
        private final SimpleRawSheet raw;
        private int next;

        private Sheet(String name, boolean hidden) {
            this.raw = new SimpleRawSheet(name, hidden);
        }

        /** Appends the next row (the first call is row 1). */
        public Sheet row(Object... values) {
            for (int c = 0; c < values.length; c++) {
                raw.put(next, c, cell(values[c]));
            }
            next++;
            return this;
        }

        /** Leaves a completely empty row. */
        public Sheet skipRow() {
            next++;
            return this;
        }

        private static RawCell cell(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof RawCell c) {
                return c;
            }
            if (value instanceof Number n) {
                return RawCell.number(new BigDecimal(n.toString()), null);
            }
            return RawCell.text(value.toString(), null);
        }
    }
}
