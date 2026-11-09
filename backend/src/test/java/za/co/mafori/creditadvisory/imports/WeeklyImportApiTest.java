package za.co.mafori.creditadvisory.imports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.imports.support.FakeWeeklyRegisterWorkbook;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * /api/imports end to end (stage 3): upload the FAKE weekly register, map the
 * unknown
 * consultant, confirm, replace the week, cancel - through HTTP, security, POI
 * and the
 * database. The real workbook is never used.
 */
class WeeklyImportApiTest extends ApiTestBase {

    private static final String FILE_NAME = "Credit Advisory Online Dashboard (14 - 18 Sept 2026).xlsx";
    private static final String PREVIEW = "/api/imports/weekly-register/preview";

    private final FakeWeeklyRegisterWorkbook.Expected expected = FakeWeeklyRegisterWorkbook.expected();

    /**
     * Built ONCE: POI writes the current time into every .xlsx, so two builds a
     * second apart have different bytes (and a different digest).
     */
    private static final byte[] WORKBOOK = FakeWeeklyRegisterWorkbook.build();
    private long consultantOne;
    private ApiClient admin;

    @BeforeEach
    void createStaff() {
        consultantOne = createConsultant("C001", "Test Consultant One");
        createConsultant("C002", "TEST CONSULTANT TWO");
        createConsultant("C003", "TEST CONSULTANT THREE");
        setConsultantStatus(createConsultant("C004", "TEST CONSULTANT FOUR"), ConsultantStatus.SUSPENDED);
        createConsultant("C005", "TESTLABEL");
        createUser("manager@test.local", null, RoleName.MANAGER);
        admin = signIn(ADMIN_EMAIL);
    }

    private ApiClient.Response preview(ApiClient as) {
        return as.upload(PREVIEW, FILE_NAME, WORKBOOK);
    }

    private ApiClient.Response confirm(ApiClient as, long id, String digest, String reason) {
        return as.post("/api/imports/" + id + "/confirm", reason == null
                ? "{\"fileDigest\":\"%s\"}".formatted(digest)
                : "{\"fileDigest\":\"%s\",\"replaceReason\":\"%s\"}".formatted(digest, reason));
    }

    private ApiClient.Response mapUnknownConsultant(long id) {
        return admin.post("/api/imports/" + id + "/consultant-mappings", """
                {"nameInSheet":"%s","consultantId":%d}""".formatted(FakeWeeklyRegisterWorkbook.UNMAPPED_CONSULTANT,
                consultantOne));
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void previewMapAndConfirmTheFakeWeek() {
        ApiClient.Response preview = preview(admin);
        assertThat(preview.status()).as(preview.body()).isEqualTo(201);
        long id = preview.number("$.id");
        String digest = preview.text("$.fileDigest");
        assertThat(preview.text("$.weekStarting")).isEqualTo("2026-09-14");
        assertThat(preview.number("$.totalRows")).isEqualTo(expected.totalRows());
        assertThat(preview.number("$.rejectedRows")).isZero();
        assertThat(preview.size("$.days")).isEqualTo(7);
        assertThat(preview.texts("$.unmappedConsultants[*].nameInSheet"))
                .containsExactly(FakeWeeklyRegisterWorkbook.UNMAPPED_CONSULTANT);
        assertThat(preview.texts("$.blockers")).containsExactly("UNMAPPED_CONSULTANTS");
        assertThat(count("select count(*) from clients")).as("preview writes no clients").isZero();

        assertThat(confirm(admin, id, digest, null).status()).isEqualTo(422);
        ApiClient.Response mapped = mapUnknownConsultant(id);
        assertThat(mapped.status()).as(mapped.body()).isEqualTo(200);
        assertThat(mapped.texts("$.blockers")).isEmpty();

        ApiClient.Response rows = admin.get("/api/imports/" + id + "/rows?sheet=Monday&size=5");
        assertThat(rows.status()).isEqualTo(200);
        assertThat(rows.number("$.totalItems")).isEqualTo(12);
        assertThat(rows.text("$.items[0].idNumberMasked")).matches("\\*{9}\\d{4}");
        assertThat(admin.get("/api/imports/" + id + "/issues?severity=WARNING").status()).isEqualTo(200);

        ApiClient.Response confirmed = confirm(admin, id, digest, null);
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        assertThat(confirmed.number("$.transactions")).isEqualTo(expected.totalRows());
        assertThat(confirmed.number("$.newClients")).isEqualTo(expected.distinctPeople());

        assertThat(count("select count(*) from client_transactions")).isEqualTo(expected.totalRows());
        assertThat(count("select count(*) from clients")).isEqualTo(expected.distinctPeople());
        assertThat(count("select count(*) from clients c join consultants k on k.id = c.current_consultant_id"
                + " where k.consultant_code = 'C004'")).as("suspended consultant gets no clients").isZero();
        assertThat(count("select count(*) from client_transactions where consultant_id is null")).isEqualTo(3);
        assertThat(count("select banked_total::bigint from v_week_daily_summary where day_of_week = 1"))
                .isEqualTo(expected.sheetBanked().get("Monday"));
        assertThat(count("select count(*) from import_rows where first_name is not null or id_number_hash is not null"))
                .as("personal data purged after confirm").isZero();
        assertThat(admin.get("/api/imports/" + id).text("$.state")).isEqualTo("COMMITTED");
        assertThat(preview(admin).status()).as("same file twice").isEqualTo(409);
    }

    @Test
    void confirmIsAllOrNothing() {
        ApiClient.Response preview = preview(admin);
        long id = preview.number("$.id");
        assertThat(mapUnknownConsultant(id).status()).isEqualTo(200);
        // Make the database refuse the first row's extra column: the whole confirm must
        // roll back.
        jdbc.execute("""
                create function test_refuse_extras() returns trigger language plpgsql as
                $$ begin raise exception 'refused for the test'; end; $$""");
        jdbc.execute("""
                create trigger test_refuse_extras before insert on client_transaction_extras
                for each row execute function test_refuse_extras()""");

        ApiClient.Response confirmed = confirm(admin, id, preview.text("$.fileDigest"), null);

        assertThat(confirmed.status()).isGreaterThanOrEqualTo(400);
        assertThat(count("select count(*) from clients")).isZero();
        assertThat(count("select count(*) from client_transactions")).isZero();
        assertThat(count("select count(*) from reporting_weeks where current_import_job_id is not null")).isZero();
        assertThat(admin.get("/api/imports/" + id).text("$.state")).isEqualTo("PREVIEWED");
    }

    @Test
    void replacingAWeekNeedsAReason() {
        ApiClient.Response first = preview(admin);
        long firstId = first.number("$.id");
        mapUnknownConsultant(firstId);
        assertThat(confirm(admin, firstId, first.text("$.fileDigest"), null).status()).isEqualTo(200);
        // Pretend the next upload is a corrected file (the fake generator builds
        // identical bytes).
        jdbc.update("update import_jobs set file_digest = repeat('e', 64) where id = ?", firstId);

        ApiClient.Response second = preview(admin);
        assertThat(second.status()).as(second.body()).isEqualTo(201);
        long secondId = second.number("$.id");
        assertThat(second.value("$.replaceRequired")).isEqualTo(true);
        assertThat(second.number("$.weekImportedByJobId")).isEqualTo(firstId);
        assertThat(second.number("$.existingClients")).isEqualTo(expected.distinctPeople());

        String digest = second.text("$.fileDigest");
        assertThat(confirm(admin, secondId, digest, null).status()).isEqualTo(422);
        ApiClient.Response replaced = confirm(admin, secondId, digest, "Corrected Tuesday");
        assertThat(replaced.status()).as(replaced.body()).isEqualTo(200);
        assertThat(replaced.number("$.supersededTransactions")).isEqualTo(expected.totalRows());

        assertThat(count("select count(*) from client_transactions")).isEqualTo(2 * expected.totalRows());
        assertThat(count("select transaction_count from v_week_totals")).isEqualTo(expected.totalRows());
        assertThat(count("select count(*) from audit_logs where action = 'WEEK_REPLACED'")).isEqualTo(1);
    }

    @Test
    void cancelThrowsThePreviewAway() {
        long id = preview(admin).number("$.id");
        ApiClient.Response cancelled = admin.post("/api/imports/" + id + "/cancel", null);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.text("$.state")).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from import_rows where first_name is not null")).isZero();
        assertThat(admin.post("/api/imports/" + id + "/cancel", null).status()).isEqualTo(409);
        assertThat(admin.get("/api/imports").number("$.totalItems")).isEqualTo(1);
    }

