package za.co.mafori.creditadvisory.imports.clientlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static za.co.mafori.creditadvisory.imports.support.GridWorkbook.hiddenSheet;
import static za.co.mafori.creditadvisory.imports.support.GridWorkbook.sheet;
import static za.co.mafori.creditadvisory.imports.support.GridWorkbook.with;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.client.PaymentMethod;
import za.co.mafori.creditadvisory.imports.excel.RawCell;
import za.co.mafori.creditadvisory.imports.register.ParseIssue;
import za.co.mafori.creditadvisory.imports.register.ParseIssue.Severity;
import za.co.mafori.creditadvisory.imports.support.GridWorkbook;

/**
 * The client list's reading rules (docs/api-client-list-import.md, section 2),
 * on hand-built grids. Every name and ID number here is made up.
 */
class ClientListParserTest {

    private final ClientListParser parser = new ClientListParser();

    /** The header spellings of V29 that these tests use. */
    static Map<String, ClientListField> aliases() {
        Map<String, ClientListField> a = new HashMap<>();
        a.put("NAME", ClientListField.NAME);
        a.put("FIRST NAME", ClientListField.NAME);
        a.put("SURNAME", ClientListField.SURNAME);
        a.put("ID NUMBER", ClientListField.ID_NUMBER);
        a.put("CONSULTANT", ClientListField.CONSULTANT);
        a.put("CONSULTANT EMAIL", ClientListField.CONSULTANT_EMAIL);
        a.put("EMAIL", ClientListField.CLIENT_EMAIL);
        a.put("CELL", ClientListField.CELLPHONE);
        a.put("WORK", ClientListField.WORK_PHONE);
        a.put("HOME", ClientListField.HOME_PHONE);
        a.put("EMPLOYER", ClientListField.EMPLOYER);
        a.put("C_BRANCHNAME", ClientListField.PRODUCT_QUOTATION);
        a.put("FILE NUMBER", ClientListField.FILE_NUMBER);
        a.put("FILE ID", ClientListField.FILE_ID);
        return a;
    }

