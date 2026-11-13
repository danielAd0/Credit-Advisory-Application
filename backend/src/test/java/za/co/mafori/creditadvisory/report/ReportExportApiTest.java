package za.co.mafori.creditadvisory.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * GET /api/reports/weeks/{weekStarting}/*.csv - the report exports (FR-22):
 * same figures and scoping as the JSON reports, as CSV files, every export
 * audited, and text that looks like a spreadsheet formula made harmless.
 */
class ReportExportApiTest extends ApiTestBase {

    private static final String WEEK = "2026-09-14";
    /**
     * A consultant name that Excel would run as a formula if it were not guarded.
     */
    private static final String FORMULA_NAME = "=HYPERLINK(\"http://evil.test\",\"Open\")";

    private long thandiId;
    private long formulaConsultantId;
    private ApiClient admin;
    private ApiClient thandi;

    /**
     * Week of 14 Sept 2026:
     *
     * <pre>
     * Mon  Thandi (C001)          quotation   1,900  banked   1,725  COMBO 1500, INSURANCE 225, MAFORI_CARD 175
     *                                                                Structure A loan 5,000
     * Mon  FORMULA_NAME (C002)    quotation   1,200  banked   1,200  COMBO 1200
     *                                                                "=SUM(A1:A9)" loan 300
     * Tue  nobody                 quotation 130,000  banked 100,000  COMBO 130000
     * </pre>
     */
    @BeforeEach
    void createData() {
        configureTargets("2026-01-01", "120000", "600000");
        thandiId = createConsultant("C001", "Thandi Test");
        formulaConsultantId = createConsultant("C002", FORMULA_NAME);
        long client1 = createClient("Test", "Alpha", null);
        long client2 = createClient("Test", "Bravo", null);
        long client3 = createClient("Test", "Charlie", null);

        long week = jdbc.queryForObject(
                "insert into reporting_weeks (week_starting) values (cast(? as date)) returning id", Long.class,
                WEEK);
        long job = jdbc.queryForObject("""
                insert into import_jobs (file_name, file_digest, file_size_bytes, state, uploaded_by_user_id,
                                         expires_at, committed_at, import_type, week_starting, reporting_week_id)
                values ('fake.xlsx', repeat('a', 64), 10, 'COMMITTED', ?, now(), now(),
                        'WEEKLY_REGISTER', cast(? as date), ?)
                returning id
                """, Long.class, adminId, WEEK, week);
        jdbc.update("update reporting_weeks set current_import_job_id = ? where id = ?", job, week);

        long t1 = transaction(client1, thandiId, "2026-09-14", job, week, 2, "1900", "1725");
        long t2 = transaction(client2, formulaConsultantId, "2026-09-14", job, week, 3, "1200", "1200");
        long t3 = transaction(client3, null, "2026-09-15", job, week, 2, "130000", "100000");
        line(t1, "COMBO", "1500");
        line(t1, "INSURANCE", "225");
        line(t1, "MAFORI_CARD", "175");
        line(t2, "COMBO", "1200");
        line(t3, "COMBO", "130000");
        jdbc.update("""
                update client_transactions set primary_structure_budget_list = ?, primary_structure_loan_amount = ?
                where id = ?""", "Structure A", new BigDecimal("5000"), t1);
        jdbc.update("""
                update client_transactions set primary_structure_budget_list = ?, primary_structure_loan_amount = ?
                where id = ?""", "=SUM(A1:A9)", new BigDecimal("300"), t2);

        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
        admin = signIn(ADMIN_EMAIL);
        thandi = signIn("thandi@test.local");
    }

    // ---------- helpers ----------

