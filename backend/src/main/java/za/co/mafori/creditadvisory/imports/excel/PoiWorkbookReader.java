package za.co.mafori.creditadvisory.imports.excel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


@Component
public class PoiWorkbookReader {

    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    private static final byte[] ZIP_SIGNATURE = { 0x50, 0x4B, 0x03, 0x04 };
    private static final byte[] OLE2_SIGNATURE = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0 };

    static {
        // Any entry that expands more than 100x is treated as a zip bomb (POI's default is 100x too,
        // set explicitly so a library upgrade cannot loosen it), and no entry may exceed 200 MB.
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(200L * 1024 * 1024);
    }

    private final long maxBytes;

    public PoiWorkbookReader(@Value("${app.imports.max-file-bytes:10485760}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public RawWorkbook read(byte[] bytes, String fileName) {
        checkBeforeOpening(bytes, fileName);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (wb.isMacroEnabled()) {
                throw macroEnabled();
            }
            List<RawSheet> sheets = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                boolean hidden = wb.isSheetHidden(i) || wb.isSheetVeryHidden(i);
                sheets.add(copy(wb.getSheetAt(i), wb.getSheetName(i), hidden));
            }
            List<RawSheet> result = List.copyOf(sheets);
            return () -> result;
        } catch (WorkbookRejectedException e) {
            throw e;
        } catch (EncryptedDocumentException e) {
            throw encrypted(e);
        } catch (IOException | RuntimeException e) {
            // Corrupt ZIP, not an Office file, zip bomb, broken XML ... The cause is never shown.
            throw new WorkbookRejectedException("file:corrupt",
                    "The file could not be read as an Excel workbook. Open it in Excel, save it as .xlsx and try again.",
                    e);
        }
    }

    private void checkBeforeOpening(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            throw new WorkbookRejectedException("file:empty", "The uploaded file is empty.");
        }
        if (bytes.length > maxBytes) {
            throw new WorkbookRejectedException("file:too-large",
                    "The file is larger than " + (maxBytes / (1024 * 1024)) + " MB.");
        }
        String name = fileName == null ? "" : fileName.strip().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsm")) {
            throw macroEnabled();
        }
        if (name.endsWith(".xls")) {
            throw new WorkbookRejectedException("file:old-format",
                    "Old .xls files are not supported. Open it in Excel and save it as .xlsx.");
        }
        if (!name.isEmpty() && !name.endsWith(".xlsx")) {
            throw new WorkbookRejectedException("file:not-xlsx", "Only Excel .xlsx files can be imported.");
        }
        if (startsWith(bytes, OLE2_SIGNATURE)) {
            throw encrypted(null);
        }
        if (!startsWith(bytes, ZIP_SIGNATURE)) {
            throw new WorkbookRejectedException("file:not-xlsx", "The file is not an Excel .xlsx workbook.");
        }
    }

    private static RawSheet copy(XSSFSheet sheet, String name, boolean hidden) {
        SimpleRawSheet raw = new SimpleRawSheet(name, hidden);
        for (Row row : sheet) {
            for (Cell cell : row) {
                raw.put(row.getRowNum(), cell.getColumnIndex(), convert(cell));
            }
        }
        return raw;
    }

    /** The cell's value as Excel saved it; null for an empty cell. */
    static RawCell convert(Cell cell) {
        CellType type = cell.getCellType();
        String formula = null;
        if (type == CellType.FORMULA) {
            try {
                formula = cell.getCellFormula();
            } catch (RuntimeException e) {
                formula = "?"; // unreadable formula text: the cached value is still used
            }
            type = cell.getCachedFormulaResultType();
        }
        switch (type) {
            case NUMERIC: {
                BigDecimal number = BigDecimal.valueOf(cell.getNumericCellValue());
                if (isDate(cell)) {
                    LocalDateTime value = cell.getLocalDateTimeCellValue();
                    return RawCell.date(number, value == null ? null : value.toLocalDate(), formula);
                }
                return RawCell.number(number, formula);
            }
            case STRING: {
                String text = cell.getStringCellValue();
                return text == null || text.isBlank() ? null : RawCell.text(text, formula);
            }
            case BOOLEAN:
                return RawCell.bool(cell.getBooleanCellValue(), formula);
            case ERROR:
                return RawCell.error(errorText(cell), formula);
            default:
                return null; // BLANK, or a formula whose cached result is empty
        }
    }

    private static boolean isDate(Cell cell) {
        try {
            return DateUtil.isCellDateFormatted(cell);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String errorText(Cell cell) {
        try {
            return FormulaError.forInt(cell.getErrorCellValue()).getString();
        } catch (RuntimeException e) {
            return "#ERROR";
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static WorkbookRejectedException encrypted(Throwable cause) {
        return new WorkbookRejectedException("file:encrypted",
                "The file is password-protected or in an old Excel format. Remove the password, save it as .xlsx and try again.",
                cause);
    }

    private static WorkbookRejectedException macroEnabled() {
        return new WorkbookRejectedException("file:macro-enabled",
                "Workbooks with macros (.xlsm) are not accepted. Save it as a normal .xlsx workbook.");
    }
}
