package za.co.mafori.creditadvisory.common.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Production only: refuses to START if a secret is missing or a setting is
 * unsafe. Every problem is listed at once, by property and environment variable
 * name - never with a value.
 *
 * <p>
 * A BeanFactoryPostProcessor runs before any normal bean is created, so this
 * check happens before the database is touched (no Flyway migration runs
 * against a server that is set up wrong). That is also why it gets the
 * Environment through EnvironmentAware: a BeanFactoryPostProcessor is created
 * so early that constructor injection is not available yet.
 */
@Component
@Profile("prod")
public class ProductionSafetyCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    /**
     * Property, and the environment variable IT sets for it
     * (application-prod.properties).
     */
    private static final List<List<String>> REQUIRED = List.of(
            List.of("spring.datasource.url", "DB_URL"),
            List.of("spring.datasource.username", "DB_USERNAME"),
            List.of("spring.datasource.password", "DB_PASSWORD"),
            List.of("app.idnumber.lookup-key", "ID_NUMBER_LOOKUP_KEY"),
            List.of("app.idnumber.encryption-key", "ID_NUMBER_ENCRYPTION_KEY"));

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        verify(environment);
    }

    /** Throws if anything is wrong, listing every problem. */
    static void verify(Environment environment) {
        List<String> problems = problems(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start in production:\n - "
                    + String.join("\n - ", problems));
        }
    }

    static List<String> problems(Environment environment) {
        List<String> problems = new ArrayList<>();

        List<String> active = Arrays.asList(environment.getActiveProfiles());
        for (String developmentProfile : List.of("local", "test")) {
            if (active.contains(developmentProfile)) {
                problems.add("the '" + developmentProfile + "' profile is active together with 'prod'");
            }
        }

        for (List<String> required : REQUIRED) {
            if (read(environment, required.get(0)).isEmpty()) {
                problems.add(required.get(0) + " is not set (environment variable " + required.get(1) + ")");
            }
        }

        String lookupKey = read(environment, "app.idnumber.lookup-key");
        if (!lookupKey.isEmpty() && lookupKey.equals(read(environment, "app.idnumber.encryption-key"))) {
            problems.add("ID_NUMBER_LOOKUP_KEY and ID_NUMBER_ENCRYPTION_KEY must be two different keys");
        }

        if (!"true".equalsIgnoreCase(read(environment, "server.servlet.session.cookie.secure"))) {
            problems.add("server.servlet.session.cookie.secure must be true (session cookie over HTTPS only)");
        }
        if ("false".equalsIgnoreCase(read(environment, "spring.flyway.clean-disabled"))) {
            problems.add("spring.flyway.clean-disabled must be true (clean deletes every table)");
        }
        if (!read(environment, "app.dev.admin-email").isEmpty()) {
            problems.add("app.dev.admin-email is set: the development admin must never exist in production");
        }
        for (String origin : read(environment, "app.auth.cors-allowed-origins").split(",")) {
            String trimmed = origin.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("https://")) {
                problems.add("CORS origin " + trimmed + " must start with https://");
            }
        }
        return problems;
    }

    /**
     * The value, trimmed, or "" if it is missing - including "${DB_URL}" whose
     * environment variable is not set (that throws while resolving).
     */
    private static String read(Environment environment, String property) {
        try {
            String value = environment.getProperty(property);
            return value == null ? "" : value.strip();
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return "";
        }
    }
}
