package za.co.mafori.creditadvisory.common.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Production only: after the migrations, checks in the database itself that
 * the account the application runs as really is limited
 * (docs/security-accounts-and-uploads.md), and refuses to start if not. The
 * settings can be right while the database is not, for example when IT created
 * the tables with the application's account.
 *
 * <p>
 * A lifecycle bean with the lowest phase: it runs once every bean exists (so
 * the migrations are done) but BEFORE the web server starts listening, so a
 * wrongly set-up server never answers a single request.
 */
@Component
@Profile("prod")
public class DatabaseAccountVerifier implements SmartLifecycle {

    private final JdbcTemplate jdbc;
    private volatile boolean running;

    public DatabaseAccountVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 1000;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public void start() {
        List<String> problems = problems(jdbc);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start in production: the application's database account"
                    + " (DB_USERNAME) has rights it must not have:\n - " + String.join("\n - ", problems)
                    + "\nSee docs/it-handover.md section 3.");
        }
        running = true;
    }

    /** Empty when the application's account can only read and write rows. */
    public static List<String> problems(JdbcTemplate jdbc) {
        List<String> problems = new ArrayList<>();
        Boolean powerful = jdbc.queryForObject(
                "select rolsuper or rolcreaterole or rolcreatedb or rolbypassrls from pg_roles where rolname = current_user",
                Boolean.class);
        if (Boolean.TRUE.equals(powerful)) {
            problems.add("it is a superuser, or may create roles or databases, or bypasses row security");
        }
        Long owned = jdbc.queryForObject(
                "select count(*) from pg_tables where schemaname = 'public' and tableowner = current_user", Long.class);
        if (owned != null && owned > 0) {
            problems.add("it owns " + owned + " table(s): the tables must belong to DB_MIGRATION_USERNAME");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select has_schema_privilege(current_user, 'public', 'CREATE')", Boolean.class))) {
            problems.add("it may create tables in the schema");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                select has_table_privilege(current_user, 'audit_logs', 'UPDATE')
                    or has_table_privilege(current_user, 'audit_logs', 'DELETE')
                    or has_table_privilege(current_user, 'audit_logs', 'TRUNCATE')
                """, Boolean.class))) {
            problems.add("it may change or delete the audit log");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select has_table_privilege(current_user, 'flyway_schema_history', 'INSERT,UPDATE,DELETE')",
                Boolean.class))) {
            problems.add("it may change the migration history");
        }
        return problems;
    }
}
