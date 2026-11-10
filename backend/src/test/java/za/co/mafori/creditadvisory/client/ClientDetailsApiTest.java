package za.co.mafori.creditadvisory.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * The fields the Clients page shows (V20): work and home phone, employer,
 * product quotation, File number, File ID, payment method, and the payment term
 * and structure of the latest sale. Every ID number is made up with
 * fakeIdNumber.
 */
class ClientDetailsApiTest extends ApiTestBase {

    private static final String ID_NUMBER = fakeIdNumber(601);

    private long thandiId;
    private ApiClient thandi;
    private ApiClient admin;
    private long afritId;
    private long tongaatId;
    private long northId;
    private long southId;

    @BeforeEach
    void createPeopleAndLists() {
        thandiId = createConsultant("C001", "Thandi Test");
        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        thandi = signIn("thandi@test.local");
        admin = signIn(ADMIN_EMAIL);

        // Seeded by V10.
        afritId = employerId("AFRIT");
        tongaatId = employerId("TONGAAT HULETT STARCH");
        northId = admin.post("/api/product-quotations", """
                {"name":"Test Branch North"}""").number("$.id");
        southId = admin.post("/api/product-quotations", """
                {"name":"Test Branch South"}""").number("$.id");
    }

    private long employerId(String name) {
        return jdbc.queryForObject("select id from employers where name = ?", Long.class, name);
    }

    private void deactivateEmployer(long id) {
        jdbc.update("update employers set active = false where id = ?", id);
    }

    private void deactivateQuotation(long id) {
        jdbc.update("update product_quotations set active = false where id = ?", id);
    }

    private ApiClient.Response create(String extraJson) {
        return thandi.post("/api/clients", """
                {"idNumber":"%s","firstName":"Lerato","lastName":"Test"%s}""".formatted(ID_NUMBER, extraJson));
    }

    /** Every field, as the edit form sends them. */
    private String fullUpdate(long version, Long employerId, Long quotationId, String fileNumber,
            String paymentMethod) {
        return """
                {"firstName":"Lerato","lastName":"Test","email":null,"phone":null,
                 "workPhone":"011 555 1234","homePhone":null,"employerId":%s,"productQuotationId":%s,
                 "fileNumber":%s,"fileId":null,"paymentMethod":%s,"version":%d}"""
                .formatted(employerId, quotationId, fileNumber == null ? "null" : "\"" + fileNumber + "\"",
                        paymentMethod == null ? "null" : "\"" + paymentMethod + "\"", version);
    }

    private void sale(long clientId, String date, String paymentTermCode, String structureName) {
        jdbc.update("""
                insert into client_transactions (client_id, consultant_id, transaction_date, created_by_user_id,
                                                 payment_term_id, structure_id)
                values (?, ?, cast(? as date), ?,
                        (select id from payment_terms where code = ?),
                        (select id from structures where name = ?))
                """, clientId, thandiId, date, adminId, paymentTermCode, structureName);
    }

    // ---------- create ----------

