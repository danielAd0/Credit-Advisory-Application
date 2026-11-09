package za.co.mafori.creditadvisory.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * POST /api/clients/{id}/unassign - a client keeps all their data but loses
 * their consultant.
 */
class ClientUnassignApiTest extends ApiTestBase {

    private long thandiId;
    private long clientId;
    private ApiClient admin;

    @BeforeEach
    void createData() {
        thandiId = createConsultant("C001", "Thandi Test");
        clientId = createClient("Test", "Alpha", thandiId);
        admin = signIn(ADMIN_EMAIL);
    }

    private ApiClient.Response unassign(ApiClient as, long id, String reason) {
        return as.post("/api/clients/" + id + "/unassign", """
                {"reason":"%s"}""".formatted(reason));
    }

    private long unassignedCount() {
        return admin.get("/api/clients?unassigned=true").number("$.totalItems");
    }

    private long auditCount() {
        return jdbc.queryForObject("select count(*) from audit_logs where action = 'CLIENT_UNASSIGNED'",
                Long.class);
    }

    @Test
    void adminUnassignsAClientAndHistoryAndAuditAreWritten() {
        ApiClient.Response response = unassign(admin, clientId, "  Client asked for a new consultant  ");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.number("$.clientId")).isEqualTo(clientId);
        assertThat(response.number("$.fromConsultant.id")).isEqualTo(thandiId);
        assertThat(response.text("$.fromConsultant.consultantCode")).isEqualTo("C001");

        // Still in the system, just with nobody assigned.
        ApiClient.Response client = admin.get("/api/clients/" + clientId);
        assertThat(client.status()).isEqualTo(200);
        assertThat(client.value("$.consultant")).isNull();
        assertThat(unassignedCount()).isEqualTo(1);

        ApiClient.Response history = admin.get("/api/clients/" + clientId + "/assignments");
        assertThat(history.text("$[0].changeType")).isEqualTo("UNASSIGN");
        assertThat(history.number("$[0].fromConsultant.id")).isEqualTo(thandiId);
        assertThat(history.value("$[0].toConsultant")).isNull();
        assertThat(history.text("$[0].reason")).isEqualTo("Client asked for a new consultant");
        assertThat(history.text("$[0].changedBy.email")).isEqualTo(ADMIN_EMAIL);

        assertThat(auditCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select metadata ->> 'fromConsultantId' from audit_logs where action = 'CLIENT_UNASSIGNED'
                """, String.class)).isEqualTo(String.valueOf(thandiId));
    }

    @Test
    void theConsultantNoLongerSeesTheClient() {
        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        ApiClient thandi = signIn("thandi@test.local");
        assertThat(thandi.get("/api/clients/" + clientId).status()).isEqualTo(200);

        assertThat(unassign(admin, clientId, "Moved away").status()).isEqualTo(200);

        assertThat(thandi.get("/api/clients/" + clientId).status()).isEqualTo(404);
    }

    @Test
    void clientWithACaseThatIsNotClosedCannotBeUnassigned() {
        ApiClient.Response created = admin.post("/api/cases", """
                {"clientId":%d,"title":"Debt review application"}""".formatted(clientId));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        long caseId = created.number("$.id");

        // OPEN
        ApiClient.Response refused = unassign(admin, clientId, "Moved away");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.text("$.type")).isEqualTo("urn:credit-advisory:unassign:open-cases");

        // IN_PROGRESS
        ApiClient.Response inProgress = admin.patch("/api/cases/" + caseId, """
                {"title":"Debt review application","status":"IN_PROGRESS","version":%d}"""
                .formatted(created.number("$.version")));
        assertThat(inProgress.status()).as(inProgress.body()).isEqualTo(200);
        assertThat(unassign(admin, clientId, "Moved away").status()).isEqualTo(409);

        // Nothing changed while refused.
        assertThat(admin.get("/api/clients/" + clientId).number("$.consultant.id")).isEqualTo(thandiId);
        assertThat(auditCount()).isZero();

        // CLOSED: now it is allowed.
        ApiClient.Response closed = admin.patch("/api/cases/" + caseId, """
                {"title":"Debt review application","status":"CLOSED","version":%d}"""
                .formatted(inProgress.number("$.version")));
        assertThat(closed.status()).as(closed.body()).isEqualTo(200);
        assertThat(unassign(admin, clientId, "Moved away").status()).isEqualTo(200);
    }

    @Test
    void clientOfASuspendedConsultantCanStillBeUnassigned() {
        setConsultantStatus(thandiId, ConsultantStatus.SUSPENDED);

        assertThat(unassign(admin, clientId, "Thandi is suspended").status()).isEqualTo(200);
        assertThat(unassignedCount()).isEqualTo(1);
    }

    @Test
    void alreadyUnassignedClientIs409() {
        long nobodys = createClient("Test", "Bravo", null);

        ApiClient.Response response = unassign(admin, nobodys, "Moved away");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:unassign:not-assigned");
    }

    @Test
    void unknownClientIs404() {
        assertThat(unassign(admin, 999999, "Moved away").status()).isEqualTo(404);
    }

    @Test
    void blankReasonIs400() {
        assertThat(unassign(admin, clientId, "   ").status()).isEqualTo(400);
        assertThat(admin.get("/api/clients/" + clientId).number("$.consultant.id")).isEqualTo(thandiId);
    }

    @Test
    void managerMayUnassignButConsultantMayNotEvenTheirOwnClient() {
        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);

        assertThat(unassign(signIn("thandi@test.local"), clientId, "Moved away").status()).isEqualTo(403);
        assertThat(unassign(signIn("manager@test.local"), clientId, "Moved away").status()).isEqualTo(200);
    }
}
