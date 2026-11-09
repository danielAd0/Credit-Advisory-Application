package za.co.mafori.creditadvisory.employer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/** /api/employers - seeded from the Mafori Employer Schedule by V10. */
class EmployerApiTest extends ApiTestBase {

    private ApiClient admin;

    @BeforeEach
    void signInAdmin() {
        admin = signIn(ADMIN_EMAIL);
    }

    @Test
    void scheduleEmployersAreSeededOnce() {
        ApiClient.Response all = admin.get("/api/employers?size=100");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.number("$.totalItems")).isEqualTo(63);

        ApiClient.Response tongaat = admin.get("/api/employers?search=tongaat");
        assertThat(tongaat.texts("$.items[*].name")).containsExactly("TONGAAT HULETT STARCH");
        assertThat(tongaat.text("$.items[0].company")).isEqualTo("Mafori");
        assertThat(tongaat.text("$.items[0].employerType")).isEqualTo("Payroll");
    }

    @Test
    void adminCreatesAndRenamesEmployer() {
        ApiClient.Response created = admin.post("/api/employers", """
                {"name":"  NEW   TEST  EMPLOYER ","company":"Mafori","employerType":"Payroll"}""");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.text("$.name")).isEqualTo("NEW TEST EMPLOYER");

        long id = created.number("$.id");
        ApiClient.Response renamed = admin.patch("/api/employers/" + id, """
                {"name":"RENAMED TEST EMPLOYER","company":"Mafori","employerType":"Payroll","version":%d}"""
                .formatted(created.number("$.version")));
        assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
        assertThat(renamed.text("$.name")).isEqualTo("RENAMED TEST EMPLOYER");
    }

    @Test
    void duplicateNameIsRejectedWhateverTheCase() {
        ApiClient.Response response = admin.post("/api/employers", """
                {"name":" afrit "}""");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:employer:duplicate-name");
    }

    @Test
    void managerCanReadButNotChange() {
        createUser("manager@test.local", null, RoleName.MANAGER);
        ApiClient manager = signIn("manager@test.local");

        assertThat(manager.get("/api/employers").status()).isEqualTo(200);
        assertThat(manager.post("/api/employers", """
                {"name":"NOT ALLOWED"}""").status()).isEqualTo(403);
    }
}
