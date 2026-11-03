package za.co.mafori.creditadvisory.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/** GET /api/dashboard/summary: counts from the database, scoped by role. */
class DashboardApiTest extends ApiTestBase {

    private long thandiId;
    private long siphoId;
    private long leratoId;
    private long leratoClientA;
    private long leratoClientB;

    @BeforeEach
    void createData() {
        thandiId = createConsultant("C001", "Thandi Test");
        siphoId = createConsultant("C002", "Sipho Test");
        leratoId = createConsultant("C003", "Lerato Test");
        long suspendedId = createConsultant("C004", "Bongani Test");

        createClient("Test", "Alpha", thandiId);
        createClient("Test", "Bravo", thandiId);
        createClient("Test", "Charlie", thandiId);
        createClient("Test", "Delta", siphoId);
        leratoClientA = createClient("Test", "Echo", leratoId);
        leratoClientB = createClient("Test", "Foxtrot", leratoId);
        createClient("Test", "Golf", null);
        createClient("Test", "Hotel", null);

        // Lerato left with 2 clients still assigned to her; Bongani is suspended with
        // none.
        setConsultantStatus(leratoId, ConsultantStatus.INACTIVE);
        setConsultantStatus(suspendedId, ConsultantStatus.SUSPENDED);

        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
    }

    @Test
    void adminSeesTheWholePicture() {
        ApiClient.Response summary = signIn(ADMIN_EMAIL).get("/api/dashboard/summary");

        assertThat(summary.status()).as(summary.body()).isEqualTo(200);
        assertThat(summary.text("$.scope")).isEqualTo("ALL");
        assertThat(summary.number("$.totalClients")).isEqualTo(8);
        assertThat(summary.number("$.unassignedClients")).isEqualTo(2);
        assertThat(summary.number("$.clientsNeedingHandover")).isEqualTo(2);
        assertThat(summary.number("$.consultants.active")).isEqualTo(2);
        assertThat(summary.number("$.consultants.inactive")).isEqualTo(1);
        assertThat(summary.number("$.consultants.suspended")).isEqualTo(1);
    }

    @Test
    void managerSeesTheSameNumbers() {
        ApiClient.Response summary = signIn("manager@test.local").get("/api/dashboard/summary");

        assertThat(summary.text("$.scope")).isEqualTo("ALL");
        assertThat(summary.number("$.totalClients")).isEqualTo(8);
        assertThat(summary.number("$.clientsNeedingHandover")).isEqualTo(2);
    }

    @Test
    void consultantOnlySeesTheirOwnCount() {
        ApiClient.Response summary = signIn("thandi@test.local").get("/api/dashboard/summary");

        assertThat(summary.status()).isEqualTo(200);
        assertThat(summary.text("$.scope")).isEqualTo("OWN");
        assertThat(summary.number("$.totalClients")).isEqualTo(3);
        assertThat(summary.value("$.unassignedClients")).isNull();
        assertThat(summary.value("$.clientsNeedingHandover")).isNull();
        assertThat(summary.value("$.consultants")).isNull();
    }

    @Test
    void consultantRoleWithoutAConsultantRecordSeesZero() {
        createUser("unlinked@test.local", null, RoleName.CONSULTANT);

        ApiClient.Response summary = signIn("unlinked@test.local").get("/api/dashboard/summary");

        assertThat(summary.text("$.scope")).isEqualTo("OWN");
        assertThat(summary.number("$.totalClients")).isZero();
        assertThat(summary.number("$.cases.open")).isZero();
    }

    /**
     * A case on the client with this last name, inserted directly (clients come
     * from createData).
     */
    private void insertCase(String clientLastName, String status) {
        long clientId = jdbc.queryForObject("select id from clients where last_name = ?", Long.class,
                clientLastName);
        // now() is the same for opened_at and closed_at in one statement, so closed_at
        // >= opened_at.
        jdbc.update("""
                insert into credit_cases (client_id, title, status, closed_at, created_by_user_id)
                values (?, 'Test case', ?, case when ?::text = 'CLOSED' then now() end, ?)
                """, clientId, status, status, adminId);
    }

    @Test
    void casesAreCountedPerStatusAndScopedLikeClients() {
        insertCase("Alpha", "OPEN"); // Thandi
        insertCase("Bravo", "IN_PROGRESS"); // Thandi
        insertCase("Charlie", "CLOSED"); // Thandi
        insertCase("Delta", "OPEN"); // Sipho
        insertCase("Golf", "OPEN"); // unassigned

        ApiClient.Response all = signIn("manager@test.local").get("/api/dashboard/summary");
        assertThat(all.number("$.cases.open")).isEqualTo(3);
        assertThat(all.number("$.cases.inProgress")).isEqualTo(1);
        assertThat(all.number("$.cases.closed")).isEqualTo(1);

        ApiClient.Response own = signIn("thandi@test.local").get("/api/dashboard/summary");
        assertThat(own.number("$.cases.open")).isEqualTo(1);
        assertThat(own.number("$.cases.inProgress")).isEqualTo(1);
        assertThat(own.number("$.cases.closed")).isEqualTo(1);
    }

    @Test
    void handoverQueueEmptiesAfterTransfer() {
        ApiClient admin = signIn(ADMIN_EMAIL);

        ApiClient.Response transfer = admin.post("/api/client-transfers", """
                {"fromConsultantId":%d,"toConsultantId":%d,"clientIds":[%d,%d],"reason":"Lerato left"}"""
                .formatted(leratoId, siphoId, leratoClientA, leratoClientB));
        assertThat(transfer.status()).as(transfer.body()).isEqualTo(200);

        ApiClient.Response summary = admin.get("/api/dashboard/summary");
        assertThat(summary.number("$.clientsNeedingHandover")).isZero();
        assertThat(summary.number("$.totalClients")).isEqualTo(8);
    }

    @Test
    void needsSignIn() {
        assertThat(browser().get("/api/dashboard/summary").status()).isEqualTo(401);
    }
}
