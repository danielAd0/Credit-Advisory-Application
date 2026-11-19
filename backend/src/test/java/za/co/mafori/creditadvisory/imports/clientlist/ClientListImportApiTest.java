package za.co.mafori.creditadvisory.imports.clientlist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * /api/imports/client-list end to end, on a FAKE client list
 * (FakeClientListWorkbook): preview, confirm, what lands in the database, the
 * issues download, cancel, and who may do it.
 */
class ClientListImportApiTest extends ApiTestBase {

        private static final String PREVIEW = "/api/imports/client-list/preview";
        private static final String FILE = "Client list (fake).xlsx";

        private final String id1 = fakeIdNumber(501);
        private final String id2 = fakeIdNumber(502);
        private final String id3 = fakeIdNumber(503);
        private long consultantId;
        private ApiClient admin;

        @BeforeEach
        void createStaff() {
                consultantId = createConsultant("C001", "Test Consultant One");
                admin = signIn(ADMIN_EMAIL);
        }

        private byte[] workbook() {
                return FakeClientListWorkbook.build(id1, id2, id3, "test consultant one");
        }

        private ApiClient.Response preview(ApiClient as, byte[] bytes) {
                return as.upload(PREVIEW, FILE, bytes);
        }

        private ApiClient.Response confirm(ApiClient.Response preview) {
                return admin.post("/api/imports/client-list/" + preview.number("$.id") + "/confirm",
                                "{\"fileDigest\":\"%s\",\"consultantsFingerprint\":\"%s\"}".formatted(
                                                preview.text("$.fileDigest"),
                                                preview.text("$.consultants.fingerprint")));
        }

        private long count(String sql, Object... args) {
                return jdbc.queryForObject(sql, Long.class, args);
        }

        @Test
        void previewShowsWhatWillHappenAndChangesNothing() {
                ApiClient.Response p = preview(admin, workbook());

                assertThat(p.status()).as(p.body()).isEqualTo(201);
                assertThat(p.header("Location")).isEqualTo("/api/imports/client-list/" + p.number("$.id"));
                assertThat(p.text("$.state")).isEqualTo("PREVIEWED");
                assertThat(p.text("$.sheetName")).isEqualTo("Clients");
                assertThat(p.number("$.rows.total")).isEqualTo(5);
                assertThat(p.number("$.rows.newClients")).isEqualTo(3);
                assertThat(p.number("$.rows.duplicate")).isEqualTo(1);
                assertThat(p.number("$.rows.invalid")).isEqualTo(1);
                assertThat(p.number("$.consultants.matched[0].consultantId")).isEqualTo(consultantId);
                assertThat(p.texts("$.newProductQuotations")).containsExactly("Test Branch North");
                assertThat(p.texts("$.newEmployers")).containsExactly("Test Local Municipality");
                assertThat(p.number("$.additions.debitOrders")).isEqualTo(1);
                assertThat(p.value("$.canConfirm")).isEqualTo(true);
                assertThat(p.body()).doesNotContainPattern("\\d{13}");

                assertThat(count("select count(*) from clients")).isZero();
                assertThat(count("select count(*) from audit_logs where action = 'IMPORT_PREVIEWED'"
                                + " and metadata ->> 'importType' = 'CLIENT_LIST'")).isEqualTo(1);
        }

        @Test
        void confirmCreatesTheClientsWithTheirDetails() {
                // Built once: each build is stamped with the time, so two builds can differ by
                // a byte.
                byte[] file = workbook();
                ApiClient.Response p = preview(admin, file);
                ApiClient.Response c = confirm(p);

                assertThat(c.status()).as(c.body()).isEqualTo(200);
                assertThat(c.number("$.newClients")).isEqualTo(3);
                assertThat(c.number("$.assigned")).isEqualTo(2);
                assertThat(c.number("$.productQuotationsCreated")).isEqualTo(1);
                assertThat(c.number("$.employersCreated")).isEqualTo(1);
                assertThat(c.number("$.skippedRows")).isEqualTo(2);

                long alpha = count("select id from clients where last_name = 'Alpha'");
                assertThat(jdbc.queryForObject("select concat_ws('|', email, phone, work_phone, file_number, file_id)"
                                + " from clients where id = ?", String.class, alpha))
                                .isEqualTo("alpha@example.test|+27821234567|+27115550000|1001|2001");
                assertThat(count("select current_consultant_id from clients where id = ?", alpha))
                                .isEqualTo(consultantId);
                assertThat(idNumberProtector.decrypt(jdbc.queryForObject(
                                "select id_number_encrypted from clients where id = ?", byte[].class, alpha)))
                                .isEqualTo(SaIdNumber.parse(id1));
                assertThat(jdbc.queryForObject("select payment_method from clients where last_name = 'Bravo'",
                                String.class)).isEqualTo("DEBIT_ORDER");
                assertThat(count("select count(*) from clients where last_name = 'Bravo' and employer_id is null"))
                                .isEqualTo(1);
                assertThat(jdbc.queryForObject("select home_phone from clients where last_name = 'Charlie'",
                                String.class))
                                .isEqualTo("+27215550000");
                assertThat(count("select count(*) from clients where last_name = 'Charlie' and email is null"
                                + " and file_number is null and current_consultant_id is null")).isEqualTo(1);
                assertThat(count("select count(*) from client_assignment_history where change_type = 'IMPORT'"
                                + " and import_job_id = ?", p.number("$.id"))).isEqualTo(2);
                assertThat(count("select count(*) from audit_logs where action = 'CLIENT_CREATED'"
                                + " and metadata ->> 'source' = 'CLIENT_LIST'")).isEqualTo(3);
                assertThat(count("select count(*) from import_rows where import_job_id = ? and (first_name is not null"
                                + " or id_number_hash is not null or phone is not null)", p.number("$.id"))).isZero();

                ApiClient.Response again = admin.get("/api/imports/client-list/" + p.number("$.id"));
                assertThat(again.text("$.state")).isEqualTo("COMMITTED");
                assertThat(again.number("$.result.newClients")).isEqualTo(3);
                assertThat(confirm(p).status()).isEqualTo(409);
                assertThat(preview(admin, file).status()).as("the same file twice").isEqualTo(409);
        }

