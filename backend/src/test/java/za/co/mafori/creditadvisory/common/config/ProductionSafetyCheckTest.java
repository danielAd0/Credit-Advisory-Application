package za.co.mafori.creditadvisory.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * The production start-up check. The first three tests check the rules without
 * starting anything; the last one starts a tiny Spring context to prove the
 * check is really wired in. The "keys" here are made-up strings: they only need
 * to be present, not valid.
 */
class ProductionSafetyCheckTest {

    /**
     * Settings as application-prod.properties gives them once IT has set every
     * variable.
     */
    private static MockEnvironment goodProduction() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://db.internal:5432/credit_advisory");
        environment.setProperty("spring.datasource.username", "ca_app");
        environment.setProperty("spring.datasource.password", "not-a-real-password");
        environment.setProperty("app.idnumber.lookup-key", "made-up-lookup-key");
        environment.setProperty("app.idnumber.encryption-key", "made-up-encryption-key");
        environment.setProperty("app.mfa.encryption-key", "made-up-mfa-key");
        environment.setProperty("app.mfa.required", "true");
        environment.setProperty("server.servlet.session.cookie.secure", "true");
        environment.setProperty("spring.flyway.clean-disabled", "true");
        environment.setProperty("app.auth.cors-allowed-origins", "");
        return environment;
    }

    @Test
    void correctProductionSettingsPass() {
        assertThat(ProductionSafetyCheck.problems(goodProduction())).isEmpty();
        assertThatCode(() -> ProductionSafetyCheck.verify(goodProduction())).doesNotThrowAnyException();

        MockEnvironment withHttpsFrontend = goodProduction();
        withHttpsFrontend.setProperty("app.auth.cors-allowed-origins", "https://advisory.example.com");
        assertThat(ProductionSafetyCheck.problems(withHttpsFrontend)).isEmpty();
    }

    @Test
    void missingVariablesAreNamedButNoValueIsShown() {
        MockEnvironment environment = goodProduction();
        // Exactly what application-prod.properties holds when IT forgot these
        // variables.
        environment.setProperty("spring.datasource.url", "${DB_URL}");
        environment.setProperty("app.idnumber.encryption-key", "${ID_NUMBER_ENCRYPTION_KEY}");

        List<String> problems = ProductionSafetyCheck.problems(environment);

        assertThat(problems).hasSize(2);
        assertThat(String.join(" ", problems))
                .contains("DB_URL", "ID_NUMBER_ENCRYPTION_KEY")
                .doesNotContain("not-a-real-password", "made-up-lookup-key");
    }

    @Test
    void unsafeSettingsAreRefused() {
        MockEnvironment environment = goodProduction();
        environment.setActiveProfiles("prod", "local");
        environment.setProperty("server.servlet.session.cookie.secure", "false");
        environment.setProperty("spring.flyway.clean-disabled", "false");
        environment.setProperty("app.dev.admin-email", "admin@local.test");
        environment.setProperty("app.auth.cors-allowed-origins", "http://localhost:5173");
        environment.setProperty("app.idnumber.encryption-key", "made-up-lookup-key");

        assertThat(ProductionSafetyCheck.problems(environment)).hasSize(6);
        assertThatThrownBy(() -> ProductionSafetyCheck.verify(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start in production")
                .hasMessageContaining("cookie.secure")
                .hasMessageContaining("http://localhost:5173")
                .hasMessageNotContaining("made-up-lookup-key");
    }

    @Test
    void theCheckRunsWhenAnApplicationStartsWithTheProdProfile() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ProductionSafetyCheck.class);

        // prod with nothing configured: start-up is refused.
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("Refusing to start in production");
        });

        // prod with everything configured: it starts.
        runner.withPropertyValues("spring.profiles.active=prod",
                "spring.datasource.url=jdbc:postgresql://db.internal:5432/credit_advisory",
                "spring.datasource.username=ca_app",
                "spring.datasource.password=not-a-real-password",
                "app.idnumber.lookup-key=made-up-lookup-key",
                "app.idnumber.encryption-key=made-up-encryption-key",
                "app.mfa.encryption-key=made-up-mfa-key",
                "server.servlet.session.cookie.secure=true",
                "spring.flyway.clean-disabled=true")
                .run(context -> assertThat(context).hasNotFailed());

        // Without prod the check does not exist at all.
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ProductionSafetyCheck.class));
    }

    @Test
    void mfaMustBeOnWithItsOwnKey() {
        MockEnvironment missingKey = goodProduction();
        missingKey.setProperty("app.mfa.encryption-key", "${MFA_ENCRYPTION_KEY}");
        assertThat(ProductionSafetyCheck.problems(missingKey)).singleElement().asString()
                .contains("MFA_ENCRYPTION_KEY");

        MockEnvironment switchedOff = goodProduction();
        switchedOff.setProperty("app.mfa.required", "false");
        assertThat(ProductionSafetyCheck.problems(switchedOff)).singleElement().asString()
                .contains("app.mfa.required must be true");

        MockEnvironment sharedKey = goodProduction();
        sharedKey.setProperty("app.mfa.encryption-key", "made-up-encryption-key");
        assertThat(ProductionSafetyCheck.problems(sharedKey)).singleElement().asString()
                .contains("its own key")
                .doesNotContain("made-up-encryption-key");
    }

    @Test
    void placeholderNamesInsteadOfValuesAreRefused() {
        MockEnvironment environment = goodProduction();
        // What a file without the ${...} gives: the variable NAME as the value.
        environment.setProperty("spring.datasource.url", "DB_URL");
        environment.setProperty("spring.datasource.username", "DB_USERNAME");
        environment.setProperty("spring.datasource.password", "DB_PASSWORD");

        List<String> problems = ProductionSafetyCheck.problems(environment);

        assertThat(problems).hasSize(3);
        assertThat(problems).allMatch(p -> p.contains("instead of its value"));
        assertThat(String.join(" ", problems)).contains("${DB_URL}", "${DB_USERNAME}", "${DB_PASSWORD}");
    }

    @Test
    void theTestingCommitFromTuesdayCouldNotStartAServer() {
        MockEnvironment environment = goodProduction();
        // Exactly what commit 1cc8025 put in application-prod.properties.
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/credit_advisory");
        environment.setProperty("spring.datasource.username", "DB_USERNAME");
        environment.setProperty("spring.datasource.password", "DB_PASSWORD");

        assertThatThrownBy(() -> ProductionSafetyCheck.verify(environment))
                .hasMessageContaining("localhost")
                .hasMessageContaining("${DB_USERNAME}")
                .hasMessageContaining("${DB_PASSWORD}");
    }

    @Test
    void theDatabaseUrlMustBeARealPostgresServerThatIsNotATestDatabase() {
        assertThat(problemsWithUrl("jdbc:mysql://db.internal:3306/credit_advisory"))
                .containsExactly("DB_URL must start with jdbc:postgresql://");
        assertThat(problemsWithUrl("jdbc:postgresql://db.internal:5432/credit_advisory_test?sslmode=require"))
                .singleElement().asString().contains("_test");

        for (String local : List.of("jdbc:postgresql://localhost/credit_advisory",
                "jdbc:postgresql://127.0.0.1:5432/credit_advisory",
                "jdbc:postgresql://[::1]:5432/credit_advisory",
                "jdbc:postgresql://db1.internal:5432,localhost:5432/credit_advisory")) {
            assertThat(problemsWithUrl(local)).as(local).singleElement().asString().contains("localhost");
        }

        // A database on the same server is allowed when IT says so explicitly.
        MockEnvironment allowed = goodProduction();
        allowed.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/credit_advisory");
        allowed.setProperty("app.safety.allow-local-database", "true");
        assertThat(ProductionSafetyCheck.problems(allowed)).isEmpty();
    }

    private static List<String> problemsWithUrl(String url) {
        MockEnvironment environment = goodProduction();
        environment.setProperty("spring.datasource.url", url);
        return ProductionSafetyCheck.problems(environment);
    }
}
