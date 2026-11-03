package za.co.mafori.creditadvisory.imports.register;


public record ParseIssue(Severity severity, String code, String sheet, Integer row, String field, String message) {

    public enum Severity {
        /** The row (or, without a row, the whole import) cannot be imported as it is. */
        ERROR,
        /** Imported, but someone should look at it. */
        WARNING,
        /** For the summary only. */
        INFO
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /** A file- or sheet-level error stops the whole import, not just one row. */
    public boolean blocksImport() {
        return severity == Severity.ERROR && row == null;
    }
}
