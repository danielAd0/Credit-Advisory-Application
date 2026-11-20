package za.co.mafori.creditadvisory.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Production only, next to ProductionSafetyCheck and the same way (before any
 * bean or migration): refuses to START unless the two security measures of
 * docs/security-accounts-and-uploads.md are set up.
 * <ul>
 * <li><b>Two database accounts.</b> Flyway migrates as the owner of the
 * tables (DB_MIGRATION_USERNAME); the application runs as DB_USERNAME, which
 * can only read and write rows. The same account for both would hand the
 * running application the right to drop tables and switch off the audit log's
 * protection.</li>
 * <li><b>Virus scan of uploads</b> through ClamAV
 * (app.upload-scan.mode=clamd).</li>
 * </ul>
 * Problems are listed by property and environment variable name, never with a
 * value.
 */
@Component
@Profile("prod")
public class SecuritySettingsCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> problems = problems(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start in production:\n - " + String.join("\n - ", problems));
        }
    }

    static List<String> problems(Environment environment) {
        List<String> problems = new ArrayList<>();

        String migrationUser = read(environment, "spring.flyway.user");
        String migrationPassword = read(environment, "spring.flyway.password");
        String appUser = read(environment, "spring.datasource.username");
        if (migrationUser.isEmpty() || migrationUser.equals("DB_MIGRATION_USERNAME")) {
            problems.add("spring.flyway.user is not set (environment variable DB_MIGRATION_USERNAME): the database"
                    + " account that owns the tables and runs the migrations");
        } else if (migrationUser.equalsIgnoreCase(appUser)) {
            problems.add("DB_MIGRATION_USERNAME and DB_USERNAME must be two different database accounts: the"
                    + " application's account must not own the tables");
        }
        if (migrationPassword.isEmpty() || migrationPassword.equals("DB_MIGRATION_PASSWORD")) {
            problems.add("spring.flyway.password is not set (environment variable DB_MIGRATION_PASSWORD)");
        } else if (migrationPassword.equals(read(environment, "spring.datasource.password"))) {
            problems.add("DB_MIGRATION_PASSWORD and DB_PASSWORD must be different passwords");
        }
        String appRole = read(environment, "spring.flyway.placeholders.approle");
        if (!appUser.isEmpty() && !appRole.equals(appUser)) {
            problems.add("spring.flyway.placeholders.approle must be ${DB_USERNAME} (application-prod.properties),"
                    + " so the migrations give the application's account its rights");
        }

        String mode = read(environment, "app.upload-scan.mode").toLowerCase(Locale.ROOT);
        if (!mode.equals("clamd")) {
            problems.add("app.upload-scan.mode must be clamd: every uploaded file is scanned for viruses");
        } else {
            if (read(environment, "app.upload-scan.clamd-host").isEmpty()) {
                problems.add("app.upload-scan.clamd-host is not set (environment variable CLAMD_HOST)");
            }
            String port = read(environment, "app.upload-scan.clamd-port");
            if (!port.matches("\\d{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) {
                problems.add("app.upload-scan.clamd-port (CLAMD_PORT) must be a port number");
            }
        }
        return problems;
    }

    /** The value, trimmed, or "" if missing or an unresolved ${...}. */
    private static String read(Environment environment, String property) {
        try {
            String value = environment.getProperty(property);
            return value == null ? "" : value.strip();
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return "";
        }
    }
}
