package za.co.mafori.creditadvisory.imports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.imports.support.FakeWeeklyRegisterWorkbook;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * Only an admin runs imports, but a manager can see when one has been uploaded:
 * the import list and each import's summary (no client data in either) are
 * open to MANAGER, read-only. Everything else - rows, issues, the CSV download,
 * the workbook check, the consultant check, confirm, cancel, name spellings -
 * stays ADMIN only. A CONSULTANT sees none of it.
 */
class ImportManagerViewApiTest extends ApiTestBase {

    private static final String FILE_NAME = "Credit Advisory Online Dashboard (14 - 18 Sept 2026).xlsx";
    private static final String PREVIEW = "/api/imports/weekly-register/preview";
    private static final String HEX = "a".repeat(64);

    private ApiClient admin;
    private ApiClient manager;

    @BeforeEach
    void createStaff() {
        createConsultant("C001", "Test Consultant One");
        createConsultant("C002", "TEST CONSULTANT TWO");
        createConsultant("C003", "TEST CONSULTANT THREE");
        createConsultant("C004", "TEST CONSULTANT FOUR");
        createConsultant("C005", "TESTLABEL");
        createUser("manager@test.local", null, RoleName.MANAGER);
        admin = signIn(ADMIN_EMAIL);
        manager = signIn("manager@test.local");
    }

    private long previewAsAdmin() {
        ApiClient.Response p = admin.upload(PREVIEW, FILE_NAME, FakeWeeklyRegisterWorkbook.build());
        assertThat(p.status()).as(p.body()).isEqualTo(201);
        return p.number("$.id");
    }

    /**
     * The admin imports the fake week: preview, "Confirm Consultants and Proceed",
     * confirm.
     */
    private long importTheFakeWeek() {
        long id = previewAsAdmin();
        assertThat(confirmConsultants(admin, id).status()).isEqualTo(200);
        ApiClient.Response c = admin.post("/api/imports/" + id + "/confirm",
                "{\"fileDigest\":\"%s\"}".formatted(admin.get("/api/imports/" + id).text("$.fileDigest")));
        assertThat(c.status()).as(c.body()).isEqualTo(200);
        return id;
    }

    @Test
    void aManagerSeesTheUploadsAndTheirSummary() {
        long id = importTheFakeWeek();

        ApiClient.Response list = manager.get("/api/imports");
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(list.number("$.totalItems")).isEqualTo(1);
        assertThat(list.number("$.items[0].id")).isEqualTo(id);
        assertThat(list.text("$.items[0].state")).isEqualTo("COMMITTED");
        assertThat(list.text("$.items[0].weekStarting")).isEqualTo("2026-09-14");
        assertThat(list.value("$.items[0].committedAt")).isNotNull();

        ApiClient.Response summary = manager.get("/api/imports/" + id);
        assertThat(summary.status()).as(summary.body()).isEqualTo(200);
        assertThat(summary.text("$.state")).isEqualTo("COMMITTED");
        assertThat(summary.size("$.days")).isEqualTo(7);
        // The file's SHA-256 (64 hex characters) can hold 13 digits in a row by chance:
        // left out of the check.
        assertThat(summary.body().replaceAll("[0-9a-f]{64}", "")).as("no full ID numbers in the summary")
                .doesNotContainPattern("\\d{13}");
        assertThat(FakeWeeklyRegisterWorkbook.allRows())
                .as("no client surname in the summary")
                .noneMatch(r -> r.surname().strip().length() > 4 && summary.body().contains(r.surname().strip()));
    }

    @Test
    void aManagerSeesAPreviewTheAdminHasNotConfirmedYet() {
        long id = previewAsAdmin();

        assertThat(manager.get("/api/imports").text("$.items[0].state")).isEqualTo("PREVIEWED");
        assertThat(manager.get("/api/imports/" + id).status()).isEqualTo(200);
        assertThat(count("select count(*) from audit_logs where action = 'IMPORT_CONFIRMED'"))
                .as("looking changes nothing").isZero();
    }

    @Test
    void aManagerCannotRunOrOpenTheImport() {
        long id = previewAsAdmin();
        String job = "/api/imports/" + id;

        assertThat(manager.upload(PREVIEW, FILE_NAME, FakeWeeklyRegisterWorkbook.build()).status()).isEqualTo(403);
        assertThat(manager.get("/api/imports/weekly-register/week-guess?fileName=x.xlsx").status()).isEqualTo(403);
        assertThat(manager.get(job + "/rows").status()).as("rows hold client names").isEqualTo(403);
        assertThat(manager.get(job + "/issues").status()).isEqualTo(403);
        assertThat(manager.get(job + "/issues/export").status()).isEqualTo(403);
        assertThat(manager.get(job + "/reconciliation").status()).isEqualTo(403);
        assertThat(manager.get(job + "/report-figures").status()).isEqualTo(403);
        // The request bodies are valid, so each 403 is the role check and not a 400.
        assertThat(manager.post(job + "/consultants/confirm", "{\"fingerprint\":\"%s\"}".formatted(HEX)).status())
                .isEqualTo(403);
        assertThat(manager.post(job + "/confirm", "{\"fileDigest\":\"%s\"}".formatted(HEX)).status())
                .isEqualTo(403);
        assertThat(manager.post(job + "/cancel", "{}").status()).isEqualTo(403);
        assertThat(manager.get("/api/consultant-aliases").status()).isEqualTo(403);

        assertThat(admin.get(job).text("$.state")).as("still open for the admin").isEqualTo("PREVIEWED");
    }

    @Test
    void aConsultantSeesNoImports() {
        long id = importTheFakeWeek();
        long consultantId = createConsultant("C009", "Some Consultant");
        createUser("consultant@test.local", consultantId, RoleName.CONSULTANT);
        ApiClient consultant = signIn("consultant@test.local");

        assertThat(consultant.get("/api/imports").status()).isEqualTo(403);
        assertThat(consultant.get("/api/imports/" + id).status()).isEqualTo(403);
        assertThat(browser().get("/api/imports").status()).as("signed out").isEqualTo(401);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