        @Test
        void theRealListsHeadersAreRecognised() {
                String id = fakeIdNumber(504);

                ApiClient.Response p = preview(admin, FakeClientListWorkbook.buildWithRealHeaders(id));

                assertThat(p.status()).as(p.body()).isEqualTo(201);
                assertThat(p.number("$.rows.total")).isEqualTo(1);
                assertThat(p.number("$.rows.newClients")).isEqualTo(1);
                assertThat(p.value("$.canConfirm")).isEqualTo(true);

                ApiClient.Response c = confirm(p);

                assertThat(c.status()).as(c.body()).isEqualTo(200);
                assertThat(c.number("$.newClients")).isEqualTo(1);
                assertThat(c.number("$.assigned")).as("the real list has no consultant column").isZero();
                assertThat(jdbc.queryForObject(
                                "select concat_ws('|', first_name, last_name, phone, work_phone, home_phone,"
                                                + " file_number, file_id) from clients",
                                String.class))
                                .isEqualTo("Test|Echo|+27825550003|+27115550001|+27115550002|3001|4001");
                assertThat(count("select count(*) from clients where current_consultant_id is null")).isEqualTo(1);
                assertThat(idNumberProtector.decrypt(jdbc.queryForObject("select id_number_encrypted from clients",
                                byte[].class))).isEqualTo(SaIdNumber.parse(id));
        }

        @Test
        void confirmNeedsTheConsultantListTheAdminSaw() {
                ApiClient.Response p = preview(admin, workbook());
                long id = p.number("$.id");

                ApiClient.Response wrong = admin.post("/api/imports/client-list/" + id + "/confirm",
                                "{\"fileDigest\":\"%s\",\"consultantsFingerprint\":\"%s\"}".formatted(
                                                p.text("$.fileDigest"),
                                                "0".repeat(64)));
                assertThat(wrong.status()).isEqualTo(409);
                assertThat(wrong.text("$.type")).isEqualTo("urn:credit-advisory:import:consultants-changed");

                ApiClient.Response missing = admin.post("/api/imports/client-list/" + id + "/confirm",
                                "{\"fileDigest\":\"%s\"}".formatted(p.text("$.fileDigest")));
                assertThat(missing.status()).isEqualTo(400);
                assertThat(count("select count(*) from clients")).isZero();
        }

        @Test
        void issuesCanBeListedAndDownloadedWithoutPersonalData() {
                long id = preview(admin, workbook()).number("$.id");

                ApiClient.Response issues = admin.get("/api/imports/client-list/" + id + "/issues?severity=WARNING");
                assertThat(issues.status()).isEqualTo(200);
                assertThat(issues.texts("$.items[*].code")).contains("row:duplicate-id-number", "row:invalid-email",
                                "row:invalid-file-reference");

                ApiClient.Response file = admin.get("/api/imports/client-list/" + id + "/issues/export");
                assertThat(file.status()).isEqualTo(200);
                assertThat(file.header("Content-Type")).startsWith("text/csv");
                assertThat(file.body()).doesNotContainPattern("\\d{13}");
                assertThat(file.body()).doesNotContain("Alpha", "Bravo", "Charlie", "Delta");

                ApiClient.Response rows = admin.get("/api/imports/client-list/" + id + "/rows?action=NEW");
                assertThat(rows.number("$.totalItems")).isEqualTo(3);
                assertThat(rows.text("$.items[0].idNumber")).startsWith("*********");
        }

        @Test
        void cancelDeletesTheStagedPersonalData() {
                long id = preview(admin, workbook()).number("$.id");

                ApiClient.Response cancelled = admin.post("/api/imports/client-list/" + id + "/cancel", "{}");
                assertThat(cancelled.status()).isEqualTo(200);
                assertThat(cancelled.text("$.state")).isEqualTo("CANCELLED");
                assertThat(count("select count(*) from import_rows where import_job_id = ? and (first_name is not null"
                                + " or id_number_hash is not null)", id)).isZero();
                assertThat(count("select count(*) from clients")).isZero();
        }

        @Test
        void onlyAnAdminCanImportTheClientList() {
                createUser("manager@test.local", null, RoleName.MANAGER);
                createUser("consultant@test.local", consultantId, RoleName.CONSULTANT);
                long id = preview(admin, workbook()).number("$.id");

                for (String email : new String[] { "manager@test.local", "consultant@test.local" }) {
                        ApiClient as = signIn(email);
                        assertThat(preview(as, workbook()).status()).isEqualTo(403);
                        assertThat(as.get("/api/imports/client-list").status()).isEqualTo(403);
                        assertThat(as.get("/api/imports/client-list/" + id).status()).isEqualTo(403);
                        assertThat(as.post("/api/imports/client-list/" + id + "/cancel", "{}").status()).isEqualTo(403);
                }
                assertThat(browser().get("/api/imports/client-list").status()).isEqualTo(401);
                assertThat(admin.get("/api/imports/client-list").number("$.totalItems")).isEqualTo(1);
                assertThat(admin.get("/api/imports").number("$.totalItems")).as("the weekly list stays apart").isZero();
        }
}
