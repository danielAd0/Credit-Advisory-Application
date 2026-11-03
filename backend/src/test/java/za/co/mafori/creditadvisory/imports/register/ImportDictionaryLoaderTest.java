package za.co.mafori.creditadvisory.imports.register;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.mafori.creditadvisory.imports.support.TestDictionary;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/** The dictionary loaded from the database must equal the one tests build from the migrations. */
class ImportDictionaryLoaderTest extends ApiTestBase {

    @Autowired
    private ImportDictionaryLoader loader;

    @Test
    void loadsAliasesAndProductsFromTheDatabase() {
        ImportDictionary db = loader.load();
        ImportDictionary test = TestDictionary.standard();

        assertEquals(DayOfWeek.WEDNESDAY, db.day("Wednsday").orElseThrow());
        assertEquals(RegisterField.ID_NUMBER, db.field("ID NUMBER ").orElseThrow());
        assertEquals("GROCERY", db.product("GROECRY").orElseThrow().code());
        assertEquals("VOLUNTARY_DISTRIBUTION", db.product("VOLUTARY DISTRIBUTION").orElseThrow().code());
        assertFalse(db.product("CREDIT REPAIR").orElseThrow().countsTowardBanked());
        assertTrue(db.product("MAFORI CARD").orElseThrow().active());

        for (String header : new String[] { "COMBO", "IVC", "INSURANCE", "MAFORI CARD", "MAFORI VOUCHER",
                "CREDIT RESCUE", "CREDIT REPAIR", "CREDIT REPAIR DEBIT ORDER", "SPEED SERVICE", "CASH ADVANCE LITE",
                "GROCERY", "GROECRY", "HOUSING ADMIN", "MAFORI WELLNESS", "VOLUTARY DISTRIBUTION",
                "POLICY REPLACEMENT" }) {
            var a = db.product(header).orElseThrow();
            var b = test.product(header).orElseThrow();
            assertEquals(b.code(), a.code(), header);
            assertEquals(b.countsTowardQuotation(), a.countsTowardQuotation(), header);
            assertEquals(b.countsTowardBanked(), a.countsTowardBanked(), header);
        }
        for (RegisterField f : RegisterField.values()) {
            String header = f.name().replace('_', ' ');
            if (f == RegisterField.THIRD_PARTY_SETTLEMENT) {
                header = "3RD PARTY SETTLEMENT";
            } else if (f.name().endsWith("_BUDGET_LIST") || f.name().endsWith("_LOAN_AMOUNT")) {
                header = header.replace("BUDGET LIST", "STRUCTURE BUDGET LIST").replace("LOAN AMOUNT",
                        "STRUCTURE LOAN AMOUNT");
            }
            assertEquals(f, db.field(header).orElseThrow(), header);
        }
    }
}
