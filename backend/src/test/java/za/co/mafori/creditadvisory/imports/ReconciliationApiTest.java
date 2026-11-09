package za.co.mafori.creditadvisory.imports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.imports.support.FakeWeeklyRegisterWorkbook;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * Stage 4 end to end: the preview stores the FAKE workbook's Report and
 * Breakdown figures
 * next to the recalculated ones, and the reconciliation endpoints serve them.
 */
class ReconciliationApiTest extends ApiTestBase {

        private static final String FILE_NAME = "Credit Advisory Online Dashboard (14 - 18 Sept 2026).xlsx";

        private ApiClient admin;
        private long consultantOne;

        @BeforeEach
        void createStaff() {
                consultantOne = createConsultant("C001", "Test Consultant One");
                createConsultant("C002", "TEST CONSULTANT TWO");
                createConsultant("C003", "TEST CONSULTANT THREE");
                createConsultant("C004", "TEST CONSULTANT FOUR");
                createConsultant("C005", "TESTLABEL");
                createUser("manager@test.local", null, RoleName.MANAGER);
                admin = signIn(ADMIN_EMAIL);
        }

        private long preview() {
                ApiClient.Response preview = admin.upload("/api/imports/weekly-register/preview", FILE_NAME,
                                FakeWeeklyRegisterWorkbook.build());
                assertThat(preview.status()).as(preview.body()).isEqualTo(201);
                assertThat(preview.text("$.reconciliationStatus")).isEqualTo("DIFFERENCES");
                assertThat(preview.number("$.reportDifferences")).isPositive();
                return preview.number("$.id");
        }

        private long count(String sql, Object... args) {
                return jdbc.queryForObject(sql, Long.class, args);
        }

        @Test
        void previewStoresAndSummarisesTheFigures() {
                long id = preview();

                long stored = count("select count(*) from weekly_report_figures where import_job_id = ?", id);
                assertThat(stored).isGreaterThan(250);
                assertThat(count(
                                "select count(*) from weekly_report_figures where import_job_id = ? and matches is null",
                                id))
                                .isZero();
                assertThat(count("select count(*) from reporting_weeks where week_starting = date '2026-09-14'"))
                                .as("the week's row exists from the preview on").isEqualTo(1);
                assertThat(count("select count(*) from v_week_totals")).as("but is not reported before confirm")
                                .isZero();
                assertThat(count("select reconciliation_differences from import_jobs where id = ?", id))
                                .isEqualTo(count("select count(*) from weekly_report_figures where import_job_id = ?"
                                                + " and (matches = false or sheet_error is not null)", id));

                ApiClient.Response summary = admin.get("/api/imports/" + id + "/reconciliation");
                assertThat(summary.status()).as(summary.body()).isEqualTo(200);
                assertThat(summary.text("$.status")).isEqualTo("DIFFERENCES");
                assertThat(summary.number("$.figures")).isEqualTo(stored);
                assertThat(summary.texts("$.sections[*].section")).contains("DAILY_BANKED", "DAILY_CLIENT_COUNT",
                                "CONSULTANT_PRODUCT", "CONSULTANT_CLIENT_COUNT", "FINANCE", "BUDGET_BY_STRUCTURE",
                                "SHEET_DIFFERENCE", "BREAKDOWN", "BREAKDOWN_SUMMARY");
                assertThat(summary.texts("$.defects[*].defectCode")).contains("WRONG_SHEET", "TEXT_ID_NOT_COUNTED",
                                "WRONG_CRITERIA_COLUMN", "ROW_OFFSET", "WRONG_COLUMN", "RANGE_INCOMPLETE", "REF_ERROR",
                                "BLANK_CONSULTANT_EXCLUDED", "THRESHOLD_GREATER_THAN");
                assertThat(summary.texts("$.defects[*].description")).allMatch(d -> d != null && !d.isBlank());
        }

