package za.co.mafori.creditadvisory.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * When an open session must end because an admin (or the user) changed the
 * account.
 */
class SessionEndApiTest extends ApiTestBase {

    private static final String MANAGER_EMAIL = "manager@test.local";

    @Test
    void adminPasswordResetSignsTheUserOutEverywhere() {
        long userId = createUser(MANAGER_EMAIL, null, RoleName.MANAGER);
        ApiClient laptop = signIn(MANAGER_EMAIL);
        ApiClient phone = signIn(MANAGER_EMAIL);

        ApiClient.Response reset = signIn(ADMIN_EMAIL).post("/api/users/" + userId + "/password-reset", """
                {"temporaryPassword":"Reset-Password-2026"}""");
        assertThat(reset.status()).as(reset.body()).isEqualTo(200);

        assertThat(laptop.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(phone.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(browser().signIn(MANAGER_EMAIL, "Reset-Password-2026").status()).isEqualTo(200);
    }

    @Test
    void changingYourOwnPasswordKeepsThisSessionAndEndsTheOthers() {
        createUser(MANAGER_EMAIL, null, RoleName.MANAGER);
        ApiClient laptop = signIn(MANAGER_EMAIL);
        ApiClient phone = signIn(MANAGER_EMAIL);

        ApiClient.Response changed = laptop.post("/api/auth/change-password", """
                {"currentPassword":"%s","newPassword":"Brand-New-Password-1"}""".formatted(PASSWORD));
        assertThat(changed.status()).as(changed.body()).isEqualTo(204);

        assertThat(laptop.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(phone.get("/api/auth/me").status()).isEqualTo(401);
        // The kept session still works for changes, so the CSRF token survived too.
        assertThat(laptop.post("/api/auth/change-password", """
                {"currentPassword":"Brand-New-Password-1","newPassword":"Second-New-Password-2"}""")
                .status()).isEqualTo(204);
    }

    @Test
    void roleChangeSignsTheUserOutSoNewRolesApplyImmediately() {
        long userId = createUser(MANAGER_EMAIL, null, RoleName.MANAGER);
        ApiClient manager = signIn(MANAGER_EMAIL);
        assertThat(manager.get("/api/clients").status()).isEqualTo(200);

        ApiClient admin = signIn(ADMIN_EMAIL);
        long version = userVersion(admin, userId);
        ApiClient.Response changed = admin.patch("/api/users/" + userId + "/roles", """
                {"roles":["MANAGER","ADMIN"],"version":%d}""".formatted(version));
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);

        assertThat(manager.get("/api/clients").status()).isEqualTo(401);
        ApiClient again = signIn(MANAGER_EMAIL);
        assertThat(again.get("/api/auth/me").texts("$.roles")).containsExactlyInAnyOrder("MANAGER", "ADMIN");
    }

    @Test
    void failedSignInsAndUnlockDoNotEndAnOpenSession() {
        long userId = createUser(MANAGER_EMAIL, null, RoleName.MANAGER);
        ApiClient manager = signIn(MANAGER_EMAIL);

        // Someone else hammers the account until it locks.
        for (int attempt = 1; attempt <= 5; attempt++) {
            browser().signIn(MANAGER_EMAIL, "Wrong-Password-000");
        }
        assertThat(manager.get("/api/auth/me").status()).isEqualTo(200);

        assertThat(signIn(ADMIN_EMAIL).post("/api/users/" + userId + "/unlock", null).status()).isEqualTo(200);
        assertThat(manager.get("/api/auth/me").status()).isEqualTo(200);
    }

    @Test
    void anAdminsOwnSessionSurvivesChangesToOtherUsers() {
        long userId = createUser(MANAGER_EMAIL, null, RoleName.MANAGER);
        ApiClient admin = signIn(ADMIN_EMAIL);

        admin.post("/api/users/" + userId + "/password-reset", """
                {"temporaryPassword":"Reset-Password-2026"}""");

        assertThat(admin.get("/api/auth/me").status()).isEqualTo(200);
    }
}
