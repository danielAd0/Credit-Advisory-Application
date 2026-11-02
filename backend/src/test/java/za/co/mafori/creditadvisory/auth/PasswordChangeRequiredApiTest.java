package za.co.mafori.creditadvisory.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * A password set by an admin must be replaced by the user before anything else.
 */
class PasswordChangeRequiredApiTest extends ApiTestBase {

    private static final String TEMP_PASSWORD = "Temp-Password-2026";
    private static final String OWN_PASSWORD = "My-Own-Password-2026";
    private static final String REQUIRED = "urn:credit-advisory:auth:password-change-required";

    private ApiClient admin;

    @BeforeEach
    void signInAdmin() {
        admin = signIn(ADMIN_EMAIL);
    }

    private long createUserAsAdmin(String email) {
        ApiClient.Response created = admin.post("/api/users", """
                {"email":"%s","temporaryPassword":"%s","roles":["MANAGER"]}""".formatted(email, TEMP_PASSWORD));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.value("$.mustChangePassword")).isEqualTo(true);
        return created.number("$.id");
    }

    private ApiClient.Response changePassword(ApiClient as, String current, String next) {
        return as.post("/api/auth/change-password", """
                {"currentPassword":"%s","newPassword":"%s"}""".formatted(current, next));
    }

    @Test
    void aNewUserMustChooseTheirOwnPasswordFirst() {
        createUserAsAdmin("manager@test.local");
        ApiClient manager = browser();

        ApiClient.Response login = manager.signIn("manager@test.local", TEMP_PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.value("$.mustChangePassword")).isEqualTo(true);

        // Everything else is refused, with a type the frontend can recognise.
        ApiClient.Response blocked = manager.get("/api/clients");
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.text("$.type")).isEqualTo(REQUIRED);
        assertThat(manager.get("/api/dashboard/summary").status()).isEqualTo(403);
        assertThat(manager.get("/api/auth/me").status()).isEqualTo(200);

        assertThat(changePassword(manager, TEMP_PASSWORD, OWN_PASSWORD).status()).isEqualTo(204);

        // Same session, now free to work.
        assertThat(manager.get("/api/auth/me").value("$.mustChangePassword")).isEqualTo(false);
        assertThat(manager.get("/api/clients").status()).isEqualTo(200);
    }

    @Test
    void choosingTheSamePasswordAgainDoesNotCount() {
        createUserAsAdmin("manager@test.local");
        ApiClient manager = browser();
        manager.signIn("manager@test.local", TEMP_PASSWORD);

        assertThat(changePassword(manager, TEMP_PASSWORD, TEMP_PASSWORD).status()).isEqualTo(400);
        assertThat(manager.get("/api/clients").status()).isEqualTo(403);
    }

    @Test
    void anAdminResetForcesAChangeAgain() {
        long userId = createUser("manager@test.local", null, RoleName.MANAGER);
        assertThat(signIn("manager@test.local").get("/api/clients").status()).isEqualTo(200);

        admin.post("/api/users/" + userId + "/password-reset", """
                {"temporaryPassword":"Reset-Password-2026"}""");
        assertThat(admin.get("/api/users/" + userId).value("$.mustChangePassword")).isEqualTo(true);

        ApiClient manager = browser();
        manager.signIn("manager@test.local", "Reset-Password-2026");
        assertThat(manager.get("/api/clients").text("$.type")).isEqualTo(REQUIRED);

        changePassword(manager, "Reset-Password-2026", OWN_PASSWORD);
        assertThat(admin.get("/api/users/" + userId).value("$.mustChangePassword")).isEqualTo(false);
    }

    @Test
    void aUserWhoMustChangeTheirPasswordCanStillSignOut() {
        createUserAsAdmin("manager@test.local");
        ApiClient manager = browser();
        manager.signIn("manager@test.local", TEMP_PASSWORD);

        assertThat(manager.post("/api/auth/logout", null).status()).isEqualTo(204);
        assertThat(manager.get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void usersWhoChoseTheirOwnPasswordAreNotAffected() {
        createUser("manager@test.local", null, RoleName.MANAGER);
        ApiClient manager = signIn("manager@test.local");

        assertThat(manager.get("/api/auth/me").value("$.mustChangePassword")).isEqualTo(false);
        assertThat(manager.get("/api/clients").status()).isEqualTo(200);
    }
}
