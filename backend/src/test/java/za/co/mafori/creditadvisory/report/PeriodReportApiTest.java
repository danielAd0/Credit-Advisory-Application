package za.co.mafori.creditadvisory.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * GET /api/reports/months and /api/reports/range/... - figures for any month
 * or date range, worked out from each sale's own date, not from the week it
 * was imported with. Same insert-straight-into-the-tables approach as
 * ReportApiTest, so every number below can be checked by hand.
 */
class PeriodReportApiTest extends ApiTestBase {

    private long thandiId;
    private long siphoId;
    private ApiClient admin;
    private ApiClient thandi;

    /**
     * <pre>
     * Week of 14 Sept:
     *   Tue 15 Sep  client 2  Sipho   quotation  10,000  COMBO 10000
     * Week of 28 Sept (runs into October):
     *   Mon 28 Sep  client 1  Thandi  quotation 100,000  banked  90,000  COMBO 100000
     *   Tue 29 Sep  client 1  Thandi  quotation  99,999  (replaced import - never counted)
     *   Wed 30 Sep  client 2  Sipho   quotation 130,000  banked 130,000  COMBO 130000
     *   Thu  1 Oct  client 1  Thandi  quotation 200,000  banked 150,000  COMBO 200000
     *   Fri  2 Oct  client 3  nobody  quotation  50,000  banked  50,000  IVC 50000, Structure A loan 5,000
     * </pre>
     *
     * September: 240,000 from 3 sales. October: 250,000 from 2 sales.
     * Targets: 120,000 a day and 600,000 a week, configured from 1 Jan 2026.
     */
    @BeforeEach
    void createData() {
        configureTargets("2026-01-01", "120000", "600000");
        thandiId = createConsultant("C001", "Thandi Test");
        siphoId = createConsultant("C002", "Sipho Test");
        long client1 = createClient("Test", "Alpha", null);
        long client2 = createClient("Test", "Bravo", null);
        long client3 = createClient("Test", "Charlie", null);

        long early = createWeek("2026-09-14");
        long earlyJob = createCommittedJob("2026-09-14", early, "a");
        long t0 = transaction(client2, siphoId, "2026-09-15", earlyJob, early, 2, "10000", "10000");
        line(t0, "COMBO", "10000");

        long week = createWeek("2026-09-28");
        long oldJob = createCommittedJob("2026-09-28", week, "b");
        long job = createCommittedJob("2026-09-28", week, "c");
        long t1 = transaction(client1, thandiId, "2026-09-28", job, week, 2, "100000", "90000");
        long t2 = transaction(client2, siphoId, "2026-09-30", job, week, 2, "130000", "130000");
        long t3 = transaction(client1, thandiId, "2026-10-01", job, week, 2, "200000", "150000");
        long t4 = transaction(client3, null, "2026-10-02", job, week, 2, "50000", "50000");
        long replaced = transaction(client1, thandiId, "2026-09-29", oldJob, week, 2, "99999", "99999");
        jdbc.update("update client_transactions set superseded_by_import_job_id = ? where id = ?", job, replaced);

        line(t1, "COMBO", "100000");
        line(t2, "COMBO", "130000");
        line(t3, "COMBO", "200000");
        line(t4, "IVC", "50000");
        line(replaced, "COMBO", "99999");
        budget(t4, "Structure A", "5000");
        budget(replaced, "Structure A", "77777");

        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
        admin = signIn(ADMIN_EMAIL);
        thandi = signIn("thandi@test.local");
    }

    // ---------- test data helpers (as in ReportApiTest) ----------

    private long createWeek(String monday) {
        return jdbc.queryForObject(
                "insert into reporting_weeks (week_starting) values (cast(? as date)) returning id", Long.class,
                monday);
    }

