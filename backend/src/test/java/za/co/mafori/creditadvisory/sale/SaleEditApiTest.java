package za.co.mafori.creditadvisory.sale;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * Changing and deleting a typed-in sale: GET, PATCH and DELETE
 * /api/transactions/{id} (docs/api-sales.md). The week of 14 Sept 2026 has no
 * import unless a test adds one.
 */
class SaleEditApiTest extends ApiTestBase {

    private static final String TUESDAY = "2026-09-15";

    private long thandiId;
    private long siphoId;
    private long thandisClient;
    private long combo;
    private long creditRepair;
    private long ivc;
    private ApiClient thandi;
    private ApiClient manager;

    @BeforeEach
    void createPeople() {
        thandiId = createConsultant("C001", "Thandi Test");
        siphoId = createConsultant("C002", "Sipho Test");
        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
        thandisClient = createClient("Test", "Alpha", thandiId);
        combo = productId("COMBO");
        creditRepair = productId("CREDIT_REPAIR"); // counts toward quotation, not banked
        ivc = productId("IVC");
        thandi = signIn("thandi@test.local");
        manager = signIn("manager@test.local");
    }

    private long productId(String code) {
        return jdbc.queryForObject("select id from products where code = ?", Long.class, code);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static double money(ApiClient.Response response, String path) {
        return ((Number) response.value(path)).doubleValue();
    }

    /** Thandi's sale on TUESDAY: Combo 1500 and Credit repair 300.50. */
    private long thandisSale() {
        String json = """
                {"clientId":%d,"transactionDate":"%s","lines":[{"productId":%d,"amount":1500},
                 {"productId":%d,"amount":300.50}]}""".formatted(thandisClient, TUESDAY, combo, creditRepair);
        ApiClient.Response saved = thandi.sendWithHeader("POST", "/api/transactions", json, "Idempotency-Key",
                UUID.randomUUID().toString());
        assertThat(saved.status()).as(saved.body()).isEqualTo(201);
        return saved.number("$.id");
    }

    private static String edit(long version, String date, String linesJson, String extraJson) {
        return """
                {"version":%d,"transactionDate":"%s","lines":[%s]%s}""".formatted(version, date, linesJson, extraJson);
    }

    private String sameLines() {
        return """
                {"productId":%d,"amount":1500},{"productId":%d,"amount":300.50}""".formatted(combo, creditRepair);
    }

    private static ApiClient.Response patch(ApiClient as, long id, String json) {
        return as.patch("/api/transactions/" + id, json);
    }

    private static ApiClient.Response delete(ApiClient as, long id, long version) {
        return as.send("DELETE", "/api/transactions/" + id + "?version=" + version, null);
    }

    /**
     * Marks the week of 14 Sept imported, the way a confirm does, and returns the
     * job id.
     */
    private long importWeek(String monday) {
        long week = jdbc.queryForObject("""
                insert into reporting_weeks (week_starting) values (cast(? as date))
                on conflict (week_starting) do update set week_starting = excluded.week_starting
                returning id
                """, Long.class, monday);
        long job = jdbc.queryForObject("""
                insert into import_jobs (file_name, file_digest, file_size_bytes, state, uploaded_by_user_id,
                                         expires_at, committed_at, import_type, week_starting, reporting_week_id)
                values ('fake.xlsx', repeat('a', 64), 10, 'COMMITTED', ?, now(), now(),
                        'WEEKLY_REGISTER', cast(? as date), ?)
                returning id
                """, Long.class, adminId, monday, week);
        jdbc.update("""
                update client_transactions set superseded_by_import_job_id = ?
                where reporting_week_id = ? and import_job_id is null
                """, job, week);
        jdbc.update("update reporting_weeks set current_import_job_id = ? where id = ?", job, week);
        return job;
    }

    // ---------- reading one back ----------

    @Test
    void theEditFormGetsEveryField() {
        long id = thandisSale();

        ApiClient.Response detail = thandi.get("/api/transactions/" + id);

        assertThat(detail.status()).as(detail.body()).isEqualTo(200);
        assertThat(detail.number("$.clientId")).isEqualTo(thandisClient);
        assertThat(detail.text("$.transactionDate")).isEqualTo(TUESDAY);
        assertThat(detail.size("$.lines")).isEqualTo(2);
        assertThat(money(detail, "$.quotationTotal")).isEqualTo(1800.5);
        assertThat(detail.value("$.editable")).isEqualTo(true);
        assertThat(detail.number("$.version")).isZero();
        assertThat(manager.get("/api/transactions/" + id).status()).isEqualTo(200);
        assertThat(manager.get("/api/transactions/999999").status()).isEqualTo(404);
    }

    // ---------- changing ----------

    @Test
    void aConsultantChangesTheirOwnSale() {
        long id = thandisSale();

        ApiClient.Response changed = patch(thandi, id, edit(0, "2026-09-16", """
                {"productId":%d,"amount":1200},{"productId":%d,"amount":100}""".formatted(combo, ivc),
                ",\"amountToClient\":4000"));

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(changed.text("$.transactionDate")).isEqualTo("2026-09-16");
        assertThat(changed.number("$.dayOfWeek")).isEqualTo(3);
        assertThat(money(changed, "$.quotationTotal")).isEqualTo(1300.0);
        assertThat(money(changed, "$.bankedTotal")).isEqualTo(1300.0);
        assertThat(money(changed, "$.amountToClient")).isEqualTo(4000.0);
        assertThat(changed.number("$.version")).isEqualTo(1);
        assertThat(count("select count(*) from client_transaction_lines where transaction_id = ?", id))
                .isEqualTo(2);

        String audit = jdbc.queryForObject(
                "select metadata::text from audit_logs where action = 'SALE_EDITED' and target_id = ?",
                String.class, String.valueOf(id));
        assertThat(audit).contains("transactionDate", "lines", "amountToClient");

        ApiClient.Response summary = manager.get("/api/reports/weeks/2026-09-14/summary");
        assertThat(money(summary, "$.week.quotationTotal")).as("the reports count the new figures")
                .isEqualTo(1300.0);
    }

    @Test
    void someoneSavingFirstGivesAStaleVersion() {
        long id = thandisSale();
        assertThat(patch(thandi, id, edit(0, TUESDAY, sameLines(), ",\"amountToClient\":10")).status())
                .isEqualTo(200);

        ApiClient.Response late = patch(manager, id, edit(0, TUESDAY, sameLines(), ",\"amountToClient\":20"));
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.text("$.type")).isEqualTo("urn:credit-advisory:conflict:stale-version");

        ApiClient.Response lateDelete = delete(manager, id, 0);
        assertThat(lateDelete.status()).isEqualTo(409);
        assertThat(count("select count(*) from client_transactions")).isEqualTo(1);
    }

