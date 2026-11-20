package za.co.mafori.creditadvisory.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Production refuses to start without two database accounts and the upload
 * virus scan.
 */
class SecuritySettingsCheckTest {

    /**
     * As application-prod.properties gives the settings once IT has set every
     * variable.
     */
    private static MockEnvironment good() {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles("prod");
        e.setProperty("spring.datasource.username", "ca_app");
        e.setProperty("spring.datasource.password", "made-up-app-password");
        e.setProperty("spring.flyway.user", "ca_owner");
        e.setProperty("spring.flyway.password", "made-up-owner-password");
        e.setProperty("spring.flyway.placeholders.approle", "ca_app");
        e.setProperty("app.upload-scan.mode", "clamd");
        e.setProperty("app.upload-scan.clamd-host", "127.0.0.1");
        e.setProperty("app.upload-scan.clamd-port", "3310");
        return e;
    }

    @Test
    void correctSettingsPass() {
        assertThat(SecuritySettingsCheck.problems(good())).isEmpty();
    }

    @Test
    void theMigrationAccountMustExistAndBeAnotherAccount() {
        MockEnvironment missing = good();
        missing.setProperty("spring.flyway.user", "${DB_MIGRATION_USERNAME}");
        assertThat(SecuritySettingsCheck.problems(missing)).singleElement().asString()
                .contains("DB_MIGRATION_USERNAME");

        MockEnvironment same = good();
        same.setProperty("spring.flyway.user", "CA_APP");
        assertThat(SecuritySettingsCheck.problems(same)).singleElement().asString()
                .contains("two different database accounts");

        MockEnvironment samePassword = good();
        samePassword.setProperty("spring.flyway.password", "made-up-app-password");
        assertThat(SecuritySettingsCheck.problems(samePassword)).singleElement().asString()
                .contains("different passwords")
                .doesNotContain("made-up-app-password");

        MockEnvironment noRole = good();
        noRole.setProperty("spring.flyway.placeholders.approle", "");
        assertThat(SecuritySettingsCheck.problems(noRole)).singleElement().asString().contains("approle");
    }

    @Test
    void uploadsMustBeScanned() {
        MockEnvironment off = good();
        off.setProperty("app.upload-scan.mode", "off");
        assertThat(SecuritySettingsCheck.problems(off)).singleElement().asString().contains("clamd");

        MockEnvironment badPort = good();
        badPort.setProperty("app.upload-scan.clamd-port", "99999");
        assertThat(SecuritySettingsCheck.problems(badPort)).singleElement().asString().contains("CLAMD_PORT");
    }
}
