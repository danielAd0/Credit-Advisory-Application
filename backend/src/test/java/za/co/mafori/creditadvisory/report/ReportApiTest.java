package za.co.mafori.creditadvisory.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * GET /api/reports/weeks/... - weekly figures from the imported registers,
 * scoped by role. The data is inserted straight into the tables, like
 * WeeklyReportSchemaTest does, so every expected number below can be worked
 * out by hand from createData().
 */
class ReportApiTest extends ApiTestBase {

    private static final String WEEK = "2026-09-14";
    private static final String NEXT_WEEK = "2026-09-21";

    private long thandiId;
    private long siphoId;
    private ApiClient admin;
    private ApiClient thandi;

    /**
     * Week of 14 Sept 2026 (current import job 2; job 1 was replaced):
     *
     * <pre>
     * Mon  client 1  Thandi  quotation   1,900  banked   1,725  COMBO 1500, INSURANCE 225, MAFORI_CARD 175
     *                                                           Structure A loan 5,000
     * Mon  client 2  Sipho   quotation   1,200  banked   1,200  COMBO 1200
     * Tue  client 3  nobody  quotation 130,000  banked 100,000  COMBO 130000
     *                                                           Structure B loan 1,000, "Structure  A" loan 2,000
     * Wed  client 1  Thandi  quotation     500  banked     500  IVC 500
     * Mon  client 1  Thandi  quotation  99,999  (from job 1, REPLACED - must never show up)
     * </pre>
     *
     * Week of 21 Sept: one sale by Sipho, 100. Week of 28 Sept: exists, but
     * nothing imported yet.
     */
    @BeforeEach
    void createData() {
        thandiId = createConsultant("C001", "Thandi Test");
        siphoId = createConsultant("C002", "Sipho Test");
        long client1 = createClient("Test", "Alpha", null);
        long client2 = createClient("Test", "Bravo", null);
        long client3 = createClient("Test", "Charlie", null);

        long week = createWeek(WEEK);
        long oldJob = createCommittedJob(WEEK, week, "a");
        long currentJob = createCommittedJob(WEEK, week, "b");
        jdbc.update("update reporting_weeks set current_import_job_id = ? where id = ?", currentJob, week);

        long t1 = transaction(client1, thandiId, "2026-09-14", currentJob, week, 2, "1900", "1725");
        long t2 = transaction(client2, siphoId, "2026-09-14", currentJob, week, 3, "1200", "1200");
        long t3 = transaction(client3, null, "2026-09-15", currentJob, week, 2, "130000", "100000");
        long t4 = transaction(client1, thandiId, "2026-09-16", currentJob, week, 2, "500", "500");
        long replaced = transaction(client1, thandiId, "2026-09-14", oldJob, week, 2, "99999", "99999");
        jdbc.update("update client_transactions set superseded_by_import_job_id = ? where id = ?", currentJob,
                replaced);

        line(t1, "COMBO", "1500");
        line(t1, "INSURANCE", "225");
        line(t1, "MAFORI_CARD", "175");
        line(t2, "COMBO", "1200");
        line(t3, "COMBO", "130000");
        line(t4, "IVC", "500");
        line(replaced, "COMBO", "99999");

        budget(t1, "primary", "Structure A", "5000");
        budget(t3, "primary", "Structure B", "1000");
        budget(t3, "secondary", "Structure  A", "2000"); // double space: still Structure A
        budget(replaced, "primary", "Structure A", "77777");

        long nextWeek = createWeek(NEXT_WEEK);
        long nextJob = createCommittedJob(NEXT_WEEK, nextWeek, "c");
        jdbc.update("update reporting_weeks set current_import_job_id = ? where id = ?", nextJob, nextWeek);
        long t6 = transaction(client2, siphoId, "2026-09-21", nextJob, nextWeek, 2, "100", "100");
        line(t6, "COMBO", "100");

        createWeek("2026-09-28"); // nothing imported: never shown

        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
        admin = signIn(ADMIN_EMAIL);
        thandi = signIn("thandi@test.local");
    }

    // ---------- test data helpers ----------

    private long createWeek(String monday) {
        return jdbc.queryForObject(
                "insert into reporting_weeks (week_starting) values (cast(? as date)) returning id", Long.class,
                monday);
    }