    private long createCommittedJob(String monday, long weekId, String digestLetter) {
        long id = jdbc.queryForObject("""
                insert into import_jobs (file_name, file_digest, file_size_bytes, state, uploaded_by_user_id,
                                         expires_at, committed_at, import_type, week_starting, reporting_week_id)
                values ('fake.xlsx', repeat(?, 64), 10, 'COMMITTED', ?, now(), now(),
                        'WEEKLY_REGISTER', cast(? as date), ?)
                returning id
                """, Long.class, digestLetter, adminId, monday, weekId);
        jdbc.update("update reporting_weeks set current_import_job_id = ? where id = ?", id, weekId);
        return id;
    }

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

    private void budget(long transaction, String structure, String loanAmount) {
        jdbc.update("""
                update client_transactions
                   set primary_structure_budget_list = ?, primary_structure_loan_amount = ?
                 where id = ?
                """, structure, new BigDecimal(loanAmount), transaction);
    }

    private static String range(String report, String from, String to) {
        return "/api/reports/range/" + report + "?from=" + from + "&to=" + to;
    }

    private long exportAudits() {
        return jdbc.queryForObject("select count(*) from audit_logs where action = 'REPORT_EXPORTED'", Long.class);
    }

    private String lastExportAudit(String key) {
        return jdbc.queryForObject("""
                select metadata ->> ? from audit_logs where action = 'REPORT_EXPORTED' order by id desc limit 1
                """, String.class, key);
    }

    // ---------- months ----------

    @Test
    void adminMonthsSplitASalesWeekByEachSalesOwnDate() {
        ApiClient.Response months = admin.get("/api/reports/months");

        assertThat(months.status()).as(months.body()).isEqualTo(200);
        assertThat(months.text("$.scope")).isEqualTo("ALL");
        assertThat(months.texts("$.months[*].month")).containsExactly("2026-10", "2026-09");
        assertThat(months.text("$.months[0].from")).isEqualTo("2026-10-01");
        assertThat(months.text("$.months[0].to")).isEqualTo("2026-10-31");
        assertThat(months.number("$.months[0].transactionCount")).isEqualTo(2);
        assertThat(months.number("$.months[0].quotationTotal")).isEqualTo(250_000);
        // The replaced 99,999 on 29 Sept is not counted.
        assertThat(months.number("$.months[1].transactionCount")).isEqualTo(3);
        assertThat(months.number("$.months[1].quotationTotal")).isEqualTo(240_000);
    }

    @Test
    void consultantMonthsOnlyCountTheirOwnSales() {
        ApiClient.Response months = thandi.get("/api/reports/months");

        assertThat(months.status()).isEqualTo(200);
        assertThat(months.text("$.scope")).isEqualTo("OWN");
        assertThat(months.texts("$.months[*].month")).containsExactly("2026-10", "2026-09");
        assertThat(months.number("$.months[0].quotationTotal")).isEqualTo(200_000);
        assertThat(months.number("$.months[1].quotationTotal")).isEqualTo(100_000);
    }

    // ---------- summary ----------

    @Test
    void adminRangeSummaryCoversAWholeWeekWithItsTargets() {
        ApiClient.Response summary = admin.get(range("summary", "2026-09-28", "2026-10-04"));

        assertThat(summary.status()).as(summary.body()).isEqualTo(200);
        assertThat(summary.size("$.days")).isEqualTo(7);
        assertThat(summary.number("$.totals.transactionCount")).isEqualTo(4);
        assertThat(summary.number("$.totals.clientCount")).isEqualTo(3);
        assertThat(summary.number("$.totals.unattributedCount")).isEqualTo(1);
        assertThat(summary.number("$.totals.quotationTotal")).isEqualTo(480_000);
        assertThat(summary.number("$.totals.bankedTotal")).isEqualTo(420_000);
        assertThat(summary.number("$.totals.daysWithSales")).isEqualTo(4);
        assertThat(summary.number("$.totals.daysJudged")).isEqualTo(4);
        // 30 Sept (130,000) and 1 Oct (200,000) reached 120,000.
        assertThat(summary.number("$.totals.daysTargetMet")).isEqualTo(2);
        assertThat(summary.number("$.days[0].dailyTarget")).isEqualTo(120_000);
        assertThat(summary.value("$.days[0].targetMet")).isEqualTo(false);
        assertThat(summary.value("$.days[2].targetMet")).isEqualTo(true);
        // Tuesday only had the replaced sale.
        assertThat(summary.number("$.days[1].transactionCount")).isZero();

        assertThat(summary.size("$.weeks")).isEqualTo(1);
        assertThat(summary.value("$.weeks[0].wholeWeek")).isEqualTo(true);
        assertThat(summary.number("$.weeks[0].weeklyTarget")).isEqualTo(600_000);
        assertThat(summary.value("$.weeks[0].targetMet")).isEqualTo(false);
    }

