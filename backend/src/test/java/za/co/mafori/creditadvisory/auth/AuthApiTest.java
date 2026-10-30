package za.co.mafori.creditadvisory.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

class AuthApiTest extends ApiTestBase {

    @Test
    void signInReturnsTheCurrentUser() {
        ApiClient admin = signIn(ADMIN_EMAIL);

        ApiClient.Response me = admin.get("/api/auth/me");

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.text("$.email")).isEqualTo(ADMIN_EMAIL);
        assertThat(me.texts("$.roles")).containsExactly("ADMIN");
        assertThat(me.body()).doesNotContainIgnoringCase("password");
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() {
        ApiClient.Response wrongPassword = browser().signIn(ADMIN_EMAIL, "Wrong-Password-000");
        ApiClient.Response unknownEmail = browser().signIn("nobody@test.local", PASSWORD);

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(wrongPassword.text("$.type")).isEqualTo("urn:credit-advisory:auth:sign-in-failed");
        assertThat(unknownEmail.text("$.type")).isEqualTo("urn:credit-advisory:auth:sign-in-failed");
        assertThat(wrongPassword.text("$.detail")).isEqualTo(unknownEmail.text("$.detail"));
    }

    @Test
    void protectedEndpointsNeedSignIn() {
        assertThat(browser().get("/api/clients").status()).isEqualTo(401);
        assertThat(browser().get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void postWithoutCsrfHeaderIsRejected() {
        ApiClient admin = signIn(ADMIN_EMAIL);
        admin.setSendCsrfHeader(false);

        ApiClient.Response response = admin.post("/api/consultants", """
                {"consultantCode":"C001","fullName":"Test Consultant"}""");

        assertThat(response.status()).isEqualTo(403);
    }

    @Test
    void fiveWrongPasswordsLockTheAccountUntilAnAdminUnlocksIt() {
        long userId = createUser("locked@test.local", null, RoleName.MANAGER);

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(browser().signIn("locked@test.local", "Wrong-Password-000").status()).isEqualTo(401);
        }
        // Locked: even the right password is refused now.
        assertThat(browser().signIn("locked@test.local", PASSWORD).status()).isEqualTo(401);

        ApiClient admin = signIn(ADMIN_EMAIL);
        assertThat(admin.get("/api/users/" + userId).value("$.locked")).isEqualTo(true);
        assertThat(admin.post("/api/users/" + userId + "/unlock", null).status()).isEqualTo(200);

        assertThat(browser().signIn("locked@test.local", PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void disabledUserIsSignedOutOnTheirNextRequest() {
        long userId = createUser("manager@test.local", null, RoleName.MANAGER);
        ApiClient manager = signIn("manager@test.local");
        assertThat(manager.get("/api/auth/me").status()).isEqualTo(200);

        ApiClient admin = signIn(ADMIN_EMAIL);
        ApiClient.Response disabled = admin.patch("/api/users/" + userId + "/enabled", """
                {"enabled":false,"version":%d}""".formatted(userVersion(admin, userId)));
        assertThat(disabled.status()).isEqualTo(200);

        assertThat(manager.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(browser().signIn("manager@test.local", PASSWORD).status()).isEqualTo(401);
    }

    @Test
    void consultantIsSignedOutWhenTheirConsultantRecordIsSuspended() {
        long consultantId = createConsultant("C001", "Thandi Test");
        createUser("thandi@test.local", consultantId, RoleName.CONSULTANT);
        ApiClient thandi = signIn("thandi@test.local");
        assertThat(thandi.get("/api/clients").status()).isEqualTo(200);

        setConsultantStatus(consultantId, ConsultantStatus.SUSPENDED);

        assertThat(thandi.get("/api/clients").status()).isEqualTo(401);
    }

    @Test
    void userCanChangeTheirOwnPassword() {
        createUser("manager@test.local", null, RoleName.MANAGER);
        ApiClient manager = signIn("manager@test.local");

        ApiClient.Response wrongCurrent = manager.post("/api/auth/change-password", """
                {"currentPassword":"Wrong-Password-000","newPassword":"Brand-New-Password-1"}""");
        assertThat(wrongCurrent.status()).isEqualTo(400);
        assertThat(wrongCurrent.text("$.type")).isEqualTo("urn:credit-advisory:auth:wrong-current-password");

        ApiClient.Response changed = manager.post("/api/auth/change-password", """
                {"currentPassword":"%s","newPassword":"Brand-New-Password-1"}""".formatted(PASSWORD));
        assertThat(changed.status()).isEqualTo(204);

        assertThat(browser().signIn("manager@test.local", PASSWORD).status()).isEqualTo(401);
        assertThat(browser().signIn("manager@test.local", "Brand-New-Password-1").status()).isEqualTo(200);
    }
}
