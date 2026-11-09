package za.co.mafori.creditadvisory.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/**
 * The health check on its own port, the way production runs it
 * (application-prod.properties): the public port does not serve it, and the
 * health port serves nothing else and gives no details.
 */
@TestPropertySource(properties = "management.server.address=127.0.0.1")
class HealthPortApiTest extends ApiTestBase {

    private static final int HEALTH_PORT = freePort();

    @DynamicPropertySource
    static void healthPort(DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> HEALTH_PORT);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ApiClient healthPort() {
        return new ApiClient("http://127.0.0.1:" + HEALTH_PORT);
    }

    @Test
    void healthIsOnItsOwnPortAndSaysOnlyUpOrDown() {
        ApiClient.Response health = healthPort().get("/actuator/health");

        assertThat(health.status()).as(health.body()).isEqualTo(200);
        assertThat(health.text("$.status")).isEqualTo("UP");
        // No database, disk or version details for whoever can reach it.
        assertThat(health.body()).doesNotContain("components", "details", "db", "diskSpace");
    }

    @Test
    void thePublicPortNoLongerServesIt() {
        assertThat(browser().get("/actuator/health").status()).isEqualTo(404);
    }

    @Test
    void theHealthPortServesNothingElse() {
        // Not signed in, so a 401 is as good as a 404: neither gives anything away.
        assertThat(healthPort().get("/actuator/env").status()).isIn(401, 404);
        assertThat(healthPort().get("/actuator/beans").status()).isIn(401, 404);
        assertThat(healthPort().get("/api/auth/csrf").status()).isIn(401, 404);
    }
}