    @Test
    void expiredPreviewCannotBeConfirmed() {
        ApiClient.Response preview = preview(admin);
        long id = preview.number("$.id");
        jdbc.update("update import_jobs set expires_at = now() - interval '1 minute' where id = ?", id);
        assertThat(admin.get("/api/imports/" + id).text("$.state")).isEqualTo("EXPIRED");
        assertThat(confirm(admin, id, preview.text("$.fileDigest"), null).status()).isEqualTo(409);
    }

    @Test
    void badUploadsAreRefused() {
        assertThat(admin.upload(PREVIEW + "?weekStarting=2026-09-14", "register.xlsx", "NAME,SURNAME".getBytes())
                .text("$.type"))
                .isEqualTo("urn:credit-advisory:file:not-xlsx");
        assertThat(admin.upload(PREVIEW + "?weekStarting=2026-09-15", "r.xlsx", FakeWeeklyRegisterWorkbook.build())
                .text("$.type")).isEqualTo("urn:credit-advisory:import:week-not-monday");
        assertThat(admin.upload(PREVIEW, "register.xlsx", FakeWeeklyRegisterWorkbook.build()).text("$.type"))
                .isEqualTo("urn:credit-advisory:import:week-required");
        assertThat(
                admin.get("/api/imports/weekly-register/week-guess?fileName=" + "Register%2014-18%20Sept%202026.xlsx")
                        .text("$.weekStarting"))
                .isEqualTo("2026-09-14");
    }

    @Test
    void onlyAnAdminCanImport() {
        ApiClient manager = signIn("manager@test.local");
        assertThat(preview(manager).status()).isEqualTo(403);
        assertThat(manager.get("/api/imports").status()).isEqualTo(403);
        assertThat(manager.get("/api/imports/weekly-register/week-guess?fileName=x.xlsx").status()).isEqualTo(403);

        long consultantId = createConsultant("C009", "Some Consultant");
        createUser("consultant@test.local", consultantId, RoleName.CONSULTANT);
        ApiClient consultant = signIn("consultant@test.local");
        assertThat(preview(consultant).status()).isEqualTo(403);
        assertThat(consultant.get("/api/imports").status()).isEqualTo(403);
    }
}
