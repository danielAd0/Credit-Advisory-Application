package za.co.mafori.creditadvisory.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/**
 * The demo data still goes in through the real services (DemoData). If a rule
 * changes and the demo data no longer fits it, this goes red.
 */
class DemoDataTest extends ApiTestBase {

    @Autowired
    private DemoData demoData;

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void makesTheDemoDataOnce() {
        DemoData.Summary made = demoData.seed(PASSWORD).orElseThrow();

        assertThat(made.consultants()).isEqualTo(3);
        assertThat(made.clients()).isEqualTo(3 * DemoData.CLIENTS_PER_CONSULTANT);
        assertThat(made.sales()).isGreaterThanOrEqualTo(made.clients());
        assertThat(made.creditCases()).isEqualTo(6);
        assertThat(count("select count(*) from clients")).isEqualTo(30);
        assertThat(count("select count(*) from client_transactions")).isEqualTo(made.sales());
        assertThat(count("select count(*) from client_assignment_history where change_type = 'TRANSFER'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from credit_cases where status = 'CLOSED'")).isEqualTo(3);
        assertThat(count("select count(*) from sales_targets")).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'SALE_CAPTURED' and actor_user_id is null"))
                .as("every sale is in the audit log under the consultant who made it").isZero();

        assertThat(demoData.seed(PASSWORD)).as("a second start changes nothing").isEmpty();
        assertThat(count("select count(*) from clients")).isEqualTo(30);
    }

    @Test
    void demoLoginsWorkStraightAway() {
        demoData.seed(PASSWORD);

        ApiClient lerato = signIn("lerato.demo@example.com");
        ApiClient.Response theirClients = lerato.get("/api/clients");
        ApiClient manager = signIn(DemoData.MANAGER_EMAIL);
        ApiClient.Response allClients = manager.get("/api/clients");

        assertThat(theirClients.status()).as("no password change first").isEqualTo(200);
        assertThat(theirClients.number("$.totalItems")).as("one of hers was handed over")
                .isEqualTo(DemoData.CLIENTS_PER_CONSULTANT - 1);
        assertThat(allClients.status()).isEqualTo(200);
        assertThat(allClients.number("$.totalItems")).isEqualTo(30);
    }

    @Test
    void theMadeUpIdNumbersAreValidAndDifferent() {
        for (int n = 0; n < 3 * DemoData.CLIENTS_PER_CONSULTANT; n++) {
            assertThat(SaIdNumber.isValid(DemoData.fakeIdNumber(n))).isTrue();
        }
        assertThat(DemoData.fakeIdNumber(0)).isNotEqualTo(DemoData.fakeIdNumber(1));
    }
}
