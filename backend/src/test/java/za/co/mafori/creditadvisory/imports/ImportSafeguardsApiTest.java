package za.co.mafori.creditadvisory.imports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.imports.support.FakeWeeklyRegisterWorkbook;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * The import's safeguards, end to end on the FAKE workbook:
 * <ul>
 * <li>a re-import never undoes a manager's transfer or unassign (spec 6.2:
 * "Reimporting the same
 * worksheet does not ... silently reverse a manager-approved transfer");</li>
 * <li>an ID number stored under another name blocks confirm until a manager
 * approves it;</li>
 * <li>every client created by confirm has its own CLIENT_CREATED audit
 * entry;</li>
 * <li>the issues can be downloaded as a CSV file (FR-12) without names or full
 * ID numbers.</li>
 * </ul>
 */
class ImportSafeguardsApiTest extends ApiTestBase {

    private static final String FILE_NAME = "Credit Advisory Online Dashboard (14 - 18 Sept 2026).xlsx";
    private static final String PREVIEW = "/api/imports/weekly-register/preview";

    private long one;
    private long two;
    private ApiClient manager;
    private ApiClient admin;

    @BeforeEach
    void createStaff() {
        one = createConsultant("C001", "Test Consultant One");
        two = createConsultant("C002", "TEST CONSULTANT TWO");
        createConsultant("C003", "TEST CONSULTANT THREE");
        createConsultant("C004", "TEST CONSULTANT FOUR");
        createConsultant("C005", "TESTLABEL");
        createUser("manager@test.local", null, RoleName.MANAGER);
        manager = signIn("manager@test.local");
        admin = signIn(ADMIN_EMAIL);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private ApiClient.Response preview(ApiClient as) {
        ApiClient.Response r = as.upload(PREVIEW, FILE_NAME, FakeWeeklyRegisterWorkbook.build());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r;
    }

    private ApiClient.Response confirm(ApiClient as, ApiClient.Response preview, String reason, boolean accept) {
        String body = "{\"fileDigest\":\"%s\"%s%s}".formatted(preview.text("$.fileDigest"),
                reason == null ? "" : ",\"replaceReason\":\"" + reason + "\"",
                accept ? ",\"acceptNameDifferences\":true" : "");
        return as.post("/api/imports/" + preview.number("$.id") + "/confirm", body);
    }

    /**
     * Imports the fake week, then makes the next upload of the same bytes count as
     * a corrected file.
     */
    private long importOnce() {
        ApiClient.Response p = preview(manager);
        long id = p.number("$.id");
        assertThat(manager
                .post("/api/imports/" + id + "/consultant-mappings",
                        """
                                {"nameInSheet":"%s","consultantId":%d}"""
                                .formatted(FakeWeeklyRegisterWorkbook.UNMAPPED_CONSULTANT, one))
                .status()).isEqualTo(200);
        ApiClient.Response c = confirm(manager, p, null, false);
        assertThat(c.status()).as(c.body()).isEqualTo(200);
        jdbc.update("update import_jobs set file_digest = repeat('e', 64) where id = ?", id);
        return id;
    }

    private long aClientOf(long consultantId) {
        return count("select min(id) from clients where current_consultant_id = ?", consultantId);
    }

    private long consultantOf(long clientId) {
        Long c = jdbc.queryForObject("select current_consultant_id from clients where id = ?", Long.class, clientId);
        return c == null ? -1 : c;
    }

    @Test
    void reimportDoesNotUndoATransfer() {
        importOnce();
        long client = aClientOf(one);
        ApiClient.Response transfer = manager.post("/api/client-transfers", """
                {"fromConsultantId":%d,"toConsultantId":%d,"clientIds":[%d],"reason":"Handover"}"""
                .formatted(one, two, client));
        assertThat(transfer.status()).as(transfer.body()).isEqualTo(200);
        long history = count("select count(*) from client_assignment_history where client_id = ?", client);

        ApiClient.Response again = preview(admin);
        long againId = again.number("$.id");
        assertThat(count(
                "select count(*) from import_errors where import_job_id = ? and error_code = 'client:consultant-differs'",
                againId)).isPositive();
        ApiClient.Response replaced = confirm(admin, again, "Corrected file", false);
        assertThat(replaced.status()).as(replaced.body()).isEqualTo(200);

        assertThat(consultantOf(client)).as("still with the consultant it was transferred to").isEqualTo(two);
        assertThat(count("select count(*) from client_assignment_history where client_id = ?", client))
                .as("the re-import adds no assignment change").isEqualTo(history);
        assertThat(count("select count(*) from client_transactions where client_id = ? and import_job_id = ?"
                + " and consultant_id = ?", client, againId, one))
                .as("the sale still counts for the consultant in the sheet").isPositive();
    }

    @Test
    void transferBetweenPreviewAndConfirmIsKept() {
        importOnce();
        long client = aClientOf(one);
        ApiClient.Response again = preview(admin);
        // The manager moves the client while the preview is open.
        assertThat(manager.post("/api/client-transfers", """
                {"fromConsultantId":%d,"toConsultantId":%d,"clientIds":[%d],"reason":"Handover"}"""
                .formatted(one, two, client)).status()).isEqualTo(200);

        assertThat(confirm(admin, again, "Corrected file", false).status()).isEqualTo(200);
        assertThat(consultantOf(client)).isEqualTo(two);
    }

    @Test
    void reimportDoesNotUndoAnUnassign() {
        importOnce();
        long client = aClientOf(one);
        ApiClient.Response unassign = manager.post("/api/clients/" + client + "/unassign", """
                {"reason":"Left the scheme"}""");
        assertThat(unassign.status()).as(unassign.body()).isEqualTo(200);

        ApiClient.Response again = preview(admin);
        assertThat(count(
                "select count(*) from import_errors where import_job_id = ? and error_code = 'client:unassigned-by-manager'",
                again.number("$.id"))).isPositive();
        assertThat(confirm(admin, again, "Corrected file", false).status()).isEqualTo(200);

        assertThat(count("select count(*) from clients where id = ? and current_consultant_id is null", client))
                .as("stays unassigned").isEqualTo(1);
    }

    @Test
    void anIdNumberUnderAnotherNameNeedsApproval() {
        importOnce();
        long client = aClientOf(one);
        jdbc.update("update clients set first_name = 'Someone', last_name = 'Else' where id = ?", client);

        ApiClient.Response again = preview(admin);
        assertThat(again.number("$.nameDifferences")).isEqualTo(1);
        assertThat(again.texts("$.blockers")).contains("NAME_DIFFERENCES");
        assertThat(again.value("$.canConfirm")).isEqualTo(false);

        ApiClient.Response refused = confirm(admin, again, "Corrected file", false);
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.text("$.type")).isEqualTo("urn:credit-advisory:import:name-differences-unconfirmed");
        assertThat(manager.get("/api/imports/" + again.number("$.id")).text("$.state")).isEqualTo("PREVIEWED");

        ApiClient.Response approved = confirm(admin, again, "Corrected file", true);
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select first_name from clients where id = ?", String.class, client))
                .as("the stored name is never overwritten").isEqualTo("Someone");
        assertThat(count("select count(*) from audit_logs where action = 'IMPORT_CONFIRMED' and target_id = ?"
                + " and metadata ->> 'nameDifferencesAccepted' = '1'", String.valueOf(again.number("$.id"))))
                .isEqualTo(1);
    }

