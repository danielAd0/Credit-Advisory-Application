package za.co.mafori.creditadvisory.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/**
 * Per-address limit on failed sign-ins. All test requests come from 127.0.0.1,
 * so
 * they all count against the same address. The default limit is 20 per 5
 * minutes.
 */
class LoginRateLimitApiTest extends ApiTestBase {

    private static final int LIMIT = 20;

    @Test
    void tryingOnePasswordAgainstManyAccountsGetsBlocked() {
        ApiClient attacker = browser();
        for (int i = 1; i <= LIMIT; i++) {
            ApiClient.Response failed = attacker.signIn("user" + i + "@test.local", "Summer2026!");
            assertThat(failed.status()).as("attempt %d", i).isEqualTo(401);
        }

        ApiClient.Response blocked = attacker.signIn("user21@test.local", "Summer2026!");
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.text("$.type")).isEqualTo("urn:credit-advisory:auth:too-many-attempts");

        // While blocked, even a correct password from this address is refused - so the
        // attacker cannot tell when they have guessed right.
        assertThat(browser().signIn(ADMIN_EMAIL, PASSWORD).status()).isEqualTo(429);
    }

    @Test
    void successfulSignInsAreNeverCounted() {
        for (int i = 1; i <= LIMIT + 5; i++) {
            assertThat(browser().signIn(ADMIN_EMAIL, PASSWORD).status()).as("sign-in %d", i).isEqualTo(200);
        }
    }

    @Test
    void openSessionsKeepWorkingWhileSignInIsBlocked() {
        ApiClient admin = signIn(ADMIN_EMAIL);

        ApiClient attacker = browser();
        for (int i = 1; i <= LIMIT; i++) {
            attacker.signIn("user" + i + "@test.local", "Summer2026!");
        }
        assertThat(attacker.signIn("user21@test.local", "Summer2026!").status()).isEqualTo(429);

        assertThat(admin.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(admin.get("/api/clients").status()).isEqualTo(200);
    }
}
