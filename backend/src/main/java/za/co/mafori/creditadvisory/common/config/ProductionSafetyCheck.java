package za.co.mafori.creditadvisory.common.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
            List.of("app.idnumber.encryption-key", "ID_NUMBER_ENCRYPTION_KEY"),
            List.of("app.mfa.encryption-key", "MFA_ENCRYPTION_KEY"));

    private static final String POSTGRES_PREFIX = "jdbc:postgresql://";

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
            String value = read(environment, required.get(0));
            if (value.isEmpty()) {
                problems.add(required.get(0) + " is not set (environment variable " + required.get(1) + ")");
            } else if (value.equalsIgnoreCase(required.get(1))) {
                // e.g. spring.datasource.username=DB_USERNAME - the ${...} was left out.
                problems.add(required.get(0) + " is the text " + required.get(1)
                        + " instead of its value: write ${" + required.get(1) + "}");
            }
        }

        String url = read(environment, "spring.datasource.url");
        if (!url.isEmpty() && !url.equalsIgnoreCase("DB_URL")) {
            // Never print the URL itself: it can contain a password.
            if (!url.startsWith(POSTGRES_PREFIX)) {
                problems.add("DB_URL must start with " + POSTGRES_PREFIX);
            } else {
                if (databaseName(url).endsWith("_test")) {
                    problems.add("DB_URL points at a database whose name ends in _test: the automated tests"
                            + " wipe those databases");
                }
                if (pointsAtThisMachine(url)) {
                    if (!"true".equalsIgnoreCase(read(environment, "app.safety.allow-local-database"))) {
                        problems.add("DB_URL points at this machine (localhost). If the database really runs on"
                                + " the same server, set ALLOW_LOCAL_DATABASE=true");
                    }
                } else if (!usesEncryptedConnection(url)) {
                    problems.add("DB_URL must use an encrypted connection to the database on another machine:"
                            + " add ?sslmode=verify-full (or at least sslmode=require)");
                }
            }
        }

        String lookupKey = read(environment, "app.idnumber.lookup-key");
        if (!lookupKey.isEmpty() && lookupKey.equals(read(environment, "app.idnumber.encryption-key"))) {
            problems.add("ID_NUMBER_LOOKUP_KEY and ID_NUMBER_ENCRYPTION_KEY must be two different keys");
        }

        String mfaKey = read(environment, "app.mfa.encryption-key");
        if (!mfaKey.isEmpty() && (mfaKey.equals(lookupKey)
                || mfaKey.equals(read(environment, "app.idnumber.encryption-key")))) {
            problems.add("MFA_ENCRYPTION_KEY must be its own key, not one of the ID number keys");
        }
        if ("false".equalsIgnoreCase(read(environment, "app.mfa.required"))) {
            problems.add("app.mfa.required must be true: every user signs in with an authenticator code");
        }

        problems.addAll(networkProblems(environment));

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
     * The application and the health check each listen on one address, never on
     * every network, and the health check has a port of its own (README, "Network
     * checklist"). Addresses are not secret, but they are still not printed.
     */
    static List<String> networkProblems(Environment environment) {
        List<String> problems = new ArrayList<>();

        if (listensEverywhere(read(environment, "server.address"))) {
            problems.add("server.address (SERVER_ADDRESS) must be one address - 127.0.0.1 when the proxy runs on"
                    + " this machine, otherwise the private address the proxy connects to - not every network");
        }
        if (listensEverywhere(read(environment, "management.server.address"))) {
            problems.add("management.server.address (MANAGEMENT_ADDRESS) must be one address, not every network");
        }

        String managementPort = read(environment, "management.server.port");
        String serverPort = read(environment, "server.port");
        if (managementPort.isEmpty()) {
            problems.add("management.server.port is not set (environment variable MANAGEMENT_PORT): the health"
                    + " check must have its own port");
        } else if (managementPort.equals(serverPort.isEmpty() ? "8080" : serverPort)) {
            problems.add("MANAGEMENT_PORT must be a different port from the application's");
        }
        return problems;
    }

    /** Empty means "every network" to Spring, just like 0.0.0.0 and ::. */
    static boolean listensEverywhere(String address) {
        return switch (address.toLowerCase(Locale.ROOT)) {
            case "", "*", "0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0" -> true;
            default -> false;
        };
    }

    /**
     * True if the PostgreSQL driver will encrypt the connection AND refuse a
     * server that does not support it: sslmode require, verify-ca or verify-full,
     * or ssl=true without an sslmode (the driver then checks like verify-full).
     * sslmode prefer or allow fall back to plain text, so they do not count.
     */
    static boolean usesEncryptedConnection(String url) {
        int query = url.indexOf('?');
        if (query < 0) {
            return false;
        }
        String sslmode = null;
        boolean ssl = false;
        for (String pair : url.substring(query + 1).split("&")) {
            int equals = pair.indexOf('=');
            String key = (equals < 0 ? pair : pair.substring(0, equals)).strip().toLowerCase(Locale.ROOT);
            String value = equals < 0 ? "" : pair.substring(equals + 1).strip().toLowerCase(Locale.ROOT);
            if (key.equals("sslmode")) {
                sslmode = value;
            } else if (key.equals("ssl")) {
                ssl = value.isEmpty() || value.equals("true");
            }
        }
        if (sslmode != null) {
            return sslmode.equals("require") || sslmode.equals("verify-ca") || sslmode.equals("verify-full");
        }
        return ssl;
    }

    /**
     * "jdbc:postgresql://host:5432/credit_advisory?ssl=true" -> "credit_advisory".
     */
    static String databaseName(String url) {
        String rest = url.substring(POSTGRES_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return "";
        }
        String name = rest.substring(slash + 1);
        int query = name.indexOf('?');
        return (query < 0 ? name : name.substring(0, query)).toLowerCase(Locale.ROOT);
    }

    /**
     * True if any host in the URL is this machine ("db1:5432,localhost:5432"
     * counts).
     */
    static boolean pointsAtThisMachine(String url) {
        String rest = url.substring(POSTGRES_PREFIX.length());
        int slash = rest.indexOf('/');
        String hosts = slash < 0 ? rest : rest.substring(0, slash);
        for (String entry : hosts.split(",")) {
            String host = entry.strip().toLowerCase(Locale.ROOT);
            if (host.startsWith("[")) {
                int end = host.indexOf(']');
                host = end < 0 ? host : host.substring(1, end); // [::1]:5432 -> ::1
            } else if (host.contains(":")) {
                host = host.substring(0, host.indexOf(':')); // localhost:5432 -> localhost
            }
            if (host.isEmpty() || host.equals("localhost") || host.equals("::1") || host.startsWith("127.")) {
                return true;
            }
        }
        return false;
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
