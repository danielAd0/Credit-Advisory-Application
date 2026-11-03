package za.co.mafori.creditadvisory.imports.excel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.imports.support.FakeWeeklyRegisterWorkbook;

class PoiWorkbookReaderTest {

    private final PoiWorkbookReader reader = new PoiWorkbookReader(PoiWorkbookReader.DEFAULT_MAX_BYTES);

    private String rejection(byte[] bytes, String name) {
        return assertThrows(WorkbookRejectedException.class, () -> reader.read(bytes, name)).code();
    }

    @Test
    void readsTheFakeWorkbookWithCachedValues() {
        RawWorkbook wb = reader.read(FakeWeeklyRegisterWorkbook.build(), "fake.xlsx");
        assertEquals(16, wb.sheets().size());
        assertTrue(wb.sheet("Sheet1").orElseThrow().hidden());
        RawCell banked = wb.sheet("Monday").orElseThrow().cell(1, 23); // X2
        assertEquals(RawCell.Kind.NUMBER, banked.kind());
        assertEquals(0, new BigDecimal("1900").compareTo(banked.number()));
        assertEquals("F2+G2+J2+K2+N2+Q2+R2+S2+T2+H2+I2", banked.formula());
        RawCell ref = wb.sheet("Breakdown").orElseThrow().cell(3, 12); // M4
        assertEquals(RawCell.Kind.ERROR, ref.kind());
        assertEquals("#REF!", ref.error());
    }

    @Test
    void rejectsFilesThatAreNotUsableWorkbooks() {
        byte[] zipHeaderThenJunk = { 0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4, 5, 6, 7, 8 };
        byte[] ole2 = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1 };
        assertEquals("file:empty", rejection(new byte[0], "a.xlsx"));
        assertEquals("file:old-format", rejection(zipHeaderThenJunk, "a.xls"));
        assertEquals("file:macro-enabled", rejection(zipHeaderThenJunk, "a.xlsm"));
        assertEquals("file:not-xlsx", rejection(zipHeaderThenJunk, "a.csv"));
        assertEquals("file:encrypted", rejection(ole2, "a.xlsx"));
        assertEquals("file:not-xlsx", rejection("NAME,SURNAME".getBytes(StandardCharsets.UTF_8), "a.xlsx"));
        assertEquals("file:corrupt", rejection(zipHeaderThenJunk, "a.xlsx"));
    }

    @Test
    void rejectsFilesOverTheSizeLimit() {
        PoiWorkbookReader small = new PoiWorkbookReader(10);
        assertEquals("file:too-large",
                assertThrows(WorkbookRejectedException.class, () -> small.read(new byte[11], "a.xlsx")).code());
    }
}