    @Test
    void createWithEveryField() {
        ApiClient.Response created = create("""
                ,"workPhone":"011 555 1234","homePhone":"(012) 555-6789","employerId":%d,
                 "productQuotationId":%d,"fileNumber":" 0012345 ","fileId":"987","paymentMethod":"DEBIT_ORDER"
                """.formatted(afritId, northId));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.text("$.workPhone")).isEqualTo("+27115551234");
        assertThat(created.text("$.homePhone")).isEqualTo("+27125556789");
        assertThat(created.number("$.employer.id")).isEqualTo(afritId);
        assertThat(created.text("$.employer.name")).isEqualTo("AFRIT");
        assertThat(created.value("$.employer.active")).isEqualTo(true);
        assertThat(created.number("$.productQuotation.id")).isEqualTo(northId);
        assertThat(created.text("$.productQuotation.name")).isEqualTo("Test Branch North");
        // Text, so the leading zeros stay.
        assertThat(created.text("$.fileNumber")).isEqualTo("0012345");
        assertThat(created.text("$.fileId")).isEqualTo("987");
        assertThat(created.text("$.paymentMethod")).isEqualTo("DEBIT_ORDER");
        assertThat(created.value("$.latestSale")).isNull();
    }

    @Test
    void everyNewFieldIsOptional() {
        ApiClient.Response created = create("");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.value("$.workPhone")).isNull();
        assertThat(created.value("$.employer")).isNull();
        assertThat(created.value("$.productQuotation")).isNull();
        assertThat(created.value("$.fileNumber")).isNull();
        assertThat(created.value("$.paymentMethod")).isNull();
    }

    @Test
    void fileReferencesMustBeDigitsOnly() {
        for (String bad : new String[] { "12A45", "12-45", "123456789012345678901" }) {
            ApiClient.Response response = create(",\"fileNumber\":\"" + bad + "\"");
            assertThat(response.status()).as(bad).isEqualTo(400);
            assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:validation:invalid-request");
            assertThat(response.text("$.errors[0].field")).isEqualTo("fileNumber");
        }
        assertThat(create(",\"fileId\":\"F100\"").status()).isEqualTo(400);
        // A blank one means none.
        ApiClient.Response blank = create(",\"fileId\":\"   \"");
        assertThat(blank.status()).as(blank.body()).isEqualTo(201);
        assertThat(blank.value("$.fileId")).isNull();
    }

    @Test
    void badPhonesAndPaymentMethodsAreRejected() {
        ApiClient.Response badWork = create(",\"workPhone\":\"12345\"");
        assertThat(badWork.status()).isEqualTo(400);
        assertThat(badWork.text("$.type")).isEqualTo("urn:credit-advisory:validation:invalid-phone");
        assertThat(create(",\"homePhone\":\"not a number\"").status()).isEqualTo(400);

        assertThat(create(",\"paymentMethod\":\"CASH\"").status()).isEqualTo(400);
    }

    @Test
    void unknownEmployerOrQuotationIs404() {
        ApiClient.Response employer = create(",\"employerId\":999999");
        assertThat(employer.status()).isEqualTo(404);
        assertThat(employer.text("$.type")).isEqualTo("urn:credit-advisory:employer:not-found");

        ApiClient.Response quotation = create(",\"productQuotationId\":999999");
        assertThat(quotation.status()).isEqualTo(404);
        assertThat(quotation.text("$.type")).isEqualTo("urn:credit-advisory:product-quotation:not-found");
    }

    @Test
    void inactiveEmployerOrQuotationCannotBeChosen() {
        deactivateEmployer(afritId);
        deactivateQuotation(northId);

        ApiClient.Response employer = create(",\"employerId\":" + afritId);
        assertThat(employer.status()).isEqualTo(422);
        assertThat(employer.text("$.type")).isEqualTo("urn:credit-advisory:client:employer-not-active");

        ApiClient.Response quotation = create(",\"productQuotationId\":" + northId);
        assertThat(quotation.status()).isEqualTo(422);
        assertThat(quotation.text("$.type")).isEqualTo("urn:credit-advisory:client:product-quotation-not-active");
    }

    // ---------- update ----------

    @Test
    void updateChangesTheNewFieldsAndAuditsOnlyTheirNames() {
        ApiClient.Response created = create(",\"employerId\":%d,\"productQuotationId\":%d"
                .formatted(afritId, northId));
        long clientId = created.number("$.id");

        ApiClient.Response updated = thandi.patch("/api/clients/" + clientId,
                fullUpdate(created.number("$.version"), tongaatId, southId, "0042", "DEBIT_ORDER"));

        assertThat(updated.status()).as(updated.body()).isEqualTo(200);
        assertThat(updated.text("$.workPhone")).isEqualTo("+27115551234");
        assertThat(updated.text("$.employer.name")).isEqualTo("TONGAAT HULETT STARCH");
        assertThat(updated.text("$.productQuotation.name")).isEqualTo("Test Branch South");
        assertThat(updated.text("$.fileNumber")).isEqualTo("0042");
        assertThat(updated.text("$.paymentMethod")).isEqualTo("DEBIT_ORDER");

        String fields = jdbc.queryForObject("""
                select metadata ->> 'fields' from audit_logs where action = 'CLIENT_UPDATED'
                """, String.class);
        assertThat(fields).isEqualTo(
                "[\"workPhone\", \"employer\", \"productQuotation\", \"fileNumber\", \"paymentMethod\"]");
        assertThat(jdbc.queryForList("select metadata::text from audit_logs", String.class))
                .noneMatch(m -> m.contains("+27115551234") || m.contains("0042"));
    }

    @Test
    void nullClearsAField() {
        ApiClient.Response created = create(",\"employerId\":%d,\"fileNumber\":\"55\"".formatted(afritId));

        ApiClient.Response cleared = thandi.patch("/api/clients/" + created.number("$.id"),
                fullUpdate(created.number("$.version"), null, null, null, null));

        assertThat(cleared.status()).as(cleared.body()).isEqualTo(200);
        assertThat(cleared.value("$.employer")).isNull();
        assertThat(cleared.value("$.fileNumber")).isNull();
    }

    @Test
    void aDeactivatedEmployerOrQuotationCanStayButNotBeNewlyChosen() {
        ApiClient.Response created = create(",\"employerId\":%d,\"productQuotationId\":%d"
                .formatted(afritId, northId));
        long clientId = created.number("$.id");
        deactivateEmployer(afritId);
        deactivateQuotation(northId);
        deactivateEmployer(tongaatId);

        ApiClient.Response kept = thandi.patch("/api/clients/" + clientId,
                fullUpdate(created.number("$.version"), afritId, northId, null, null));
        assertThat(kept.status()).as(kept.body()).isEqualTo(200);
        assertThat(kept.number("$.employer.id")).isEqualTo(afritId);
        assertThat(kept.value("$.employer.active")).isEqualTo(false);
        assertThat(kept.value("$.productQuotation.active")).isEqualTo(false);

        ApiClient.Response switched = thandi.patch("/api/clients/" + clientId,
                fullUpdate(kept.number("$.version"), tongaatId, northId, null, null));
        assertThat(switched.status()).isEqualTo(422);
        assertThat(switched.text("$.type")).isEqualTo("urn:credit-advisory:client:employer-not-active");
    }

    // ---------- list and latest sale ----------

    @Test
    void listShowsTheNewFieldsAndTheLatestSale() {
        ApiClient.Response created = create("""
                ,"workPhone":"011 555 1234","employerId":%d,"productQuotationId":%d,"fileNumber":"0012345"
                """.formatted(afritId, northId));
        long clientId = created.number("$.id");
        long noSales = createClient("Test", "Nosales", thandiId);
        sale(clientId, "2026-01-05", null, null);
        sale(clientId, "2026-02-02", "ONCE_OFF", "Vital");

        ApiClient.Response page = admin.get("/api/clients");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.texts("$.items[*].lastName")).containsExactly("Nosales", "Test");
        assertThat(page.value("$.items[0].latestSale")).isNull();
        assertThat(page.text("$.items[1].employer.name")).isEqualTo("AFRIT");
        assertThat(page.text("$.items[1].productQuotation.name")).isEqualTo("Test Branch North");
        assertThat(page.text("$.items[1].workPhone")).isEqualTo("+27115551234");
        assertThat(page.text("$.items[1].fileNumber")).isEqualTo("0012345");
        assertThat(page.text("$.items[1].latestSale.date")).isEqualTo("2026-02-02");
        assertThat(page.text("$.items[1].latestSale.paymentTerm")).isEqualTo("Once off payment");
        assertThat(page.text("$.items[1].latestSale.structure")).isEqualTo("Vital");

        ApiClient.Response detail = thandi.get("/api/clients/" + clientId);
        assertThat(detail.text("$.latestSale.structure")).isEqualTo("Vital");
        assertThat(thandi.get("/api/clients/" + noSales).value("$.latestSale")).isNull();
    }

    @Test
    void searchFindsAClientByFileNumberOrFileId() {
        create(",\"fileNumber\":\"0012345\",\"fileId\":\"777\"");
        createClient("Test", "Other", thandiId);

        assertThat(admin.get("/api/clients?search=0012345").texts("$.items[*].lastName")).containsExactly("Test");
        assertThat(admin.get("/api/clients?search=%20777%20").texts("$.items[*].lastName"))
                .containsExactly("Test");
        // Exact match only: part of a File number finds nothing.
        assertThat(admin.get("/api/clients?search=12345").number("$.totalItems")).isZero();
        // A consultant still only sees their own clients.
        assertThat(thandi.get("/api/clients?search=777").number("$.totalItems")).isEqualTo(1);
    }
}