    @Test
    void monthSummaryJudgesOnlyWholeWeeksAgainstTheWeeklyTarget() {
        ApiClient.Response summary = signIn("manager@test.local").get(range("summary", "2026-09-01", "2026-09-30"));

        assertThat(summary.status()).as(summary.body()).isEqualTo(200);
        assertThat(summary.text("$.scope")).isEqualTo("ALL");
        assertThat(summary.size("$.days")).isEqualTo(30);
        assertThat(summary.number("$.totals.quotationTotal")).isEqualTo(240_000);
        // 1 Sept is a Tuesday and 30 Sept a Wednesday: both ends are part-weeks.
        assertThat(summary.text("$.weeks[0].weekStarting")).isEqualTo("2026-08-31");
        assertThat(summary.text("$.weeks[0].from")).isEqualTo("2026-09-01");
        assertThat(summary.value("$.weeks[0].wholeWeek")).isEqualTo(false);
        assertThat(summary.value("$.weeks[0].targetMet")).isNull();
        assertThat(summary.text("$.weeks[4].weekStarting")).isEqualTo("2026-09-28");
        assertThat(summary.text("$.weeks[4].to")).isEqualTo("2026-09-30");
        assertThat(summary.value("$.weeks[4].wholeWeek")).isEqualTo(false);
        assertThat(summary.value("$.weeks[4].targetMet")).isNull();
    }

    @Test
    void consultantRangeSummaryHasOnlyTheirSalesAndNoCompanyTargets() {
        ApiClient.Response summary = thandi.get(range("summary", "2026-09-28", "2026-10-04"));

        assertThat(summary.status()).as(summary.body()).isEqualTo(200);
        assertThat(summary.text("$.scope")).isEqualTo("OWN");
        assertThat(summary.number("$.totals.transactionCount")).isEqualTo(2);
        assertThat(summary.number("$.totals.quotationTotal")).isEqualTo(300_000);
        assertThat(summary.value("$.totals.unattributedCount")).isNull();
        assertThat(summary.value("$.totals.daysJudged")).isNull();
        assertThat(summary.value("$.totals.daysTargetMet")).isNull();
        assertThat(summary.value("$.days[0].dailyTarget")).isNull();
        assertThat(summary.value("$.days[0].targetMet")).isNull();
        assertThat(summary.value("$.weeks[0].weeklyTarget")).isNull();
        assertThat(summary.value("$.weeks[0].targetMet")).isNull();
    }

    @Test
    void withoutConfiguredTargetsNothingIsJudged() {
        jdbc.update("delete from sales_targets");

        ApiClient.Response summary = admin.get(range("summary", "2026-09-28", "2026-10-04"));

        assertThat(summary.status()).as(summary.body()).isEqualTo(200);
        assertThat(summary.number("$.totals.quotationTotal")).isEqualTo(480_000);
        assertThat(summary.number("$.totals.daysWithSales")).isEqualTo(4);
        assertThat(summary.number("$.totals.daysJudged")).isZero();
        assertThat(summary.value("$.totals.daysTargetMet")).isNull();
        assertThat(summary.value("$.days[0].dailyTarget")).isNull();
        assertThat(summary.value("$.days[0].targetMet")).isNull();
        assertThat(summary.value("$.weeks[0].weeklyTarget")).isNull();
        assertThat(summary.value("$.weeks[0].targetMet")).isNull();
    }

