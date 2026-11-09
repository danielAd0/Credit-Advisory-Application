package za.co.mafori.creditadvisory.imports.excel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** In-memory RawSheet. Used by the POI reader and by tests that build a grid by hand. */
public final class SimpleRawSheet implements RawSheet {

    private final String name;
    private final boolean hidden;
    private final TreeMap<Integer, Map<Integer, RawCell>> rows = new TreeMap<>();

    public SimpleRawSheet(String name, boolean hidden) {
        this.name = name;
        this.hidden = hidden;
    }

    /** Adds or replaces a cell (0-based row and column). A null cell is ignored. */
    public SimpleRawSheet put(int row, int column, RawCell cell) {
        if (cell != null) {
            rows.computeIfAbsent(row, r -> new LinkedHashMap<>()).put(column, cell);
        }
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean hidden() {
        return hidden;
    }

    @Override
    public NavigableMap<Integer, Map<Integer, RawCell>> rows() {
        return Collections.unmodifiableNavigableMap(rows);
    }
}