    private long createCommittedJob(String monday, long weekId, String digestLetter) {
        return jdbc.queryForObject("""
                insert into import_jobs (file_name, file_digest, file_size_bytes, state, uploaded_by_user_id,
                                         expires_at, committed_at, import_type, week_starting, reporting_week_id)
                values ('fake.xlsx', repeat(?, 64), 10, 'COMMITTED', ?, now(), now(),
                        'WEEKLY_REGISTER', cast(? as date), ?)
                returning id
                """, Long.class, digestLetter, adminId, monday, weekId);
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

    /** which is "primary" or "secondary". */
    private void budget(long transaction, String which, String structure, String loanAmount) {
        jdbc.update("update client_transactions set " + which + "_structure_budget_list = ?, "
                + which + "_structure_loan_amount = ? where id = ?", structure, new BigDecimal(loanAmount),
                transaction);
    }

    private static String reports(String week, String report) {
        return "/api/reports/weeks/" + week + "/" + report;
    }

    // ---------- week list ----------

    @Test
    void adminSeesEveryImportedWeekNewestFirstWithCompanyTotals() {
        ApiClient.Response weeks = admin.get("/api/reports/weeks");

        assertThat(weeks.status()).as(weeks.body()).isEqualTo(200);
        assertThat(weeks.text("$.scope")).isEqualTo("ALL");
        assertThat(weeks.texts("$.weeks[*].weekStarting")).containsExactly(NEXT_WEEK, WEEK);
        assertThat(weeks.number("$.weeks[1].transactionCount")).isEqualTo(4);
        assertThat(weeks.number("$.weeks[1].quotationTotal")).isEqualTo(133_600);
        assertThat(weeks.number("$.weeks[1].bankedTotal")).isEqualTo(103_425);
    }

    @Test
    void consultantWeekListOnlyCountsTheirOwnSales() {
        ApiClient.Response weeks = thandi.get("/api/reports/weeks");

        assertThat(weeks.status()).isEqualTo(200);
        assertThat(weeks.text("$.scope")).isEqualTo("OWN");
        assertThat(weeks.texts("$.weeks[*].weekStarting")).containsExactly(NEXT_WEEK, WEEK);
        assertThat(weeks.number("$.weeks[0].transactionCount")).isZero();
        assertThat(weeks.number("$.weeks[1].transactionCount")).isEqualTo(2);
        assertThat(weeks.number("$.weeks[1].quotationTotal")).isEqualTo(2_400);
    }

    // ---------- summary (Report sheet) ----------

    @Test
    void adminSummaryHasSevenDaysTargetsAndWeekTotals() {
        ApiClient.Response summary = admin.get(reports(WEEK, "summary"));

        assertThat(summary.status()).as(summary.body()).isEqualTo(200);
        assertThat(summary.text("$.scope")).isEqualTo("ALL");
        assertThat(summary.text("$.weekStarting")).isEqualTo(WEEK);
        assertThat(summary.text("$.weekEnding")).isEqualTo("2026-09-20");
        assertThat(summary.size("$.days")).isEqualTo(7);

        // Monday: Thandi 1,900 + Sipho 1,200 (the replaced 99,999 is not counted).
        assertThat(summary.text("$.days[0].date")).isEqualTo(WEEK);
        assertThat(summary.number("$.days[0].transactionCount")).isEqualTo(2);
        assertThat(summary.number("$.days[0].quotationTotal")).isEqualTo(3_100);
        assertThat(summary.number("$.days[0].bankedTotal")).isEqualTo(2_925);
        assertThat(summary.number("$.days[0].dailyTarget")).isEqualTo(120_000);
        assertThat(summary.value("$.days[0].targetMet")).isEqualTo(false);
        // Tuesday: 130,000 >= 120,000.
        assertThat(summary.value("$.days[1].targetMet")).isEqualTo(true);
        // Sunday: nothing sold, still listed.
        assertThat(summary.number("$.days[6].dayOfWeek")).isEqualTo(7);
        assertThat(summary.number("$.days[6].transactionCount")).isZero();

        assertThat(summary.number("$.week.transactionCount")).isEqualTo(4);
        assertThat(summary.number("$.week.clientCount")).isEqualTo(3);
        assertThat(summary.number("$.week.unattributedCount")).isEqualTo(1);
        assertThat(summary.number("$.week.quotationTotal")).isEqualTo(133_600);
        assertThat(summary.number("$.week.weeklyTarget")).isEqualTo(600_000);
        assertThat(summary.value("$.week.targetMet")).isEqualTo(false);
    }

    @Test
    void managerGetsTheSameSummaryAsAdmin() {
        ApiClient.Response summary = signIn("manager@test.local").get(reports(WEEK, "summary"));

        assertThat(summary.text("$.scope")).isEqualTo("ALL");
        assertThat(summary.number("$.week.quotationTotal")).isEqualTo(133_600);
    }

    @Test
    void consultantSummaryOnlyHasTheirOwnSalesAndNoCompanyTargets() {
        ApiClient.Response summary = thandi.get(reports(WEEK, "summary"));

        assertThat(summary.status()).as(summary.body()).isEqualTo(200);
        assertThat(summary.text("$.scope")).isEqualTo("OWN");
        assertThat(summary.size("$.days")).isEqualTo(7);
        assertThat(summary.number("$.days[0].quotationTotal")).isEqualTo(1_900);
        assertThat(summary.number("$.days[0].bankedTotal")).isEqualTo(1_725);
        // Tuesday's 130,000 had no consultant: not hers.
        assertThat(summary.number("$.days[1].transactionCount")).isZero();
        assertThat(summary.number("$.days[1].quotationTotal")).isZero();
        assertThat(summary.number("$.days[2].quotationTotal")).isEqualTo(500);
        assertThat(summary.value("$.days[0].dailyTarget")).isNull();
        assertThat(summary.value("$.days[0].targetMet")).isNull();

        assertThat(summary.number("$.week.transactionCount")).isEqualTo(2);
        assertThat(summary.number("$.week.clientCount")).isEqualTo(1);
        assertThat(summary.number("$.week.quotationTotal")).isEqualTo(2_400);
        assertThat(summary.value("$.week.unattributedCount")).isNull();
        assertThat(summary.value("$.week.weeklyTarget")).isNull();
        assertThat(summary.value("$.week.targetMet")).isNull();
    }

    // ---------- consultants ----------

    @Test
    void adminConsultantReportHasEveryConsultantAndTheUnattributedSales() {
        ApiClient.Response report = admin.get(reports(WEEK, "consultants"));

        assertThat(report.status()).as(report.body()).isEqualTo(200);
        assertThat(report.size("$.consultants")).isEqualTo(3);

        assertThat(report.text("$.consultants[0].consultant.consultantCode")).isEqualTo("C001");
        assertThat(report.number("$.consultants[0].week.quotationTotal")).isEqualTo(2_400);
        assertThat(report.number("$.consultants[0].week.bankedTotal")).isEqualTo(2_225);
        assertThat(report.size("$.consultants[0].days")).isEqualTo(7);
        assertThat(report.number("$.consultants[0].days[0].quotationTotal")).isEqualTo(1_900);
        assertThat(report.number("$.consultants[0].days[1].quotationTotal")).isZero();
        assertThat(report.number("$.consultants[0].days[2].quotationTotal")).isEqualTo(500);

        assertThat(report.text("$.consultants[1].consultant.consultantCode")).isEqualTo("C002");
        assertThat(report.number("$.consultants[1].week.quotationTotal")).isEqualTo(1_200);

        // Last: the sales without a consultant.
        assertThat(report.value("$.consultants[2].consultant")).isNull();
        assertThat(report.number("$.consultants[2].week.quotationTotal")).isEqualTo(130_000);
    }

    @Test
    void consultantOnlyGetsTheirOwnRow() {
        ApiClient.Response report = thandi.get(reports(WEEK, "consultants"));

        assertThat(report.text("$.scope")).isEqualTo("OWN");
        assertThat(report.size("$.consultants")).isEqualTo(1);
        assertThat(report.number("$.consultants[0].consultant.id")).isEqualTo(thandiId);
        assertThat(report.number("$.consultants[0].week.quotationTotal")).isEqualTo(2_400);

        // No sales of hers in the next week: an empty list, not someone else's row.
        assertThat(thandi.get(reports(NEXT_WEEK, "consultants")).size("$.consultants")).isZero();
    }

    // ---------- products (Breakdown sheet) ----------

    @Test
    void productReportListsEveryActiveProductForEveryDay() {
        ApiClient.Response report = admin.get(reports(WEEK, "products"));

        assertThat(report.status()).as(report.body()).isEqualTo(200);
        assertThat(report.value("$.consultantId")).isNull();
        // All 15 seeded products, in display order, also the ones nobody sold.
        assertThat(report.size("$.products")).isEqualTo(15);
        assertThat(report.text("$.products[0].product.code")).isEqualTo("COMBO");
        assertThat(report.size("$.products[0].days")).isEqualTo(7);
        assertThat(report.number("$.products[0].days[0].totalAmount")).isEqualTo(2_700); // 1500 + 1200
        assertThat(report.number("$.products[0].days[1].totalAmount")).isEqualTo(130_000);
        assertThat(report.number("$.products[0].week.lineCount")).isEqualTo(3);
        assertThat(report.number("$.products[0].week.totalAmount")).isEqualTo(132_700);
        assertThat(report.text("$.products[1].product.code")).isEqualTo("IVC");
        assertThat(report.number("$.products[1].days[2].totalAmount")).isEqualTo(500);
        assertThat(report.text("$.products[4].product.code")).isEqualTo("MAFORI_VOUCHER");
        assertThat(report.number("$.products[4].week.totalAmount")).isZero();
    }

    @Test
    void managerCanFilterTheProductReportByConsultant() {
        ApiClient.Response report = admin.get(reports(WEEK, "products") + "?consultantId=" + siphoId);

        assertThat(report.number("$.consultantId")).isEqualTo(siphoId);
        assertThat(report.number("$.products[0].week.totalAmount")).isEqualTo(1_200);
        assertThat(report.number("$.products[1].week.totalAmount")).isZero(); // IVC was Thandi's

        assertThat(admin.get(reports(WEEK, "products") + "?consultantId=999999").status()).isEqualTo(404);
    }

    @Test
    void consultantProductReportIsAlwaysTheirOwnWhateverTheyAskFor() {
        ApiClient.Response report = thandi.get(reports(WEEK, "products") + "?consultantId=" + siphoId);

        assertThat(report.status()).isEqualTo(200);
        assertThat(report.text("$.scope")).isEqualTo("OWN");
        assertThat(report.number("$.consultantId")).isEqualTo(thandiId);
        assertThat(report.number("$.products[0].week.totalAmount")).isEqualTo(1_500); // her COMBO, not Sipho's
        assertThat(report.number("$.products[1].week.totalAmount")).isEqualTo(500);
    }

    // ---------- budget by structure ----------

    @Test
    void budgetByStructureAddsPrimaryAndSecondaryLoans() {
        ApiClient.Response report = admin.get(reports(WEEK, "budget-structures"));

        assertThat(report.status()).as(report.body()).isEqualTo(200);
        assertThat(report.texts("$.structures[*].budgetStructure")).containsExactly("Structure A", "Structure B");
        assertThat(report.number("$.structures[0].week.entryCount")).isEqualTo(2);
        assertThat(report.number("$.structures[0].week.loanAmountTotal")).isEqualTo(7_000); // not 77,777
        assertThat(report.number("$.structures[0].days[0].loanAmountTotal")).isEqualTo(5_000);
        assertThat(report.number("$.structures[0].days[1].loanAmountTotal")).isEqualTo(2_000);
        assertThat(report.number("$.structures[1].week.loanAmountTotal")).isEqualTo(1_000);
    }

    @Test
    void budgetByStructureIsForManagersAndAdminsOnly() {
        assertThat(signIn("manager@test.local").get(reports(WEEK, "budget-structures")).status()).isEqualTo(200);
        assertThat(thandi.get(reports(WEEK, "budget-structures")).status()).isEqualTo(403);
    }

    // ---------- week rules ----------

    @Test
    void weekMustBeAMondayWrittenAsADate() {
        ApiClient.Response tuesday = admin.get(reports("2026-09-15", "summary"));
        assertThat(tuesday.status()).isEqualTo(400);
        assertThat(tuesday.text("$.type")).isEqualTo("urn:credit-advisory:report:invalid-week");

        assertThat(admin.get(reports("14-09-2026", "summary")).status()).isEqualTo(400);
    }

    @Test
    void weekWithoutAnImportIsNotFound() {
        ApiClient.Response notImported = admin.get(reports("2026-09-28", "summary"));
        assertThat(notImported.status()).isEqualTo(404);
        assertThat(notImported.text("$.type")).isEqualTo("urn:credit-advisory:report:week-not-found");

        assertThat(thandi.get(reports("2026-10-05", "consultants")).status()).isEqualTo(404);
    }

    // ---------- edge cases ----------

    @Test
    void consultantWithoutAConsultantRecordSeesOnlyZeros() {
        createUser("unlinked@test.local", null, RoleName.CONSULTANT);
        ApiClient unlinked = signIn("unlinked@test.local");

        ApiClient.Response summary = unlinked.get(reports(WEEK, "summary"));
        assertThat(summary.status()).as(summary.body()).isEqualTo(200);
        assertThat(summary.number("$.week.transactionCount")).isZero();
        assertThat(summary.number("$.week.quotationTotal")).isZero();
        assertThat(unlinked.get(reports(WEEK, "consultants")).size("$.consultants")).isZero();
        assertThat(unlinked.get(reports(WEEK, "products")).number("$.products[0].week.totalAmount")).isZero();
    }

    @Test
    void reportsNeedSignIn() {
        assertThat(browser().get("/api/reports/weeks").status()).isEqualTo(401);
        assertThat(browser().get(reports(WEEK, "summary")).status()).isEqualTo(401);
    }
}