    @Test
    void aTargetStartingMidPeriodOnlyJudgesTheDaysFromThen() {
        jdbc.update("delete from sales_targets");
        configureTargets("2026-10-01", "150000", "700000");

        ApiClient.Response summary = admin.get(range("summary", "2026-09-28", "2026-10-04"));

        // 1 Oct (200,000) met 150,000; 2 Oct (50,000) did not. 28 and 30 Sept had no
        // target.
        assertThat(summary.number("$.totals.daysJudged")).isEqualTo(2);
        assertThat(summary.number("$.totals.daysTargetMet")).isEqualTo(1);
        assertThat(summary.value("$.days[0].dailyTarget")).isNull();
        assertThat(summary.number("$.days[3].dailyTarget")).isEqualTo(150_000);
        // The weekly target is looked up on the Monday (28 Sept): none yet.
        assertThat(summary.value("$.weeks[0].weeklyTarget")).isNull();
    }

    @Test
    void typedInSalesCountTheSameAsImportedOnes() {
        // A sale typed into the app (no import job) on Saturday 3 Oct, in the same
        // week.
        long week = jdbc.queryForObject("select id from reporting_weeks where week_starting = date '2026-09-28'",
                Long.class);
        long client = createClient("Test", "Delta", null);
        long typedIn = jdbc.queryForObject("""
                insert into client_transactions (client_id, consultant_id, transaction_date, created_by_user_id,
                    reporting_week_id, day_of_week, quotation_amount, banked_amount)
                values (?, ?, date '2026-10-03', ?, ?, 6, 20000, 15000)
                returning id
                """, Long.class, client, siphoId, adminId, week);
        line(typedIn, "COMBO", "20000");

        ApiClient.Response day = admin.get(range("summary", "2026-10-03", "2026-10-03"));
        ApiClient.Response october = admin.get(range("summary", "2026-10-01", "2026-10-31"));

        assertThat(day.status()).as(day.body()).isEqualTo(200);
        assertThat(day.size("$.days")).isEqualTo(1);
        assertThat(day.number("$.totals.transactionCount")).isEqualTo(1);
        assertThat(day.number("$.totals.quotationTotal")).isEqualTo(20_000);
        assertThat(october.number("$.totals.quotationTotal")).isEqualTo(270_000);
        // Not in September.
        assertThat(admin.get(range("summary", "2026-09-01", "2026-09-30")).number("$.totals.quotationTotal"))
                .isEqualTo(240_000);
    }

    // ---------- consultants, products, budget ----------

    @Test
    void adminConsultantTotalsPutSalesWithoutAConsultantLast() {
        ApiClient.Response rows = admin.get(range("consultants", "2026-09-28", "2026-10-04"));

        assertThat(rows.status()).as(rows.body()).isEqualTo(200);
        assertThat(rows.size("$.consultants")).isEqualTo(3);
        assertThat(rows.number("$.consultants[0].consultant.id")).isEqualTo(thandiId);
        assertThat(rows.number("$.consultants[0].quotationTotal")).isEqualTo(300_000);
        assertThat(rows.number("$.consultants[1].consultant.id")).isEqualTo(siphoId);
        assertThat(rows.value("$.consultants[2].consultant")).isNull();
        assertThat(rows.number("$.consultants[2].quotationTotal")).isEqualTo(50_000);
    }

    @Test
    void productTotalsLeaveOutReplacedSalesAndCanBeNarrowedToAConsultant() {
        ApiClient.Response all = admin.get(range("products", "2026-09-28", "2026-10-04"));

        assertThat(all.status()).as(all.body()).isEqualTo(200);
        assertThat(all.numbers("$.products[?(@.product.code == 'COMBO')].totalAmount")).containsExactly(430_000L);
        assertThat(all.numbers("$.products[?(@.product.code == 'IVC')].totalAmount")).containsExactly(50_000L);

        ApiClient.Response sipho = admin.get(range("products", "2026-09-28", "2026-10-04") + "&consultantId="
                + siphoId);
        assertThat(sipho.number("$.consultantId")).isEqualTo(siphoId);
        assertThat(sipho.numbers("$.products[?(@.product.code == 'COMBO')].totalAmount"))
                .containsExactly(130_000L);
    }

