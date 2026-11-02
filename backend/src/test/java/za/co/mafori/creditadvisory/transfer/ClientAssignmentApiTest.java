package za.co.mafori.creditadvisory.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/** POST /api/client-assignments - giving unassigned clients to a consultant. */
class ClientAssignmentApiTest extends ApiTestBase {

    private long consultantId;
    private long otherConsultantId;
    private long suspendedId;
    private long unassignedA;
    private long unassignedB;
    private long assignedC;
    private ApiClient admin;

    @BeforeEach
    void createData() {
        consultantId = createConsultant("C001", "Thandi Test");
        otherConsultantId = createConsultant("C002", "Sipho Test");
        suspendedId = createConsultant("C003", "Lerato Test");
        setConsultantStatus(suspendedId, ConsultantStatus.SUSPENDED);

        unassignedA = createClient("Test", "Alpha", null);
        unassignedB = createClient("Test", "Bravo", null);
        assignedC = createClient("Test", "Charlie", otherConsultantId);

        admin = signIn(ADMIN_EMAIL);
    }

    private ApiClient.Response assign(ApiClient as, long to, String clientIds) {
        return as.post("/api/client-assignments", """
                {"consultantId":%d,"clientIds":[%s],"reason":"New client allocation"}""".formatted(to, clientIds));
    }

    private List<String> clientsOf(long id) {
        return admin.get("/api/clients?consultantId=" + id).texts("$.items[*].lastName");
    }

    @Test
    void adminAssignsUnassignedClientsAndHistoryIsWritten() {
        ApiClient.Response response = assign(admin, consultantId, unassignedA + "," + unassignedB);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.number("$.assignedCount")).isEqualTo(2);
        assertThat(response.text("$.batchId")).isNotBlank();
        assertThat(clientsOf(consultantId)).containsExactly("Alpha", "Bravo");
        assertThat(admin.get("/api/clients?unassigned=true").number("$.totalItems")).isZero();

        ApiClient.Response history = admin.get("/api/clients/" + unassignedA + "/assignments");
        assertThat(history.size("$")).isEqualTo(1);
        assertThat(history.text("$[0].changeType")).isEqualTo("MANUAL");
        assertThat(history.value("$[0].fromConsultant")).isNull();
        assertThat(history.number("$[0].toConsultant.id")).isEqualTo(consultantId);
        assertThat(history.text("$[0].reason")).isEqualTo("New client allocation");
        assertThat(history.text("$[0].changedBy.email")).isEqualTo(ADMIN_EMAIL);

        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'CLIENTS_ASSIGNED'",
                Long.class)).isEqualTo(1);
    }

    @Test
    void singleAssignmentHasNoBatchId() {
        ApiClient.Response response = assign(admin, consultantId, String.valueOf(unassignedA));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.value("$.batchId")).isNull();
    }

    @Test
    void alreadyAssignedClientRejectsTheWholeRequest() {
        ApiClient.Response response = assign(admin, consultantId, unassignedA + "," + assignedC);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:assignment:already-assigned");
        assertThat(clientsOf(consultantId)).isEmpty();
        assertThat(clientsOf(otherConsultantId)).containsExactly("Charlie");
    }

    @Test
    void cannotAssignToSuspendedConsultant() {
        assertThat(assign(admin, suspendedId, String.valueOf(unassignedA)).status()).isEqualTo(422);
    }

    @Test
    void unknownClientIsNotFound() {
        assertThat(assign(admin, consultantId, "999999").status()).isEqualTo(404);
    }

    @Test
    void managerMayAssignButConsultantMayNot() {
        createUser("manager@test.local", null, RoleName.MANAGER);
        createUser("consultant@test.local", consultantId, RoleName.CONSULTANT);

        assertThat(assign(signIn("consultant@test.local"), consultantId, String.valueOf(unassignedA)).status())
                .isEqualTo(403);
        assertThat(assign(signIn("manager@test.local"), consultantId, String.valueOf(unassignedA)).status())
                .isEqualTo(200);
    }
}
