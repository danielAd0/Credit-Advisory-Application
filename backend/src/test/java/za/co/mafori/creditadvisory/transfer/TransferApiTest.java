package za.co.mafori.creditadvisory.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * POST /api/client-transfers and GET /api/clients/{id}/assignments (FR-13 to
 * FR-17).
 */
class TransferApiTest extends ApiTestBase {

    private long fromId;
    private long toId;
    private long suspendedId;
    private long clientA;
    private long clientB;
    private long clientC;
    private ApiClient manager;

    @BeforeEach
    void createData() {
        fromId = createConsultant("C001", "Thandi Test");
        toId = createConsultant("C002", "Sipho Test");
        suspendedId = createConsultant("C003", "Lerato Test");
        setConsultantStatus(suspendedId, ConsultantStatus.SUSPENDED);

        clientA = createClient("Test", "Alpha", fromId);
        clientB = createClient("Test", "Bravo", fromId);
        clientC = createClient("Test", "Charlie", fromId);

        createUser("manager@test.local", null, RoleName.MANAGER);
        manager = signIn("manager@test.local");
    }

    private ApiClient.Response transfer(long from, long to, String clientIds) {
        return manager.post("/api/client-transfers", """
                {"fromConsultantId":%d,"toConsultantId":%d,"clientIds":[%s],"reason":"Test handover"}"""
                .formatted(from, to, clientIds));
    }

    private List<String> clientsOf(long consultantId) {
        return manager.get("/api/clients?consultantId=" + consultantId).texts("$.items[*].lastName");
    }

    @Test
    void bulkTransferMovesEveryClientAndSharesOneBatchId() {
        ApiClient.Response response = transfer(fromId, toId, clientA + "," + clientB);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.number("$.transferredCount")).isEqualTo(2);
        String batchId = response.text("$.batchId");
        assertThat(batchId).isNotBlank();

        assertThat(clientsOf(toId)).containsExactly("Alpha", "Bravo");
        assertThat(clientsOf(fromId)).containsExactly("Charlie");

        ApiClient.Response history = manager.get("/api/clients/" + clientA + "/assignments");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.size("$")).isEqualTo(1);
        assertThat(history.text("$[0].changeType")).isEqualTo("BULK_TRANSFER");
        assertThat(history.text("$[0].batchId")).isEqualTo(batchId);
        assertThat(history.number("$[0].fromConsultant.id")).isEqualTo(fromId);
        assertThat(history.number("$[0].toConsultant.id")).isEqualTo(toId);
        assertThat(history.text("$[0].changedBy.email")).isEqualTo("manager@test.local");
        assertThat(history.text("$[0].reason")).isEqualTo("Test handover");
        assertThat(manager.get("/api/clients/" + clientB + "/assignments").text("$[0].batchId")).isEqualTo(batchId);
    }

    @Test
    void singleTransferHasNoBatchId() {
        ApiClient.Response response = transfer(fromId, toId, String.valueOf(clientC));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.value("$.batchId")).isNull();
        assertThat(manager.get("/api/clients/" + clientC + "/assignments").text("$[0].changeType"))
                .isEqualTo("TRANSFER");
    }

    @Test
    void nothingMovesWhenOneClientAlreadyChangedHands() {
        assertThat(transfer(fromId, toId, String.valueOf(clientA)).status()).isEqualTo(200);

        ApiClient.Response response = transfer(fromId, toId, clientA + "," + clientB);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:transfer:ownership-changed");
        assertThat(response.text("$.detail")).contains(String.valueOf(clientA));
        // All or nothing: Bravo did not move and has no history.
        assertThat(clientsOf(fromId)).containsExactly("Bravo", "Charlie");
        assertThat(manager.get("/api/clients/" + clientB + "/assignments").size("$")).isZero();
    }

    @Test
    void destinationMustBeActive() {
        ApiClient.Response response = transfer(fromId, suspendedId, String.valueOf(clientA));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:transfer:destination-not-active");
        assertThat(clientsOf(fromId)).containsExactly("Alpha", "Bravo", "Charlie");
    }

    @Test
    void sourceMayBeSuspended() {
        setConsultantStatus(fromId, ConsultantStatus.SUSPENDED);

        assertThat(transfer(fromId, toId, String.valueOf(clientA)).status()).isEqualTo(200);
    }

    @Test
    void badRequestsAreRejected() {
        ApiClient.Response same = transfer(fromId, fromId, String.valueOf(clientA));
        assertThat(same.status()).isEqualTo(400);
        assertThat(same.text("$.type")).isEqualTo("urn:credit-advisory:transfer:same-consultant");

        ApiClient.Response duplicates = transfer(fromId, toId, clientA + "," + clientA);
        assertThat(duplicates.status()).isEqualTo(400);
        assertThat(duplicates.text("$.type")).isEqualTo("urn:credit-advisory:transfer:duplicate-clients");

        ApiClient.Response empty = transfer(fromId, toId, "");
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.text("$.type")).isEqualTo("urn:credit-advisory:validation:invalid-request");

        ApiClient.Response missingClient = transfer(fromId, toId, "999999");
        assertThat(missingClient.status()).isEqualTo(404);
        assertThat(missingClient.text("$.type")).isEqualTo("urn:credit-advisory:transfer:client-not-found");

        ApiClient.Response missingConsultant = transfer(fromId, 999999, String.valueOf(clientA));
        assertThat(missingConsultant.status()).isEqualTo(404);

        assertThat(clientsOf(fromId)).containsExactly("Alpha", "Bravo", "Charlie");
    }

    @Test
    void consultantCannotTransferOrSeeHistory() {
        createUser("thandi@test.local", fromId, RoleName.CONSULTANT);
        ApiClient thandi = signIn("thandi@test.local");

        ApiClient.Response transfer = thandi.post("/api/client-transfers", """
                {"fromConsultantId":%d,"toConsultantId":%d,"clientIds":[%d],"reason":"Test"}"""
                .formatted(fromId, toId, clientA));

        assertThat(transfer.status()).isEqualTo(403);
        assertThat(thandi.get("/api/clients/" + clientA + "/assignments").status()).isEqualTo(403);
        assertThat(clientsOf(fromId)).containsExactly("Alpha", "Bravo", "Charlie");
    }
}