    @Test
    void sendingTheSameSaleBackSavesNothing() {
        long id = thandisSale();

        ApiClient.Response same = patch(thandi, id, edit(0, TUESDAY, sameLines(), ""));

        assertThat(same.status()).as(same.body()).isEqualTo(200);
        assertThat(same.number("$.version")).isZero();
        assertThat(count("select count(*) from audit_logs where action = 'SALE_EDITED'")).isZero();
    }

    @Test
    void onlyTheirOwnSalesForAConsultant() {
        long id = thandisSale();
        long siphos = manager.sendWithHeader("POST", "/api/transactions", """
                {"clientId":%d,"consultantId":%d,"transactionDate":"%s","lines":[{"productId":%d,"amount":10}]}"""
                .formatted(thandisClient, siphoId, TUESDAY, combo), "Idempotency-Key", UUID.randomUUID().toString())
                .number("$.id");

        assertThat(thandi.get("/api/transactions/" + siphos).status()).as("looks missing").isEqualTo(404);
        assertThat(patch(thandi, siphos, edit(0, TUESDAY, sameLines(), "")).status()).isEqualTo(404);
        assertThat(delete(thandi, siphos, 0).status()).isEqualTo(404);

        ApiClient.Response giveAway = patch(thandi, id, edit(0, TUESDAY, sameLines(), ",\"consultantId\":" + siphoId));
        assertThat(giveAway.status()).isEqualTo(403);
        assertThat(giveAway.text("$.type")).isEqualTo("urn:credit-advisory:sale:cannot-capture-for-others");

        ApiClient.Response moved = patch(manager, id, edit(0, TUESDAY, sameLines(), ",\"consultantId\":" + siphoId));
        assertThat(moved.status()).as(moved.body()).isEqualTo(200);
        assertThat(moved.number("$.consultantId")).isEqualTo(siphoId);
        assertThat(thandi.get("/api/transactions/" + id).status()).as("no longer hers").isEqualTo(404);
    }

    @Test
    void badChangesAreRefused() {
        long id = thandisSale();
        String tomorrow = LocalDate.now(ZoneId.of("Africa/Johannesburg")).plusDays(1).toString();

        ApiClient.Response future = patch(thandi, id, edit(0, tomorrow, sameLines(), ""));
        assertThat(future.status()).isEqualTo(422);
        assertThat(future.text("$.type")).isEqualTo("urn:credit-advisory:sale:date-in-future");

        assertThat(patch(thandi, id, edit(0, TUESDAY, "", "")).status()).as("no products").isEqualTo(400);
        assertThat(thandi.patch("/api/transactions/" + id, """
                {"transactionDate":"%s","lines":[%s]}""".formatted(TUESDAY, sameLines())).status())
                .as("no version").isEqualTo(400);
        assertThat(delete(thandi, id, 0).status()).isEqualTo(204);
        assertThat(patch(thandi, id, edit(0, TUESDAY, sameLines(), "")).status()).as("deleted").isEqualTo(404);
    }

