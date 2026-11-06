package za.co.mafori.creditadvisory.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/**
 * Every response carries the security headers: normal JSON, a 401 written by
 * the security filters, and a 404 ProblemDetail written by a controller.
 */
class SecurityHeadersApiTest extends ApiTestBase {

    @Test
    void signedInResponsesCarryTheSecurityHeaders() {
        ApiClient admin = signIn(ADMIN_EMAIL);

        ApiClient.Response me = admin.get("/api/auth/me");

        assertThat(me.status()).isEqualTo(200);
        assertSecurityHeaders(me);
    }

    @Test
    void notSignedInResponsesCarryThemToo() {
        ApiClient.Response response = browser().get("/api/clients");

        assertThat(response.status()).isEqualTo(401);
        assertSecurityHeaders(response);
    }

    @Test
    void notFoundResponsesCarryThemToo() {
        ApiClient admin = signIn(ADMIN_EMAIL);

        ApiClient.Response response = admin.get("/api/clients/999999");

        assertThat(response.status()).isEqualTo(404);
        assertSecurityHeaders(response);
    }

    private static void assertSecurityHeaders(ApiClient.Response response) {
        // Written out in full on purpose: changing a policy should mean changing this
        // test.
        assertThat(response.header("Content-Security-Policy"))
                .isEqualTo("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        assertThat(response.header("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.header("Permissions-Policy"))
                .isEqualTo("camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        assertThat(response.header("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
        // Client data must never sit in a browser or proxy cache.
        assertThat(response.header("Cache-Control")).contains("no-store");
    }
}
