package za.co.mafori.creditadvisory.imports.excel;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One non-empty cell as Excel last SAVED it: for a formula cell, the cached result
 * (formulas are never evaluated by the import) plus the formula text.
 *
 * <p>
 * Exactly one of number / text / bool / error is set, by {@link Kind}. A numeric cell
 * formatted as a date also carries {@code date}.
 *
 * <p>
 * May hold personal data (names, ID numbers): never log a RawCell.
 */
public record RawCell(Kind kind, BigDecimal number, LocalDate date, String text, Boolean bool, String error,
        String formula) {

    public enum Kind {
        NUMBER, TEXT, BOOLEAN, ERROR
    }

    public static RawCell number(BigDecimal value, String formula) {
        return new RawCell(Kind.NUMBER, value, null, null, null, null, formula);
    }

    public static RawCell date(BigDecimal serial, LocalDate date, String formula) {
        return new RawCell(Kind.NUMBER, serial, date, null, null, null, formula);
    }

    public static RawCell text(String value, String formula) {
        return new RawCell(Kind.TEXT, null, null, value, null, null, formula);
    }

    public static RawCell bool(boolean value, String formula) {
        return new RawCell(Kind.BOOLEAN, null, null, null, value, null, formula);
    }

    /** @param error Excel's error text, e.g. "#REF!" */
    public static RawCell error(String error, String formula) {
        return new RawCell(Kind.ERROR, null, null, null, null, error, formula);
    }

    public boolean isFormula() {
        return formula != null;
    }

    /** True for an empty string or only spaces (Excel keeps those). */
    public boolean isBlankText() {
        return kind == Kind.TEXT && text.isBlank();
    }

    /** Never prints the value - it may be an ID number or a name. */
    @Override
    public String toString() {
        return "RawCell[" + kind + (formula != null ? ", formula" : "") + "]";
    }
}
