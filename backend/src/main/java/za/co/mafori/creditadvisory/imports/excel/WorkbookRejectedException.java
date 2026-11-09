package za.co.mafori.creditadvisory.imports.excel;

/**
 * The uploaded file cannot be read as an import workbook. The code is the API error
 * type (e.g. "file:encrypted"); the message is safe to show and never contains data.
 */
public class WorkbookRejectedException extends RuntimeException {

    private final String code;

    public WorkbookRejectedException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public WorkbookRejectedException(String code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