    @Test
    void consultantProductTotalsAreAlwaysTheirOwn() {
        ApiClient.Response rows = thandi.get(range("products", "2026-09-28", "2026-10-04") + "&consultantId="
                + siphoId);

        assertThat(rows.status()).as(rows.body()).isEqualTo(200);
        assertThat(rows.number("$.consultantId")).isEqualTo(thandiId);
        assertThat(rows.numbers("$.products[?(@.product.code == 'COMBO')].totalAmount"))
                .containsExactly(300_000L);
    }

    @Test
    void unknownConsultantIsNotFound() {
        ApiClient.Response rows = admin.get(range("products", "2026-09-28", "2026-10-04") + "&consultantId=999999");

        assertThat(rows.status()).isEqualTo(404);
        assertThat(rows.body()).contains("consultant:not-found");
    }

    @Test
    void budgetStructuresAreForManagersOnly() {
        ApiClient.Response rows = signIn("manager@test.local").get(range("budget-structures", "2026-09-01",
                "2026-10-31"));

        assertThat(rows.status()).as(rows.body()).isEqualTo(200);
        assertThat(rows.texts("$.structures[*].budgetStructure")).containsExactly("Structure A");
        assertThat(rows.number("$.structures[0].loanAmountTotal")).isEqualTo(5_000);

        assertThat(thandi.get(range("budget-structures", "2026-09-01", "2026-10-31")).status()).isEqualTo(403);
    }

    // ---------- period checks ----------

    @Test
    void badPeriodsAreRefused() {
        for (String query : new String[] {
                "/api/reports/range/summary?from=2026-09-01",
                "/api/reports/range/summary?from=2026-09-xx&to=2026-09-30",
                range("summary", "2026-09-30", "2026-09-01"),
                range("summary", "2025-01-01", "2026-09-30") }) {
            ApiClient.Response response = admin.get(query);
            assertThat(response.status()).as(query).isEqualTo(400);
            assertThat(response.body()).as(query).contains("report:invalid-period");
        }
    }

    @Test
    void signInIsRequired() {
        ApiClient anonymous = browser();
        assertThat(anonymous.get("/api/reports/months").status()).isEqualTo(401);
        assertThat(anonymous.get(range("summary", "2026-09-01", "2026-09-30")).status()).isEqualTo(401);
    }

    // ---------- CSV ----------

    @Test
    void summaryCsvIsADownloadAndIsAudited() {
        long before = exportAudits();

        ApiClient.Response csv = admin.get(range("summary.csv", "2026-09-28", "2026-10-04"));

        assertThat(csv.status()).as(csv.body()).isEqualTo(200);
        assertThat(csv.header("Content-Disposition"))
                .contains("report-summary-2026-09-28-to-2026-10-04.csv");
        assertThat(csv.body()).contains("480000");
        assertThat(exportAudits()).isEqualTo(before + 1);
        assertThat(lastExportAudit("report")).isEqualTo("summary");
        assertThat(lastExportAudit("period")).isEqualTo("2026-09-28-to-2026-10-04");
        assertThat(lastExportAudit("scope")).isEqualTo("ALL");
    }

    @Test
    void consultantCsvIsAuditedAsTheirOwn() {
        ApiClient.Response csv = thandi.get(range("products.csv", "2026-09-28", "2026-10-04"));

        assertThat(csv.status()).as(csv.body()).isEqualTo(200);
        assertThat(lastExportAudit("report")).isEqualTo("products");
        assertThat(lastExportAudit("scope")).isEqualTo("OWN");
    }
}
