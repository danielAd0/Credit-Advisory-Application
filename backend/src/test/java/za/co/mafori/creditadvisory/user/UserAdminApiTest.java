package za.co.mafori.creditadvisory.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/** /api/users: only admins, and the rules that keep accounts consistent. */
class UserAdminApiTest extends ApiTestBase {

    @Test
    void onlyAdminsCanManageUsers() {
        createUser("manager@test.local", null, RoleName.MANAGER);

        assertThat(signIn("manager@test.local").get("/api/users").status()).isEqualTo(403);
        assertThat(signIn(ADMIN_EMAIL).get("/api/users").status()).isEqualTo(200);
    }

    @Test
    void adminCreatesAConsultantLogin() {
        long consultantId = createConsultant("C001", "Thandi Test");
        ApiClient admin = signIn(ADMIN_EMAIL);

        ApiClient.Response created = admin.post("/api/users", """
                {"email":"Thandi@Test.Local","temporaryPassword":"Temp-Password-2026",
                 "roles":["CONSULTANT"],"consultantId":%d}""".formatted(consultantId));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.text("$.email")).isEqualTo("thandi@test.local");
        assertThat(created.number("$.consultant.id")).isEqualTo(consultantId);
        assertThat(created.body()).doesNotContainIgnoringCase("password");
        assertThat(browser().signIn("thandi@test.local", "Temp-Password-2026").status()).isEqualTo(200);
    }

    @Test
    void consultantRoleNeedsAnActiveUnlinkedConsultant() {
        long consultantId = createConsultant("C001", "Thandi Test");
        createUser("thandi@test.local", consultantId, RoleName.CONSULTANT);
        ApiClient admin = signIn(ADMIN_EMAIL);

        ApiClient.Response noLink = admin.post("/api/users", """
                {"email":"a@test.local","temporaryPassword":"Temp-Password-2026","roles":["CONSULTANT"]}""");
        assertThat(noLink.status()).isEqualTo(422);
        assertThat(noLink.text("$.type")).isEqualTo("urn:credit-advisory:user:consultant-role-needs-link");

        ApiClient.Response alreadyLinked = admin.post("/api/users", """
                {"email":"b@test.local","temporaryPassword":"Temp-Password-2026",
                 "roles":["CONSULTANT"],"consultantId":%d}""".formatted(consultantId));
        assertThat(alreadyLinked.status()).isEqualTo(409);
        assertThat(alreadyLinked.text("$.type")).isEqualTo("urn:credit-advisory:user:consultant-already-linked");
    }

    @Test
    void duplicateEmailIsRejected() {
        ApiClient.Response response = signIn(ADMIN_EMAIL).post("/api/users", """
                {"email":"ADMIN@test.local","temporaryPassword":"Temp-Password-2026","roles":["MANAGER"]}""");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:user:duplicate-email");
    }

    @Test
    void weakPasswordIsRejected() {
        ApiClient.Response response = signIn(ADMIN_EMAIL).post("/api/users", """
                {"email":"weak@test.local","temporaryPassword":"weak@test.local","roles":["MANAGER"]}""");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:validation:weak-password");
    }

    @Test
    void theLastAdminCannotBeDisabledOrDemoted() {
        ApiClient admin = signIn(ADMIN_EMAIL);
        long version = userVersion(admin, adminId);

        ApiClient.Response disable = admin.patch("/api/users/" + adminId + "/enabled", """
                {"enabled":false,"version":%d}""".formatted(version));
        ApiClient.Response demote = admin.patch("/api/users/" + adminId + "/roles", """
                {"roles":["MANAGER"],"version":%d}""".formatted(version));

        assertThat(disable.status()).isEqualTo(409);
        assertThat(disable.text("$.type")).isEqualTo("urn:credit-advisory:user:last-admin");
        assertThat(demote.status()).isEqualTo(409);
        assertThat(demote.text("$.type")).isEqualTo("urn:credit-advisory:user:last-admin");
    }

    @Test
    void staleVersionIsRejected() {
        long userId = createUser("manager@test.local", null, RoleName.MANAGER);
        ApiClient admin = signIn(ADMIN_EMAIL);
        long version = userVersion(admin, userId);

        assertThat(admin.patch("/api/users/" + userId + "/roles", """
                {"roles":["MANAGER","ADMIN"],"version":%d}""".formatted(version)).status()).isEqualTo(200);

        ApiClient.Response stale = admin.patch("/api/users/" + userId + "/roles", """
                {"roles":["MANAGER"],"version":%d}""".formatted(version));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.text("$.type")).isEqualTo("urn:credit-advisory:conflict:stale-version");
    }
}
