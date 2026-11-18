package za.co.mafori.creditadvisory.imports.clientlist;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * A FAKE launch client list in the layout of the real one (V20 and
 * docs/api-client-list-import.md): made-up names, made-up but valid ID numbers.
 * Never put a real client in here.
 */
public final class FakeClientListWorkbook {

    public static final String[] HEADER = { "Date", "Name", "Surname", "ID number", "Consultant", "Email", "Cell",
            "Work", "Home", "C_BRANCHNAME", "Employer", "File number", "File ID" };

    /** Row 2-6. Row 5 repeats row 2's ID number; row 6 has a wrong check digit. */
    public static byte[] build(String id1, String id2, String id3, String consultant) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Clients");
            write(sheet.createRow(0), (Object[]) HEADER);
            write(sheet.createRow(1), null, "Test", "Alpha", id1, consultant, "alpha@example.test", "082 123 4567",
                    "011 555 0000", null, "Test Branch North", "Test Local Municipality", 1001.0, "2001");
            write(sheet.createRow(2), null, "Test", "Bravo", id2, consultant, null, 831234567.0, null, null,
                    "Test Branch North", "DEBIT ORDER TEST BANK", null, null);
            write(sheet.createRow(3), null, "Test", "Charlie", id3, null, "not-an-email", null, null, "021 555 0000",
                    null, null, "12A", null);
            write(sheet.createRow(4), null, "Test", "Alpha", id1, consultant, null, null, null, null, null, null,
                    null, null);
            write(sheet.createRow(5), null, "Test", "Delta", "8001015009088", consultant, null, null, null, null,
                    null, null, null, null);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Row row, Object... values) {
        for (int c = 0; c < values.length; c++) {
            Object v = values[c];
            if (v instanceof String s) {
                row.createCell(c).setCellValue(s);
            } else if (v instanceof Double d) {
                row.createCell(c).setCellValue(d);
            }
        }
    }

    private FakeClientListWorkbook() {
    }
}
