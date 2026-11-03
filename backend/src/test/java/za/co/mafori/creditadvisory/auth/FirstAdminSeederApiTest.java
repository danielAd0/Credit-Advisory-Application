package za.co.mafori.creditadvisory.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/**
 * The first administrator on a brand-new server (FIRST_ADMIN_EMAIL /
 * FIRST_ADMIN_PASSWORD).
 */
@TestPropertySource(properties = {
        "app.bootstrap.admin-email=First@Test.Local",
        "app.bootstrap.admin-password=First-Admin-Pass-2026" })
class FirstAdminSeederApiTest extends ApiTestBase {

    @Autowired
    private FirstAdminSeeder seeder;

    private long userCount() {
        return jdbc.queryForObject("select count(*) from users", Long.class);
    }

    @Test
    void doesNothingWhenAnAdministratorAlreadyExists() {
        long before = userCount();

        assertThat(seeder.createFirstAdminIfNeeded()).isFalse();
        assertThat(userCount()).isEqualTo(before);
    }

    @Test
    void createsTheFirstAdministratorWhoMustChooseTheirOwnPassword() {
        // A brand-new server: no users at all.
        jdbc.update("delete from user_roles");
        jdbc.update("delete from users");

        assertThat(seeder.createFirstAdminIfNeeded()).isTrue();
        assertThat(seeder.createFirstAdminIfNeeded()).as("only once").isFalse();

        ApiClient first = browser();
        ApiClient.Response login = first.signIn("first@test.local", "First-Admin-Pass-2026");
        assertThat(login.status()).as(login.body()).isEqualTo(200);
        assertThat(login.texts("$.roles")).containsExactly("ADMIN");
        assertThat(login.value("$.mustChangePassword")).isEqualTo(true);
        assertThat(first.get("/api/users").text("$.type"))
                .isEqualTo("urn:credit-advisory:auth:password-change-required");

        String audit = jdbc.queryForObject(
                "select metadata::text from audit_logs where action = 'FIRST_ADMIN_CREATED'", String.class);
        assertThat(audit).contains("first@test.local").doesNotContain("First-Admin-Pass-2026");
    }
}
