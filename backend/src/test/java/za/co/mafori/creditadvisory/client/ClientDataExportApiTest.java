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

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
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

    @Test
    void anUnknownClientIsNotFound() {
        confirmPassword(admin);

        ApiClient.Response missing = export(admin, 999_999);

        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.text("$.type")).isEqualTo("urn:credit-advisory:client:not-found");
    }
}