        @Test
        void figuresCanBeFilteredAndCarryIds() {
                long id = preview();

                ApiClient.Response sunday = admin.get("/api/imports/" + id
                                + "/report-figures?sheet=Report&section=DAILY_BANKED&onlyDifferences=true");
                assertThat(sunday.status()).as(sunday.body()).isEqualTo(200);
                assertThat(sunday.texts("$.items[*].cellRef")).containsExactly("C9", "C10");
                assertThat(sunday.text("$.items[0].defectCode")).isEqualTo("WRONG_SHEET");
                assertThat(sunday.text("$.items[0].formula")).isEqualTo("=SUM(Saturday!T:T)");
                assertThat(sunday.number("$.items[0].dayOfWeek")).isEqualTo(7);
                assertThat(sunday.text("$.items[0].defectDescription")).contains("another day");

                ApiClient.Response offsets = admin
                                .get("/api/imports/" + id + "/report-figures?defect=ROW_OFFSET&size=500");
                assertThat(offsets.texts("$.items[*].sheetName")).containsOnly("Breakdown");
                assertThat(offsets.texts("$.items[*].cellRef")).contains("B12", "B16");

                ApiClient.Response wrongColumn = admin.get("/api/imports/" + id
                                + "/report-figures?sheet=report&defect=WRONG_COLUMN&onlyDifferences=true");
                assertThat(wrongColumn.texts("$.items[*].cellRef")).contains("H18");
                assertThat(wrongColumn.texts("$.items[?(@.cellRef == 'H18')].productCode"))
                                .containsExactly("CASH_ADVANCE_LITE");
                assertThat(offsets.numbers("$.items[?(@.cellRef == 'B12')].consultantId"))
                                .containsExactly(consultantOne);

                ApiClient.Response ref = admin.get("/api/imports/" + id + "/report-figures?defect=REF_ERROR");
                assertThat(ref.texts("$.items[*].sheetError")).containsExactly("#REF!");

                ApiClient.Response paged = admin.get("/api/imports/" + id + "/report-figures?page=1&size=10");
                assertThat(paged.size("$.items")).isEqualTo(10);
                assertThat(paged.number("$.page")).isEqualTo(1);
        }

        @Test
        void figuresStayWithTheImportAfterConfirm() {
                long id = preview();
                String digest = admin.get("/api/imports/" + id).text("$.fileDigest");
                assertThat(admin.post("/api/imports/" + id + "/consultant-mappings",
                                """
                                                {"nameInSheet":"%s","consultantId":%d}""".formatted(
                                                FakeWeeklyRegisterWorkbook.UNMAPPED_CONSULTANT,
                                                consultantOne))
                                .status()).isEqualTo(200);
                ApiClient.Response confirmed = admin.post("/api/imports/" + id + "/confirm",
                                "{\"fileDigest\":\"%s\"}".formatted(digest));
                assertThat(confirmed.status()).as("differences never block confirm: " + confirmed.body())
                                .isEqualTo(200);

                long week = confirmed.number("$.reportingWeekId");
                assertThat(count(
                                "select count(*) from weekly_report_figures where reporting_week_id = ? and import_job_id = ?",
                                week, id)).isGreaterThan(250);
                assertThat(count("select count(*) from v_week_totals")).isEqualTo(1);
                assertThat(admin.get("/api/imports/" + id + "/reconciliation").text("$.status"))
                                .isEqualTo("DIFFERENCES");
        }

        @Test
        void badFiltersAndRoles() {
                long id = preview();
                assertThat(admin.get("/api/imports/" + id + "/report-figures?sheet=Monday").text("$.type"))
                                .isEqualTo("urn:credit-advisory:reconciliation:invalid-sheet");
                assertThat(admin.get("/api/imports/" + id + "/report-figures?defect=TYPO").status()).isEqualTo(400);
                assertThat(admin.get("/api/imports/" + id + "/report-figures?section=NOPE").status()).isEqualTo(400);
                assertThat(admin.get("/api/imports/999999/reconciliation").status()).isEqualTo(404);

                long consultantId = createConsultant("C009", "Some Consultant");
                createUser("consultant@test.local", consultantId, RoleName.CONSULTANT);
                ApiClient consultant = signIn("consultant@test.local");
                assertThat(signIn("manager@test.local").get("/api/imports/" + id + "/reconciliation").status())
                                .as("admin only").isEqualTo(403);
                assertThat(consultant.get("/api/imports/" + id + "/reconciliation").status()).isEqualTo(403);
                assertThat(consultant.get("/api/imports/" + id + "/report-figures").status()).isEqualTo(403);
        }
}