    @Test
    void aProductAlreadyOnTheSaleCanStayAfterItIsDeactivated() {
        long id = thandisSale();
        jdbc.update("update products set active = false, counts_toward_banked = false where id = ?", combo);

        ApiClient.Response kept = patch(thandi, id, edit(0, TUESDAY, """
                {"productId":%d,"amount":1500},{"productId":%d,"amount":500}""".formatted(combo, creditRepair), ""));
        assertThat(kept.status()).as(kept.body()).isEqualTo(200);
        assertThat(money(kept, "$.bankedTotal")).as("Combo keeps the flags it was saved with").isEqualTo(1500.0);

        jdbc.update("update products set active = false where id = ?", ivc);
        ApiClient.Response added = patch(thandi, id, edit(1, TUESDAY, """
                {"productId":%d,"amount":1500},{"productId":%d,"amount":5}""".formatted(combo, ivc), ""));
        assertThat(added.status()).isEqualTo(422);
        assertThat(added.text("$.type")).isEqualTo("urn:credit-advisory:sale:product-not-active");
    }

    // ---------- deleting ----------

    @Test
    void deletingASaleTakesItOutOfTheReports() {
        long id = thandisSale();
        assertThat(manager.get("/api/reports/weeks").texts("$.weeks[*].weekStarting")).containsExactly("2026-09-14");

        ApiClient.Response gone = delete(thandi, id, 0);

        assertThat(gone.status()).as(gone.body()).isEqualTo(204);
        assertThat(count("select count(*) from client_transactions")).isZero();
        assertThat(count("select count(*) from client_transaction_lines")).isZero();
        assertThat(manager.get("/api/reports/weeks").size("$.weeks")).isZero();
        String audit = jdbc.queryForObject(
                "select metadata::text from audit_logs where action = 'SALE_DELETED' and target_id = ?",
                String.class, String.valueOf(id));
        assertThat(audit).contains("1800.50", TUESDAY);
        assertThat(delete(thandi, id, 0).status()).as("already gone").isEqualTo(404);
    }

    // ---------- once the spreadsheet is in ----------

    @Test
    void aReplacedSaleCanNoLongerChange() {
        long id = thandisSale();
        importWeek("2026-09-14");

        assertThat(thandi.get("/api/transactions/" + id).value("$.editable")).isEqualTo(false);

        ApiClient.Response changed = patch(thandi, id, edit(0, TUESDAY, sameLines(), ",\"amountToClient\":1"));
        assertThat(changed.status()).isEqualTo(409);
        assertThat(changed.text("$.type")).isEqualTo("urn:credit-advisory:sale:replaced");
        ApiClient.Response deleted = delete(manager, id, 0);
        assertThat(deleted.status()).isEqualTo(409);
        assertThat(deleted.text("$.type")).isEqualTo("urn:credit-advisory:sale:replaced");
    }

    @Test
    void anImportedSaleCanNeverChange() {
        long job = importWeek("2026-09-14");
        long week = count("select id from reporting_weeks where week_starting = date '2026-09-14'");
        long id = jdbc.queryForObject("""
                insert into client_transactions (client_id, consultant_id, transaction_date, import_job_id,
                    source_sheet, source_row_number, created_by_user_id, reporting_week_id, day_of_week)
                values (?, ?, date '2026-09-15', ?, 'Sheet1', 2, ?, ?, 2)
                returning id
                """, Long.class, thandisClient, thandiId, job, adminId, week);

        ApiClient.Response changed = patch(manager, id, edit(0, TUESDAY, sameLines(), ""));
        assertThat(changed.status()).isEqualTo(409);
        assertThat(changed.text("$.type")).isEqualTo("urn:credit-advisory:sale:imported");
        assertThat(delete(manager, id, 0).text("$.type")).isEqualTo("urn:credit-advisory:sale:imported");
    }

    @Test
    void aSaleCannotMoveIntoAnImportedWeek() {
        long id = thandisSale();
        importWeek("2026-09-07");

        ApiClient.Response moved = patch(thandi, id, edit(0, "2026-09-08", sameLines(), ""));

        assertThat(moved.status()).isEqualTo(409);
        assertThat(moved.text("$.type")).isEqualTo("urn:credit-advisory:sale:week-already-imported");
        assertThat(patch(thandi, id, edit(0, "2026-09-21", sameLines(), "")).status())
                .as("the next week is still open").isEqualTo(200);
    }
}
