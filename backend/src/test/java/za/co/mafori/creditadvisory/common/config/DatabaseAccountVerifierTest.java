package za.co.mafori.creditadvisory.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/**
 * The test database has ONE account that owns everything (like a laptop), so
 * the production check must refuse it. The opposite case - a limited account
 * that passes - needs two database accounts; it was checked against
 * PostgreSQL 16 for this change (docs/security-accounts-and-uploads.md) and
 * is part of IT's go-live check.
 */
class DatabaseAccountVerifierTest extends ApiTestBase {

    @Test
    void anAccountThatOwnsTheTablesIsRefused() {
        assertThat(DatabaseAccountVerifier.problems(jdbc))
                .anyMatch(p -> p.contains("owns"))
                .anyMatch(p -> p.contains("audit log"));
    }

    @Test
    void theGrantsAreSkippedWhenOneAccountDoesEverything() {
        // afterMigrate.sql ran with an empty approle: the test account still owns its
        // tables and nothing failed.
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname = 'public'"
                + " and tableowner = current_user", Long.class)).isPositive();
    }
}
