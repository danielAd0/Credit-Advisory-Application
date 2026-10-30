package za.co.mafori.creditadvisory.consultant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/** /api/consultants: clientCount, used by the frontend to plan handovers. */
class ConsultantApiTest extends ApiTestBase {

    private long thandiId;
    private long siphoId;
    private long leratoId;
    private long clientA;
    private long clientB;

    @BeforeEach
    void createData() {
        thandiId = createConsultant("C001", "Thandi Test");
        siphoId = createConsultant("C002", "Sipho Test");
        leratoId = createConsultant("C003", "Lerato Test");

        clientA = createClient("Test", "Alpha", thandiId);
        clientB = createClient("Test", "Bravo", thandiId);
        createClient("Test", "Charlie", thandiId);
        createClient("Test", "Delta", siphoId);
        createClient("Test", "Echo", null);
    }

    private long clientCountOf(ApiClient api, long consultantId) {
        return api.get("/api/consultants/" + consultantId).number("$.clientCount");
    }

    @Test
    void listShowsHowManyClientsEachConsultantHas() {
        ApiClient.Response page = signIn(ADMIN_EMAIL).get("/api/consultants");

        assertThat(page.status()).isEqualTo(200);
        // Sorted by name: Lerato, Sipho, Thandi. The unassigned client counts for
        // nobody.
        assertThat(page.texts("$.items[*].consultantCode")).containsExactly("C003", "C002", "C001");
        assertThat(page.numbers("$.items[*].clientCount")).containsExactly(0L, 1L, 3L);
    }

    @Test
    void singleConsultantHasAClientCount() {
        ApiClient admin = signIn(ADMIN_EMAIL);

        assertThat(clientCountOf(admin, thandiId)).isEqualTo(3);
        assertThat(clientCountOf(admin, leratoId)).isZero();
    }

    @Test
    void newConsultantStartsWithNoClients() {
        ApiClient.Response created = signIn(ADMIN_EMAIL).post("/api/consultants", """
                {"consultantCode":"C009","fullName":"New Test"}""");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.number("$.clientCount")).isZero();
    }

    @Test
    void countsFollowTransfers() {
        ApiClient admin = signIn(ADMIN_EMAIL);

        ApiClient.Response transfer = admin.post("/api/client-transfers", """
                {"fromConsultantId":%d,"toConsultantId":%d,"clientIds":[%d,%d],"reason":"Test handover"}"""
                .formatted(thandiId, siphoId, clientA, clientB));
        assertThat(transfer.status()).as(transfer.body()).isEqualTo(200);

        assertThat(clientCountOf(admin, thandiId)).isEqualTo(1);
        assertThat(clientCountOf(admin, siphoId)).isEqualTo(3);
    }

    @Test
    void statusChangeReportsClientsStillToHandOver() {
        ApiClient admin = signIn(ADMIN_EMAIL);
        long version = admin.get("/api/consultants/" + thandiId).number("$.version");

        ApiClient.Response response = admin.patch("/api/consultants/" + thandiId + "/status", """
                {"status":"INACTIVE","reason":"Left the company","version":%d}""".formatted(version));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.text("$.status")).isEqualTo("INACTIVE");
        // Clients are not moved automatically; the frontend uses this to prompt a
        // handover.
        assertThat(response.number("$.clientCount")).isEqualTo(3);
    }
}