    /** A valid SA ID number: the 12 digits given plus the Luhn check digit. */
    static String id(String twelve) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = twelve.charAt(11 - i) - '0';
            if (i % 2 == 0) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
        }
        return twelve + ((10 - sum % 10) % 10);
    }

    static final String ID_1 = id("800101500908");
    static final String ID_2 = id("850615012308");
    /** Born 2005: starts with 0, so a number cell drops the zero. */
    static final String ID_2005 = id("050310580008");

    private static final Object[] HEADER = { "Name", "Surname", "ID number", "Consultant", "Email", "Cell", "Work",
            "Home", "C_BRANCHNAME", "Employer", "File number", "File ID", "Date" };

    private ClientListParser.Result parse(GridWorkbook.Sheet... sheets) {
        return parser.parse(with(sheets), aliases());
    }

    private static List<ParseIssue> codes(ClientListParser.Result r, String code) {
        return r.issues().stream().filter(i -> i.code().equals(code)).toList();
    }

    @Test
    void readsTheLaunchListLayout() {
        ClientListParser.Result r = parse(sheet("Clients").row(HEADER)
                .row("  test  ", "Alpha", ID_1, "Test Consultant", "Alpha@Example.COM", "082 123 4567",
                        "011 555 0000", null, "Mafori North", "Test Municipality", 1234, "000777", null)
                .row("Test", "Bravo", ID_2, null, null, 821234567, null, "+27 21 555 0000", null,
                        "DEBIT ORDER - TEST BANK", null, null, null));

        assertEquals("Clients", r.sheetName());
        assertEquals(Integer.valueOf(1), r.headerRow());
        assertFalse(r.blocked());
        assertEquals(2, r.rows().size());

        ClientListParser.Row a = r.rows().get(0);
        assertEquals(2, a.rowNumber());
        assertEquals("test", a.firstName());
        assertEquals(ID_1, a.idNumber().value());
        assertEquals("Test Consultant", a.consultantName());
        assertEquals("alpha@example.com", a.email());
        assertEquals("+27821234567", a.cellphone());
        assertEquals("+27115550000", a.workPhone());
        assertNull(a.homePhone());
        assertEquals("Mafori North", a.productQuotation());
        assertEquals("Test Municipality", a.employerName());
        assertNull(a.paymentMethod());
        assertEquals("1234", a.fileNumber());
        assertEquals("000777", a.fileId(), "a File ID typed as text keeps its zeros");
        assertFalse(a.rejected());

        ClientListParser.Row b = r.rows().get(1);
        assertEquals("+27821234567", b.cellphone(), "a number cell lost the leading zero; it is added back");
        assertEquals("+27215550000", b.homePhone());
        assertNull(b.employerName(), "DEBIT ORDER is not an employer");
        assertEquals(PaymentMethod.DEBIT_ORDER, b.paymentMethod());

        List<ParseIssue> unknown = codes(r, ClientListParser.SHEET_UNKNOWN_COLUMN);
        assertEquals(1, unknown.size());
        assertEquals(Severity.INFO, unknown.get(0).severity());
        assertTrue(unknown.get(0).message().contains("Date"));
        assertEquals(1, codes(r, ClientListParser.ROW_PHONE_LEADING_ZERO).size());
    }

    @Test
    void theHeaderMayBeBelowATitleAndColumnsInAnyOrder() {
        ClientListParser.Result r = parse(sheet("Sheet1")
                .row("MAFORI CLIENT LIST")
                .skipRow()
                .row("ID NUMBER", "Surname", "First name")
                .row(ID_1, "Alpha", "Test"));

        assertEquals(Integer.valueOf(3), r.headerRow());
        assertEquals(1, r.rows().size());
        assertEquals(4, r.rows().get(0).rowNumber());
        assertEquals("Test", r.rows().get(0).firstName());
    }

    @Test
    void requiredFieldsRejectTheRow() {
        ClientListParser.Result r = parse(sheet("Clients").row("Name", "Surname", "ID number")
                .row(null, "Alpha", ID_1)
                .row("Test", null, ID_2)
                .row("Test", "Charlie", "8001015009088")
                .row("Test", "Delta", null)
                .row("Test", "Echo", new BigDecimal(ID_2005)));

        assertEquals(5, r.rows().size());
        assertTrue(r.rows().get(0).rejected());
        assertEquals(1, codes(r, ClientListParser.ROW_MISSING_NAME).size());
        assertTrue(r.rows().get(1).rejected());
        assertEquals(1, codes(r, ClientListParser.ROW_MISSING_SURNAME).size());
        assertTrue(r.rows().get(2).rejected(), "wrong check digit");
        assertEquals(1, codes(r, ClientListParser.ROW_INVALID_ID).size());
        assertTrue(r.rows().get(3).rejected());
        assertEquals(1, codes(r, ClientListParser.ROW_MISSING_ID).size());

        ClientListParser.Row e = r.rows().get(4);
        assertFalse(e.rejected());
        assertEquals(ID_2005, e.idNumber().value(), "the leading zero is added back");
        assertEquals(Severity.WARNING, codes(r, ClientListParser.ROW_ID_LEADING_ZERO).get(0).severity());
        assertFalse(r.blocked(), "row errors never block the whole file");
    }

    @Test
    void anOptionalValueThatCannotBeStoredIsLeftOutWithAWarning() {
        ClientListParser.Result r = parse(sheet("Clients")
                .row("Name", "Surname", "ID number", "Email", "Cell", "File number", "Consultant",
                        "Consultant email", "Employer")
                .row("Test", "Alpha", ID_1, "not-an-email", "12345", "AB-12", null, "someone@example.com",
                        GridWorkbook.error("#REF!")));

        ClientListParser.Row a = r.rows().get(0);
        assertFalse(a.rejected(), "the client is still imported");
        assertNull(a.email());
        assertNull(a.cellphone());
        assertNull(a.fileNumber());
        assertNull(a.consultantEmail());
        assertNull(a.employerName());
        assertEquals(1, codes(r, ClientListParser.ROW_INVALID_EMAIL).size());
        assertEquals(1, codes(r, ClientListParser.ROW_INVALID_PHONE).size());
        assertEquals(1, codes(r, ClientListParser.ROW_INVALID_FILE_REFERENCE).size());
        assertEquals(1, codes(r, ClientListParser.ROW_EMAIL_WITHOUT_CONSULTANT).size());
        assertEquals(1, codes(r, ClientListParser.ROW_FORMULA_ERROR_IGNORED).size());
        assertTrue(r.issues().stream().filter(i -> i.row() != null)
                .allMatch(i -> i.severity() == Severity.WARNING));
    }

    @Test
    void fileLevelProblemsBlockTheImport() {
        ClientListParser.Result none = parse(sheet("Notes").row("Name", "Comment").row("x", "y"));
        assertTrue(none.blocked());
        assertEquals(1, codes(none, ClientListParser.FILE_NO_LIST).size());

        ClientListParser.Result two = parse(sheet("A").row("Name", "Surname", "ID number").row("T", "A", ID_1),
                sheet("B").row("Name", "Surname", "ID number").row("T", "B", ID_2));
        assertTrue(two.blocked());
        assertEquals(1, codes(two, ClientListParser.FILE_SEVERAL_LISTS).size());

        ClientListParser.Result hiddenIgnored = parse(
                hiddenSheet("Old").row("Name", "Surname", "ID number").row("T", "A", ID_1),
                sheet("Clients").row("Name", "Surname", "ID number").row("T", "B", ID_2));
        assertFalse(hiddenIgnored.blocked());
        assertEquals("Clients", hiddenIgnored.sheetName());

        ClientListParser.Result duplicate = parse(sheet("Clients").row("Name", "Surname", "ID number", "Cell", "Cell")
                .row("T", "A", ID_1, "0821234567", "0831234567"));
        assertTrue(duplicate.blocked());
        assertEquals(1, codes(duplicate, ClientListParser.SHEET_DUPLICATE_COLUMN).size());

        ClientListParser.Result empty = parse(sheet("Clients").row("Name", "Surname", "ID number"));
        assertTrue(empty.blocked());
        assertEquals(1, codes(empty, ClientListParser.SHEET_EMPTY).size());
    }

    @Test
    void rowsWithoutAClientAreReportedAndEmptyRowsSkipped() {
        ClientListParser.Result r = parse(sheet("Clients").row("Name", "Surname", "ID number", "Consultant")
                .row("Test", "Alpha", ID_1, "Test Consultant")
                .skipRow()
                .row(null, null, null, "Test Consultant")
                .row(null, null, RawCell.text("   ", null), null));

        assertEquals(1, r.rows().size());
        List<ParseIssue> noClient = codes(r, ClientListParser.ROW_NO_CLIENT);
        assertEquals(1, noClient.size());
        assertEquals(Integer.valueOf(4), noClient.get(0).row());
    }

    @Test
    void messagesNeverContainAnIdNumberOrAName() {
        ClientListParser.Result r = parse(sheet("Clients").row("Name", "Surname", "ID number", "Cell", "Email",
                "8001015009087 notes")
                .row("Zebedee", "Quixotic", "8001015009088", "nope", "nope", "x")
                .row(null, "Quixotic", ID_1, null, null, null)
                .row("Zebedee", "Quixotic", new BigDecimal(ID_2005), null, null, null));

        assertFalse(r.issues().isEmpty());
        for (ParseIssue i : r.issues()) {
            assertFalse(i.message().matches("(?s).*\\d{13}.*"), i.code());
            assertFalse(i.field() != null && i.field().matches("(?s).*\\d{13}.*"), i.code());
            assertFalse(i.message().contains("Zebedee") || i.message().contains("Quixotic"), i.code());
        }
    }
}