    private long transaction(long client, Long consultant, String date, long jobId, long weekId, int sourceRow,
            String quotation, String banked) {
        int day = java.time.LocalDate.parse(date).getDayOfWeek().getValue();
        return jdbc.queryForObject("""
                insert into client_transactions (client_id, consultant_id, transaction_date, created_by_user_id,
                    import_job_id, source_sheet, source_row_number, reporting_week_id, day_of_week,
                    quotation_amount, banked_amount)
                values (?, ?, cast(? as date), ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, client, consultant, date, adminId, jobId, "Day" + day, sourceRow, weekId, day,
                new BigDecimal(quotation), new BigDecimal(banked));
    }

    private void line(long transaction, String productCode, String amount) {
        jdbc.update("""
                insert into client_transaction_lines (transaction_id, product_id, amount,
                                                      counts_toward_quotation, counts_toward_banked)
                select ?, id, ?, counts_toward_quotation, counts_toward_banked from products where code = ?
                """, transaction, new BigDecimal(amount), productCode);
    }

    private static String csv(String report) {
        return "/api/reports/weeks/" + WEEK + "/" + report + ".csv";
    }

    /** The file's lines, without the byte-order mark. */
    private static List<String> lines(ApiClient.Response response) {
        assertThat(response.body()).startsWith(ReportCsv.BYTE_ORDER_MARK);
        return List.of(response.body().substring(1).split("\r\n"));
    }

    /** "0.00,0.00,..." n times. */
    private static String zeros(int n) {
        return String.join(",", Collections.nCopies(n, "0.00"));
    }

    private long exportAudits() {
        return jdbc.queryForObject("select count(*) from audit_logs where action = 'REPORT_EXPORTED'", Long.class);
    }

    private String lastExportAudit(String key) {
        return jdbc.queryForObject("""
                select metadata ->> ? from audit_logs where action = 'REPORT_EXPORTED' order by id desc limit 1
                """, String.class, key);
    }

    // ---------- summary.csv ----------

    @Test
    void adminSummaryCsvIsADownloadWithTheReportFigures() {
        ApiClient.Response response = admin.get(csv("summary"));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/csv");
        assertThat(response.header("Content-Disposition"))
                .isEqualTo("attachment; filename=\"report-summary-2026-09-14.csv\"");

        List<String> lines = lines(response);
        assertThat(lines).hasSize(9); // header + 7 days + week
        assertThat(lines.get(0)).isEqualTo("Day,Date,Sales,Clients,Quotation,Banked,Target,Target met");
        assertThat(lines.get(1)).isEqualTo("Monday,2026-09-14,2,2,3100.00,2925.00,120000.00,No");
        assertThat(lines.get(2)).isEqualTo("Tuesday,2026-09-15,1,1,130000.00,100000.00,120000.00,Yes");
        assertThat(lines.get(7)).isEqualTo("Sunday,2026-09-20,0,0,0.00,0.00,120000.00,No");
        assertThat(lines.get(8)).isEqualTo("Week,2026-09-14 to 2026-09-20,3,3,133100.00,102925.00,600000.00,No");
    }

    @Test
    void summaryCsvSaysNotConfiguredWhenNoTargetIsSet() {
        jdbc.update("delete from sales_targets");

        List<String> lines = lines(admin.get(csv("summary")));

        assertThat(lines.get(1)).isEqualTo("Monday,2026-09-14,2,2,3100.00,2925.00,Not configured,");
        assertThat(lines.get(8))
                .isEqualTo("Week,2026-09-14 to 2026-09-20,3,3,133100.00,102925.00,Not configured,");
    }

    @Test
    void consultantSummaryCsvOnlyHasTheirOwnFiguresAndNoTargets() {
        List<String> lines = lines(thandi.get(csv("summary")));

        assertThat(lines.get(1)).isEqualTo("Monday,2026-09-14,1,1,1900.00,1725.00,,");
        assertThat(lines.get(2)).isEqualTo("Tuesday,2026-09-15,0,0,0.00,0.00,,");
        assertThat(lines.get(8)).isEqualTo("Week,2026-09-14 to 2026-09-20,1,1,1900.00,1725.00,,");
    }

    // ---------- consultants.csv ----------

    @Test
    void consultantsCsvHasOneLinePerConsultantAndGuardsFormulas() {
        List<String> lines = lines(admin.get(csv("consultants")));

        assertThat(lines).hasSize(4); // header + C001 + C002 + no consultant
        assertThat(lines.get(0)).startsWith("Consultant code,Consultant,Monday quotation,Monday banked,"
                + "Tuesday quotation,Tuesday banked,")
                .endsWith(",Sunday quotation,Sunday banked,Week sales,Week clients,Week quotation,Week banked");
        assertThat(lines.get(1)).isEqualTo("C001,Thandi Test,1900.00,1725.00," + zeros(12) + ",1,1,1900.00,1725.00");
        // The formula name starts with ' (shown as text) and is quoted, with its quotes
        // doubled.
        assertThat(lines.get(2)).isEqualTo("C002,\"'=HYPERLINK(\"\"http://evil.test\"\",\"\"Open\"\")\","
                + "1200.00,1200.00," + zeros(12) + ",1,1,1200.00,1200.00");
        assertThat(lines.get(3)).isEqualTo(",No consultant,0.00,0.00,130000.00,100000.00," + zeros(10)
                + ",1,1,130000.00,100000.00");
    }

    @Test
    void consultantGetsOnlyTheirOwnLine() {
        List<String> lines = lines(thandi.get(csv("consultants")));

        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).startsWith("C001,Thandi Test,");
    }

    // ---------- products.csv ----------

    @Test
    void productsCsvHasEveryActiveProduct() {
        List<String> lines = lines(admin.get(csv("products")));

        assertThat(lines).hasSize(16); // header + 15 products
        assertThat(lines.get(0)).isEqualTo("Product code,Product,Monday,Tuesday,Wednesday,Thursday,Friday,"
                + "Saturday,Sunday,Week sales,Week total");
        assertThat(lines.get(1)).isEqualTo("COMBO,Combo,2700.00,130000.00," + zeros(5) + ",3,132700.00");
    }

    @Test
    void consultantProductsCsvIsTheirOwnWhateverTheyAskFor() {
        ApiClient.Response response = thandi.get(csv("products") + "?consultantId=" + formulaConsultantId);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Disposition"))
                .isEqualTo("attachment; filename=\"report-products-2026-09-14-consultant-" + thandiId + ".csv\"");
        assertThat(lines(response).get(1)).isEqualTo("COMBO,Combo,1500.00," + zeros(6) + ",1,1500.00");
    }

    // ---------- budget-structures.csv ----------

    @Test
    void budgetStructuresCsvGuardsAFormulaInTheStructureName() {
        List<String> lines = lines(signIn("manager@test.local").get(csv("budget-structures")));

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("Budget structure,Monday,Tuesday,Wednesday,Thursday,Friday,Saturday,"
                + "Sunday,Week entries,Week loan total");
        assertThat(lines).contains("Structure A,5000.00," + zeros(6) + ",1,5000.00");
        assertThat(lines).contains("'=SUM(A1:A9),300.00," + zeros(6) + ",1,300.00");
    }

    @Test
    void budgetStructuresCsvIsForManagersAndAdminsOnly() {
        assertThat(thandi.get(csv("budget-structures")).status()).isEqualTo(403);
    }

    // ---------- audit ----------

    @Test
    void everyExportIsAuditedWithWhatWasExported() {
        assertThat(admin.get(csv("summary")).status()).isEqualTo(200);
        assertThat(exportAudits()).isEqualTo(1);
        assertThat(lastExportAudit("report")).isEqualTo("summary");
        assertThat(lastExportAudit("weekStarting")).isEqualTo(WEEK);
        assertThat(lastExportAudit("scope")).isEqualTo("ALL");

        assertThat(thandi.get(csv("products") + "?consultantId=" + formulaConsultantId).status()).isEqualTo(200);
        assertThat(exportAudits()).isEqualTo(2);
        assertThat(lastExportAudit("report")).isEqualTo("products");
        assertThat(lastExportAudit("scope")).isEqualTo("OWN");
        assertThat(lastExportAudit("consultantId")).isEqualTo(String.valueOf(thandiId));

        // The JSON reports are not exports: not audited.
        admin.get("/api/reports/weeks/" + WEEK + "/summary");
        assertThat(exportAudits()).isEqualTo(2);
    }

    @Test
    void aRefusedExportIsNotAudited() {
        assertThat(thandi.get(csv("budget-structures")).status()).isEqualTo(403);
        assertThat(admin.get("/api/reports/weeks/2026-09-21/summary.csv").status()).isEqualTo(404);
        assertThat(admin.get("/api/reports/weeks/2026-09-15/summary.csv").status()).isEqualTo(400);

        assertThat(exportAudits()).isZero();
    }

    @Test
    void exportsNeedSignIn() {
        assertThat(browser().get(csv("summary")).status()).isEqualTo(401);
    }
}
