package za.co.mafori.creditadvisory.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/** GET /api/audit-logs/export: the audit trail as a CSV file (ADMIN only). */
class AuditLogExportApiTest extends ApiTestBase {

    @Autowired
    private NamedParameterJdbcTemplate named;

    @Autowired
    private AuditLogWriter writer;

    private ApiClient admin;
    private long consultantId;

    /** Two known entries: CONSULTANT_CREATED, then CONSULTANT_STATUS_CHANGED. */
    @BeforeEach
    void createEntries() {
        admin = signIn(ADMIN_EMAIL);
        ApiClient.Response created = admin.post("/api/consultants", """
                {"consultantCode":"C001","fullName":"Thandi Test"}""");
        consultantId = created.number("$.id");
        admin.patch("/api/consultants/" + consultantId + "/status", """
                {"status":"SUSPENDED","reason":"Under investigation","version":%d}"""
                .formatted(created.number("$.version")));
    }

    private long exported() {
        return jdbc.queryForObject("select count(*) from audit_logs where action = 'AUDIT_LOG_EXPORTED'",
                Long.class);
    }

    @Test
    void theWholeTrailOldestFirstAsAFile() {
        ApiClient.Response file = admin.get("/api/audit-logs/export");

        assertThat(file.status()).as(file.body()).isEqualTo(200);
        assertThat(file.header("Content-Type")).startsWith("text/csv");
        assertThat(file.header("Content-Disposition")).startsWith("attachment; filename=\"audit-log-");
        assertThat(file.header("Cache-Control")).contains("no-store");

        String body = file.body();
        assertThat(body).startsWith("\uFEFFTime (South Africa),Action,By,By user id,Target type,Target id,Details\r\n");
        assertThat(body.indexOf("CONSULTANT_CREATED")).as("oldest first")
                .isLessThan(body.indexOf("CONSULTANT_STATUS_CHANGED"));
        assertThat(body).contains("," + ADMIN_EMAIL + "," + adminId + ",CONSULTANT," + consultantId + ",")
                .contains("Under investigation");
    }

    @Test
    void theSameFiltersAsTheList() {
        String body = admin.get("/api/audit-logs/export?action=consultant_status_changed").body();

        assertThat(body).contains("CONSULTANT_STATUS_CHANGED").doesNotContain("CONSULTANT_CREATED");

        String later = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        assertThat(admin.get("/api/audit-logs/export?from=" + later).body().split("\r\n"))
                .as("only the header").hasSize(1);
        assertThat(admin.get("/api/audit-logs/export?from=" + later + "&to=" + later).status()).isEqualTo(400);
    }

    @Test
    void theExportIsAuditedButNotInItsOwnFile() {
        String body = admin.get("/api/audit-logs/export").body();

        assertThat(body).doesNotContain("AUDIT_LOG_EXPORTED");
        assertThat(exported()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select metadata ->> 'rows' from audit_logs"
                + " where action = 'AUDIT_LOG_EXPORTED'", String.class)).isEqualTo("2");
        assertThat(jdbc.queryForObject("select actor_user_id from audit_logs where action = 'AUDIT_LOG_EXPORTED'",
                Long.class)).isEqualTo(adminId);

        assertThat(admin.get("/api/audit-logs/export").body()).as("the next file has the first export")
                .contains("AUDIT_LOG_EXPORTED");
    }

    @Test
    void aValueThatLooksLikeAFormulaStaysText() {
        writer.record("TEST_ENTRY", "TEST", "=1+1", AuditLogWriter.meta());

        String body = admin.get("/api/audit-logs/export?action=TEST_ENTRY").body();

        assertThat(body).contains(",'=1+1,").doesNotContain(",=1+1,");
    }

    @Test
    void tooManyEntriesForOneFile() {
        AuditLogExportService small = new AuditLogExportService(named, writer, 1);

        assertThatThrownBy(() -> small.export(new AuditLogQuery(null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("at most 1");
        assertThat(exported()).as("nothing was exported").isZero();
    }

    @Test
    void onlyAnAdmin() {
        createUser("manager@test.local", null, RoleName.MANAGER);

        assertThat(signIn("manager@test.local").get("/api/audit-logs/export").status()).isEqualTo(403);
        assertThat(exported()).isZero();
    }
}
