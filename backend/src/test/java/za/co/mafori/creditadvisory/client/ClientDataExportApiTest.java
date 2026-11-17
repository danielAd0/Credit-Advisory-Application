package za.co.mafori.creditadvisory.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * POPIA access request: POST /api/clients/{id}/data-export
 * (docs/api-auth-consultants-clients.md, section 4). Made-up people only.
 */
class ClientDataExportApiTest extends ApiTestBase {

    private long thandiId;
    private long siphoId;
    private long client;
    private String idNumber;
    private ApiClient admin;
    private ApiClient manager;
    private ApiClient thandi;

    @BeforeEach
    void createData() {
        thandiId = createConsultant("C001", "Thandi Test");
        siphoId = createConsultant("C002", "Sipho Test");
        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
        client = createClient("Export", "Person", thandiId);
        idNumber = idNumberProtector.decrypt(jdbc.queryForObject(
                "select id_number_encrypted from clients where id = ?", byte[].class, client)).value();
        admin = signIn(ADMIN_EMAIL);
        manager = signIn("manager@test.local");
        thandi = signIn("thandi@test.local");
    }

    private ApiClient.Response export(ApiClient as, long id) {
        return as.post("/api/clients/" + id + "/data-export", "{}");
    }

    private ApiClient.Response export(ApiClient as, long id, String format) {
        return as.post("/api/clients/" + id + "/data-export?format=" + format, "{}");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** A credit case for the client with one note, written by Thandi. */
    private void caseWithNote(String title, String note) {
        long caseId = thandi.post("/api/cases", """
                {"clientId":%d,"title":"%s"}""".formatted(client, title)).number("$.id");
        assertThat(thandi.post("/api/cases/" + caseId + "/notes", """
                {"note":"%s"}""".formatted(note)).status()).isEqualTo(201);
    }

    @Test
    void anAdminGetsEverythingHeldAboutTheClient() {
        long combo = count("select id from products where code = 'COMBO'");
        ApiClient.Response sale = thandi.sendWithHeader("POST", "/api/transactions", """
                {"clientId":%d,"transactionDate":"2026-09-15","lines":[{"productId":%d,"amount":1500}]}"""
                .formatted(client, combo), "Idempotency-Key", UUID.randomUUID().toString());
        assertThat(sale.status()).as(sale.body()).isEqualTo(201);
        long caseId = thandi.post("/api/cases", """
                {"clientId":%d,"title":"Debt review"}""".formatted(client)).number("$.id");
        assertThat(thandi.post("/api/cases/" + caseId + "/notes", """
                {"note":"Called the client."}""").status()).isEqualTo(201);
        confirmPassword(thandi);
        assertThat(thandi.get("/api/clients/" + client + "?include=idNumber").status()).isEqualTo(200);
        assertThat(manager.post("/api/client-transfers", """
                {"fromConsultantId":%d,"toConsultantId":%d,"clientIds":[%d],"reason":"Handover"}"""
                .formatted(thandiId, siphoId, client)).status()).isEqualTo(200);
        confirmPassword(admin);

        ApiClient.Response exported = export(admin, client);

        assertThat(exported.status()).as(exported.body()).isEqualTo(200);
        assertThat(exported.header("Content-Disposition"))
                .isEqualTo("attachment; filename=\"client-" + client + "-data.json\"");
        assertThat(exported.header("Cache-Control")).contains("no-store");
        assertThat(exported.text("$.exportedBy")).isEqualTo(ADMIN_EMAIL);
        assertThat(exported.text("$.client.idNumber")).as("the full number, for the client").isEqualTo(idNumber);
        assertThat(exported.text("$.client.firstName")).isEqualTo("Export");
        assertThat(exported.text("$.client.currentConsultant.code")).isEqualTo("C002");
        assertThat(exported.size("$.sales")).isEqualTo(1);
        assertThat(exported.texts("$.sales[0].lines[*].product")).containsExactly("Combo");
        assertThat(exported.text("$.sales[0].consultant.code")).as("who made the sale then").isEqualTo("C001");
        assertThat(exported.value("$.sales[0].typedIn")).isEqualTo(true);
        assertThat(exported.texts("$.assignmentHistory[*].reason")).contains("Handover");
        assertThat(exported.text("$.creditCases[0].title")).isEqualTo("Debt review");
        assertThat(exported.texts("$.creditCases[0].notes[*].note")).containsExactly("Called the client.");
        assertThat(exported.texts("$.accessLog[*].action")).contains("CLIENT_VIEWED");
        assertThat(exported.texts("$.accessLog[?(@.action == 'CLIENT_VIEWED')].by"))
                .containsExactly("thandi@test.local");

        String audit = jdbc.queryForObject("select metadata::text from audit_logs"
                + " where action = 'CLIENT_DATA_EXPORTED' and target_id = ?", String.class, String.valueOf(client));
        assertThat(audit).as("counts only").doesNotContain(idNumber, "Export");
    }

    @Test
    void thePasswordMustBeEnteredAgainFirst() {
        ApiClient.Response refused = export(admin, client);
        ApiClient.Response unknown = export(admin, 999_999);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.text("$.type")).isEqualTo("urn:credit-advisory:auth:reauthentication-required");
        assertThat(unknown.status()).as("same answer whether the client exists or not").isEqualTo(403);
        assertThat(count("select count(*) from audit_logs where action = 'CLIENT_DATA_EXPORTED'")).isZero();
    }

