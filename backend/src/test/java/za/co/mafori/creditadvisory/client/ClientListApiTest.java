package za.co.mafori.creditadvisory.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/** GET /api/clients: who sees which clients (FR-02) and the filters. */
class ClientListApiTest extends ApiTestBase {

    private long thandiConsultantId;
    private long siphoConsultantId;

    @BeforeEach
    void createClients() {
        thandiConsultantId = createConsultant("C001", "Thandi Test");
        siphoConsultantId = createConsultant("C002", "Sipho Test");
        createUser("thandi@test.local", thandiConsultantId, RoleName.CONSULTANT);

        createClient("Test", "Charlie", thandiConsultantId);
        createClient("Test", "Alpha", thandiConsultantId);
        createClient("Test", "Bravo", thandiConsultantId);
        createClient("Test", "Delta", siphoConsultantId);
        createClient("Test", "Echo", siphoConsultantId);
        createClient("Test", "Foxtrot", null);
    }

    @Test
    void adminSeesEveryClientSortedByName() {
        ApiClient.Response page = signIn(ADMIN_EMAIL).get("/api/clients");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.number("$.totalItems")).isEqualTo(6);
        assertThat(page.texts("$.items[*].lastName"))
                .containsExactly("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot");
    }

    @Test
    void listNeverContainsTheFullIdNumber() {
        ApiClient.Response page = signIn(ADMIN_EMAIL).get("/api/clients");

        assertThat(page.texts("$.items[*].idNumberMasked")).allMatch(masked -> masked.matches("\\*{9}[0-9]{4}"));
        assertThat(page.body()).doesNotContain("idNumber\"").doesNotContain("idNumberHash")
                .doesNotContain("idNumberEncrypted");
    }

    @Test
    void adminCanFilterByConsultantOrUnassigned() {
        ApiClient admin = signIn(ADMIN_EMAIL);

        assertThat(admin.get("/api/clients?consultantId=" + siphoConsultantId).texts("$.items[*].lastName"))
                .containsExactly("Delta", "Echo");
        assertThat(admin.get("/api/clients?unassigned=true").texts("$.items[*].lastName"))
                .containsExactly("Foxtrot");
    }

    @Test
    void unassignedCannotBeCombinedWithAConsultantFilter() {
        ApiClient.Response response = signIn(ADMIN_EMAIL)
                .get("/api/clients?unassigned=true&consultantId=" + thandiConsultantId);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:validation:invalid-filter");
    }

    @Test
    void searchMatchesFirstLastOrFullName() {
        ApiClient admin = signIn(ADMIN_EMAIL);

        assertThat(admin.get("/api/clients?search=brav").texts("$.items[*].lastName")).containsExactly("Bravo");
        assertThat(admin.get("/api/clients?search=TEST%20ECHO").texts("$.items[*].lastName")).containsExactly("Echo");
    }

    @Test
    void consultantOnlySeesTheirOwnClients() {
        ApiClient.Response page = signIn("thandi@test.local").get("/api/clients");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.texts("$.items[*].lastName")).containsExactly("Alpha", "Bravo", "Charlie");
        assertThat(page.numbers("$.items[*].consultant.id")).containsOnly(thandiConsultantId);
    }

    @Test
    void consultantCannotWidenTheirScopeWithFilters() {
        ApiClient thandi = signIn("thandi@test.local");

        assertThat(thandi.get("/api/clients?consultantId=" + siphoConsultantId).texts("$.items[*].lastName"))
                .containsExactly("Alpha", "Bravo", "Charlie");
        assertThat(thandi.get("/api/clients?unassigned=true").texts("$.items[*].lastName"))
                .containsExactly("Alpha", "Bravo", "Charlie");
        assertThat(thandi.get("/api/clients?search=delta").number("$.totalItems")).isZero();
    }
}
