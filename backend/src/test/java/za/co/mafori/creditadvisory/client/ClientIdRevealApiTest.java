package za.co.mafori.creditadvisory.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * Showing a full ID number (GET /api/clients/{id}?include=idNumber) needs the
 * password re-entered in the last few minutes (POST /api/auth/reauthenticate)
 * and is capped per user per hour. The 5-minute expiry and the hour rolling
 * over are tested with a fake clock in RecentPasswordConfirmationTest and
 * IdRevealLimiterTest.
 */
class IdRevealApiTest extends ApiTestBase {

    /** app.auth.max-id-reveals-per-hour in application.properties. */
    private static final int LIMIT = 20;

    private long thandiId;
    private long clientId;
    private ApiClient thandi;

    @BeforeEach
    void createData() {
        thandiId = createConsultant("C001", "Thandi Test");
        clientId = createClient("Test", "Alpha", thandiId);
        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
        thandi = signIn("thandi@test.local");
    }

    private ApiClient.Response reveal(ApiClient as, long id) {
        return as.get("/api/clients/" + id + "?include=idNumber");
    }

    private ApiClient.Response reauthenticate(ApiClient as, String password) {
        return as.post("/api/auth/reauthenticate", """
                {"password":"%s"}""".formatted(password));
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ?", Long.class, action);
    }

    // ---------- password first ----------

    @Test
    void fullIdNumberNeedsThePasswordFirst() {
        ApiClient.Response refused = reveal(thandi, clientId);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.text("$.type")).isEqualTo("urn:credit-advisory:auth:reauthentication-required");
        assertThat(refused.body()).doesNotContain(fakeIdNumber(1));
        assertThat(auditCount("CLIENT_VIEWED")).isZero();

        // The normal view never needs it.
        assertThat(thandi.get("/api/clients/" + clientId).status()).isEqualTo(200);
    }

    @Test
    void afterReEnteringThePasswordTheNumberIsShownAndAudited() {
        ApiClient.Response confirmed = reauthenticate(thandi, PASSWORD);
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        assertThat(confirmed.text("$.confirmedUntil")).isNotBlank();

        ApiClient.Response shown = reveal(thandi, clientId);
        assertThat(shown.status()).as(shown.body()).isEqualTo(200);
        assertThat(shown.text("$.idNumber")).isEqualTo(fakeIdNumber(1));
        assertThat(auditCount("CLIENT_VIEWED")).isEqualTo(1);
    }

    @Test
    void confirmationBelongsToOneSessionOnly() {
        ApiClient otherBrowser = signIn("thandi@test.local");
        confirmPassword(thandi);

        assertThat(reveal(thandi, clientId).status()).isEqualTo(200);
        assertThat(reveal(otherBrowser, clientId).status()).isEqualTo(403);
    }

    @Test
    void refusedTheSameWayWhetherOrNotTheClientExists() {
        // Before confirming: the same 403 for a client that does not exist.
        assertThat(reveal(thandi, 999999).status()).isEqualTo(403);

        // After confirming: someone else's client still looks like it does not exist.
        long othersClient = createClient("Test", "Bravo", null);
        confirmPassword(thandi);
        assertThat(reveal(thandi, othersClient).status()).isEqualTo(404);
    }

    // ---------- wrong passwords ----------

    @Test
    void wrongPasswordIs400AndDoesNotSignOtherSessionsOut() {
        ApiClient otherBrowser = signIn("thandi@test.local");

        ApiClient.Response wrong = reauthenticate(thandi, "not-the-password");

        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.text("$.type")).isEqualTo("urn:credit-advisory:auth:wrong-password");
        assertThat(reveal(thandi, clientId).status()).isEqualTo(403);
        assertThat(otherBrowser.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(thandi.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(auditCount("PASSWORD_CONFIRMATION_FAILED")).isEqualTo(1);
    }

    @Test
    void fiveWrongPasswordsLockTheAccountAndSignThisSessionOut() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(reauthenticate(thandi, "not-the-password").status()).isEqualTo(400);
        }

        ApiClient.Response fifth = reauthenticate(thandi, "not-the-password");

        assertThat(fifth.status()).isEqualTo(401);
        assertThat(fifth.text("$.type")).isEqualTo("urn:credit-advisory:auth:account-locked");
        assertThat(thandi.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(auditCount("ACCOUNT_LOCKED")).isEqualTo(1);
        // Locked like a sign-in lockout: even the right password does not get back in.
        assertThat(browser().signIn("thandi@test.local", PASSWORD).status()).isEqualTo(401);
    }

    @Test
    void theRightPasswordResetsTheWrongPasswordCount() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            reauthenticate(thandi, "not-the-password");
        }
        confirmPassword(thandi);
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(reauthenticate(thandi, "not-the-password").status()).isEqualTo(400);
        }
        assertThat(auditCount("ACCOUNT_LOCKED")).isZero();
    }

    // ---------- hourly limit ----------

    @Test
    void afterTheHourlyLimitRevealsAreRefusedAndAnAdminCanSeeIt() {
        confirmPassword(thandi);
        for (int i = 1; i <= LIMIT; i++) {
            assertThat(reveal(thandi, clientId).status()).as("reveal %d", i).isEqualTo(200);
        }

        ApiClient.Response refused = reveal(thandi, clientId);
        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.text("$.type")).isEqualTo("urn:credit-advisory:client:id-reveal-limit");
        assertThat(Long.parseLong(refused.header("Retry-After"))).isBetween(1L, 3600L);
        assertThat(reveal(thandi, clientId).status()).isEqualTo(429);

        // Reported once, not once per refused request; refused reveals are not
        // "viewed".
        assertThat(auditCount("ID_REVEAL_LIMIT_REACHED")).isEqualTo(1);
        assertThat(auditCount("CLIENT_VIEWED")).isEqualTo(LIMIT);
        ApiClient.Response alerts = signIn(ADMIN_EMAIL).get("/api/audit-logs?action=ID_REVEAL_LIMIT_REACHED");
        assertThat(alerts.status()).as(alerts.body()).isEqualTo(200);
        assertThat(alerts.number("$.totalItems")).isEqualTo(1);

        // Normal work carries on, and other users have their own allowance.
        assertThat(thandi.get("/api/clients/" + clientId).status()).isEqualTo(200);
        ApiClient manager = signIn("manager@test.local");
        confirmPassword(manager);
        assertThat(reveal(manager, clientId).status()).isEqualTo(200);
    }

    @Test
    void reauthenticateNeedsSignIn() {
        ApiClient notSignedIn = browser();
        notSignedIn.get("/api/auth/csrf"); // a valid CSRF token, so only the missing sign-in counts
        assertThat(reauthenticate(notSignedIn, PASSWORD).status()).isEqualTo(401);
    }
}