    @Test
    void onlyAnAdmin() {
        confirmPassword(manager);
        confirmPassword(thandi);

        assertThat(export(manager, client).status()).isEqualTo(403);
        assertThat(export(thandi, client).status()).as("not even for their own client").isEqualTo(403);
        assertThat(count("select count(*) from audit_logs where action = 'CLIENT_DATA_EXPORTED'")).isZero();
    }

    // ---------- the file formats ----------

    @Test
    void aPdfToRead() {
        caseWithNote("Debt review", "Called the client.");
        confirmPassword(admin);

        ApiClient.Response pdf = export(admin, client, "pdf");

        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.header("Content-Type")).startsWith("application/pdf");
        assertThat(pdf.header("Content-Disposition"))
                .isEqualTo("attachment; filename=\"client-" + client + "-data.pdf\"");
        assertThat(pdf.header("Cache-Control")).contains("no-store");
        assertThat(pdf.body()).startsWith("%PDF-1.4").contains("%%EOF")
                .contains("(Client data export: Export Person)", "(" + idNumber + ")", "Debt review",
                        "(Called the client.)");
        assertThat(count("select count(*) from audit_logs where action = 'CLIENT_DATA_EXPORTED'"
                + " and metadata ->> 'format' = 'pdf'")).isEqualTo(1);
    }

    @Test
    void aCsvForASpreadsheet() {
        caseWithNote("Debt review", "=HYPERLINK(1)");
        confirmPassword(admin);

        ApiClient.Response csv = export(admin, client, "csv");

        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.header("Content-Type")).startsWith("text/csv");
        assertThat(csv.header("Content-Disposition"))
                .isEqualTo("attachment; filename=\"client-" + client + "-data.csv\"");
        assertThat(csv.header("Cache-Control")).contains("no-store");
        assertThat(csv.body()).startsWith("\uFEFFSection,Record,Field,Value\r\n")
                .contains("Client details,,First name,Export\r\n")
                .contains("Client details,,ID number," + idNumber + "\r\n")
                .as("a note that looks like a formula stays text").contains(",'=HYPERLINK(1)\r\n")
                .doesNotContain(",=HYPERLINK");
        assertThat(count("select count(*) from audit_logs where action = 'CLIENT_DATA_EXPORTED'"
                + " and metadata ->> 'format' = 'csv'")).isEqualTo(1);
    }

    @Test
    void jsonWhenNoFormatIsGiven() {
        confirmPassword(admin);

        ApiClient.Response json = export(admin, client, "json");

        assertThat(json.status()).isEqualTo(200);
        assertThat(json.header("Content-Type")).startsWith("application/json");
        assertThat(json.text("$.client.idNumber")).isEqualTo(idNumber);
        assertThat(count("select count(*) from audit_logs where action = 'CLIENT_DATA_EXPORTED'"
                + " and metadata ->> 'format' = 'json'")).isEqualTo(1);
    }

    @Test
    void anUnknownFormatIsRefused() {
        ApiClient.Response refused = export(admin, client, "xlsx");

        assertThat(refused.status()).as("checked before the password, and nothing is exported").isEqualTo(400);
        assertThat(refused.text("$.type")).isEqualTo("urn:credit-advisory:client:export-format-invalid");
        assertThat(count("select count(*) from audit_logs where action = 'CLIENT_DATA_EXPORTED'")).isZero();
    }

    @Test
    void anUnknownClientIsNotFound() {
        confirmPassword(admin);

        ApiClient.Response missing = export(admin, 999_999);

        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.text("$.type")).isEqualTo("urn:credit-advisory:client:not-found");
    }
}