    @Test
    void everyClientCreatedByConfirmIsAudited() {
        ApiClient.Response p = preview(manager);
        long id = p.number("$.id");
        manager.post("/api/imports/" + id + "/consultant-mappings", """
                {"nameInSheet":"%s","consultantId":%d}""".formatted(FakeWeeklyRegisterWorkbook.UNMAPPED_CONSULTANT,
                one));
        ApiClient.Response c = confirm(manager, p, null, false);
        assertThat(c.status()).isEqualTo(200);

        assertThat(count("select count(*) from audit_logs where action = 'CLIENT_CREATED'"
                + " and metadata ->> 'importJobId' = ?", String.valueOf(id))).isEqualTo(c.number("$.newClients"));
        assertThat(count("select count(*) from audit_logs a join clients k on k.id::text = a.target_id"
                + " where a.action = 'CLIENT_CREATED' and k.created_by_import_job_id = ?", id))
                .isEqualTo(c.number("$.newClients"));
    }

    @Test
    void issuesCanBeDownloadedAsACsvFile() {
        long id = preview(manager).number("$.id");

        ApiClient.Response file = manager.get("/api/imports/" + id + "/issues/export");
        assertThat(file.status()).isEqualTo(200);
        assertThat(file.header("Content-Type")).startsWith("text/csv");
        assertThat(file.header("Content-Disposition"))
                .isEqualTo("attachment; filename=\"import-" + id + "-2026-09-14-issues.csv\"");
        String[] lines = file.body().replace("﻿", "").split("\r\n");
        assertThat(lines[0])
                .isEqualTo("\"Sheet\",\"Row\",\"Field\",\"Severity\",\"Code\",\"Message\",\"ID number (last 4)\"");
        assertThat(lines.length - 1L)
                .isEqualTo(count("select count(*) from import_errors where import_job_id = ?", id));
        assertThat(file.body()).doesNotContainPattern("\\d{13}");
        assertThat(FakeWeeklyRegisterWorkbook.allRows())
                .noneMatch(r -> r.surname().strip().length() > 4 && file.body().contains(r.surname().strip()));

        ApiClient.Response warnings = manager.get("/api/imports/" + id + "/issues/export?severity=WARNING");
        assertThat(warnings.body().split("\r\n").length - 1L)
                .isEqualTo(count("select count(*) from import_errors where import_job_id = ? and severity = 'WARNING'",
                        id));
        assertThat(count("select count(*) from audit_logs where action = 'IMPORT_ISSUES_EXPORTED'")).isEqualTo(2);

        long consultantId = createConsultant("C009", "Some Consultant");
        createUser("consultant@test.local", consultantId, RoleName.CONSULTANT);
        assertThat(signIn("consultant@test.local").get("/api/imports/" + id + "/issues/export").status())
                .isEqualTo(403);
    }
}
