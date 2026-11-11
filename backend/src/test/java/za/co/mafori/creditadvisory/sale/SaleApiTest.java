package za.co.mafori.creditadvisory.sale;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * Sales typed in on the Consultant actions page: POST and GET
 * /api/transactions and the dropdowns (docs/api-sales.md). The week of 14 Sept 2026 has no import.
 */
class SaleApiTest extends ApiTestBase {

    private static final String TUESDAY = "2026-09-15";

    private long thandiId;
    private long siphoId;
    private long thandisClient;
    private long siphosClient;
    private long unassignedClient;
    private long combo;
    private long creditRepair;
    private ApiClient thandi;
    private ApiClient manager;

    @BeforeEach
    void createPeople() {
        thandiId = createConsultant("C001", "Thandi Test");
        siphoId = createConsultant("C002", "Sipho Test");
        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
        thandisClient = createClient("Test", "Alpha", thandiId);
        siphosClient = createClient("Test", "Bravo", siphoId);
        unassignedClient = createClient("Test", "Charlie", null);
        combo = productId("COMBO");
        creditRepair = productId("CREDIT_REPAIR"); // counts toward quotation, not banked
        thandi = signIn("thandi@test.local");
        manager = signIn("manager@test.local");
    }

    private long productId(String code) {
        return jdbc.queryForObject("select id from products where code = ?", Long.class, code);
    }

    private static String body(long clientId, String date, String linesJson, String extraJson) {
        return """
                {"clientId":%d,"transactionDate":"%s","lines":[%s]%s}""".formatted(clientId, date, linesJson,
                extraJson);
    }

    private String twoLines() {
        return """
                {"productId":%d,"amount":1500},{"productId":%d,"amount":300.50}""".formatted(combo, creditRepair);
    }

    private static ApiClient.Response capture(ApiClient as, String key, String json) {
        return as.sendWithHeader("POST", "/api/transactions", json, "Idempotency-Key", key);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static double money(ApiClient.Response response, String path) {
        return ((Number) response.value(path)).doubleValue();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ---------- saving ----------

    @Test
    void consultantCapturesASaleForTheirOwnClient() {
        ApiClient.Response saved = capture(thandi, newKey(), body(thandisClient, TUESDAY, twoLines(), """
                ,"amountToClient":5000,"paymentTermId":%d,"primaryStructureBudgetList":" Vital ",
                 "primaryStructureLoanAmount":2500""".formatted(
                count("select id from payment_terms where code = 'ONCE_OFF'"))));

        assertThat(saved.status()).as(saved.body()).isEqualTo(201);
        assertThat(saved.number("$.consultantId")).isEqualTo(thandiId);
        assertThat(saved.text("$.weekStarting")).isEqualTo("2026-09-14");
        assertThat(saved.number("$.dayOfWeek")).isEqualTo(2);
        assertThat(saved.size("$.lines")).isEqualTo(2);
        assertThat(money(saved, "$.quotationTotal")).isEqualTo(1800.5);
        assertThat(money(saved, "$.bankedTotal")).isEqualTo(1500.0);
        assertThat(saved.value("$.replaced")).isEqualTo(false);

        long id = saved.number("$.id");
        assertThat(jdbc.queryForObject("select primary_structure_budget_list from client_transactions where id = ?",
                String.class, id)).isEqualTo("Vital");
        assertThat(count("select count(*) from client_transactions where id = ? and import_job_id is null", id))
                .isEqualTo(1);
        // audit_logs.target_id is text (V4), so the id goes in as text.
        assertThat(count("select count(*) from audit_logs where action = 'SALE_CAPTURED' and target_id = ?",
                String.valueOf(id))).isEqualTo(1);
    }

    @Test
    void theSameKeySavesTheSaleOnce() {
        String key = newKey();
        ApiClient.Response first = capture(thandi, key, body(thandisClient, TUESDAY, twoLines(), ""));
        ApiClient.Response again = capture(thandi, key, body(thandisClient, TUESDAY, twoLines(), ""));

        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).as(again.body()).isEqualTo(200);
        assertThat(again.number("$.id")).isEqualTo(first.number("$.id"));
        assertThat(count("select count(*) from client_transactions")).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'SALE_CAPTURED'")).isEqualTo(1);
    }

    @Test
    void theKeyMustBeAUuid() {
        ApiClient.Response missing = thandi.post("/api/transactions", body(thandisClient, TUESDAY, twoLines(), ""));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.text("$.type")).isEqualTo("urn:credit-advisory:sale:idempotency-key-required");

        ApiClient.Response bad = capture(thandi, "not-a-uuid", body(thandisClient, TUESDAY, twoLines(), ""));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.text("$.type")).isEqualTo("urn:credit-advisory:sale:invalid-idempotency-key");
    }

    // ---------- who ----------

    @Test
    void consultantOnlyCapturesTheirOwnSales() {
        ApiClient.Response othersClient = capture(thandi, newKey(), body(siphosClient, TUESDAY, twoLines(), ""));
        assertThat(othersClient.status()).as("someone else's client looks missing").isEqualTo(404);

        ApiClient.Response forSipho = capture(thandi, newKey(),
                body(thandisClient, TUESDAY, twoLines(), ",\"consultantId\":" + siphoId));
        assertThat(forSipho.status()).isEqualTo(403);
        assertThat(forSipho.text("$.type")).isEqualTo("urn:credit-advisory:sale:cannot-capture-for-others");
        assertThat(count("select count(*) from client_transactions")).isZero();
    }

    @Test
    void managerChoosesTheConsultantOrItIsTheClientsConsultant() {
        ApiClient.Response clients = capture(manager, newKey(), body(thandisClient, TUESDAY, twoLines(), ""));
        assertThat(clients.status()).as(clients.body()).isEqualTo(201);
        assertThat(clients.number("$.consultantId")).isEqualTo(thandiId);

        ApiClient.Response nobody = capture(manager, newKey(), body(unassignedClient, TUESDAY, twoLines(), ""));
        assertThat(nobody.status()).isEqualTo(422);
        assertThat(nobody.text("$.type")).isEqualTo("urn:credit-advisory:sale:consultant-required");

        ApiClient.Response chosen = capture(manager, newKey(),
                body(unassignedClient, TUESDAY, twoLines(), ",\"consultantId\":" + siphoId));
        assertThat(chosen.status()).isEqualTo(201);
        assertThat(chosen.number("$.consultantId")).isEqualTo(siphoId);

        long leratoId = createConsultant("C003", "Lerato Test");
        setConsultantStatus(leratoId, ConsultantStatus.SUSPENDED);
        ApiClient.Response suspended = capture(manager, newKey(),
                body(unassignedClient, TUESDAY, twoLines(), ",\"consultantId\":" + leratoId));
        assertThat(suspended.status()).isEqualTo(422);
        assertThat(suspended.text("$.type")).isEqualTo("urn:credit-advisory:sale:consultant-not-active");
    }

    // ---------- what ----------

    @Test
    void badSalesAreRefused() {
        String tomorrow = LocalDate.now(ZoneId.of("Africa/Johannesburg")).plusDays(1).toString();
        ApiClient.Response future = capture(thandi, newKey(), body(thandisClient, tomorrow, twoLines(), ""));
        assertThat(future.status()).isEqualTo(422);
        assertThat(future.text("$.type")).isEqualTo("urn:credit-advisory:sale:date-in-future");

        assertThat(capture(thandi, newKey(), body(thandisClient, TUESDAY, "", "")).status())
                .as("no products").isEqualTo(400);
        assertThat(capture(thandi, newKey(), body(thandisClient, TUESDAY,
                "{\"productId\":%d,\"amount\":0}".formatted(combo), "")).status()).as("zero").isEqualTo(400);
        assertThat(capture(thandi, newKey(), body(thandisClient, TUESDAY,
                "{\"productId\":%d,\"amount\":10.555}".formatted(combo), "")).status()).as("3 decimals")
                .isEqualTo(400);
        assertThat(capture(thandi, newKey(), body(thandisClient, TUESDAY, twoLines(), ",\"amountToClient\":-1"))
                .status()).as("negative").isEqualTo(400);

        ApiClient.Response twice = capture(thandi, newKey(), body(thandisClient, TUESDAY,
                "{\"productId\":%d,\"amount\":10},{\"productId\":%d,\"amount\":20}".formatted(combo, combo), ""));
        assertThat(twice.status()).isEqualTo(400);
        assertThat(twice.text("$.type")).isEqualTo("urn:credit-advisory:sale:duplicate-product");

        assertThat(capture(thandi, newKey(), body(thandisClient, TUESDAY,
                "{\"productId\":999999,\"amount\":10}", "")).status()).isEqualTo(404);
        assertThat(capture(thandi, newKey(), body(thandisClient, TUESDAY, twoLines(), ",\"employerId\":999999"))
                .status()).isEqualTo(404);

        jdbc.update("update products set active = false where id = ?", combo);
        ApiClient.Response inactive = capture(thandi, newKey(), body(thandisClient, TUESDAY, twoLines(), ""));
        assertThat(inactive.status()).isEqualTo(422);
        assertThat(inactive.text("$.type")).isEqualTo("urn:credit-advisory:sale:product-not-active");

        assertThat(count("select count(*) from client_transactions")).isZero();
    }

    // ---------- weeks and reports ----------

    @Test
    void aTypedInWeekShowsInTheReportsBeforeItsImport() {
        assertThat(capture(thandi, newKey(), body(thandisClient, TUESDAY, twoLines(), "")).status()).isEqualTo(201);

        ApiClient.Response weeks = manager.get("/api/reports/weeks");
        assertThat(weeks.texts("$.weeks[*].weekStarting")).containsExactly("2026-09-14");

        ApiClient.Response summary = manager.get("/api/reports/weeks/2026-09-14/summary");
        assertThat(summary.status()).as(summary.body()).isEqualTo(200);
        assertThat(money(summary, "$.week.quotationTotal")).isEqualTo(1800.5);
        assertThat(money(summary, "$.days[1].quotationTotal")).isEqualTo(1800.5);
    }

    @Test
    void anImportedWeekTakesNoMoreTypedInSales() {
        long week = jdbc.queryForObject(
                "insert into reporting_weeks (week_starting) values (date '2026-09-14') returning id", Long.class);
        long job = jdbc.queryForObject("""
                insert into import_jobs (file_name, file_digest, file_size_bytes, state, uploaded_by_user_id,
                                         expires_at, committed_at, import_type, week_starting, reporting_week_id)
                values ('fake.xlsx', repeat('a', 64), 10, 'COMMITTED', ?, now(), now(),
                        'WEEKLY_REGISTER', date '2026-09-14', ?)
                returning id
                """, Long.class, adminId, week);
        jdbc.update("update reporting_weeks set current_import_job_id = ? where id = ?", job, week);

        ApiClient.Response refused = capture(thandi, newKey(), body(thandisClient, TUESDAY, twoLines(), ""));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.text("$.type")).isEqualTo("urn:credit-advisory:sale:week-already-imported");
        assertThat(capture(thandi, newKey(), body(thandisClient, "2026-09-21", twoLines(), "")).status())
                .as("the next week is still open").isEqualTo(201);
    }

    // ---------- dropdowns ----------

    @Test
    void paymentTermsAndStructuresForTheDropdowns() {
        assertThat(thandi.get("/api/payment-terms").texts("$[*].name"))
                .containsExactly("Once off payment", "Within 12 months");
        assertThat(thandi.get("/api/structures").texts("$[*].name"))
                .containsExactly("MAFORI CREDIT", "VITAL CREDIT");

        jdbc.update("update structures set active = false where name = 'MAFORI CREDIT'");
        assertThat(thandi.get("/api/structures").texts("$[*].name")).containsExactly("VITAL CREDIT");
    }

    @Test
    void anInactiveStructureIsRefused() {
        long mafori = count("select id from structures where name = 'MAFORI CREDIT'");
        jdbc.update("update structures set active = false where id = ?", mafori);

        ApiClient.Response refused = capture(thandi, newKey(),
                body(thandisClient, TUESDAY, twoLines(), ",\"structureId\":" + mafori));
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.text("$.type")).isEqualTo("urn:credit-advisory:sale:structure-not-active");
    }

    // ---------- reading back ----------

    @Test
    void aClientsSalesCanBeReadBack() {
        long saved = capture(thandi, newKey(), body(thandisClient, TUESDAY, twoLines(), "")).number("$.id");
        long earlier = capture(thandi, newKey(), body(thandisClient, "2026-09-01",
                "{\"productId\":%d,\"amount\":100}".formatted(combo), "")).number("$.id");
        capture(manager, newKey(), body(siphosClient, TUESDAY, twoLines(), ""));

        ApiClient.Response all = thandi.get("/api/transactions?clientId=" + thandisClient);
        assertThat(all.status()).as(all.body()).isEqualTo(200);
        assertThat(all.size("$")).as("newest first, only this client").isEqualTo(2);
        assertThat(all.number("$[0].id")).isEqualTo(saved);
        assertThat(all.number("$[1].id")).isEqualTo(earlier);
        assertThat(money(all, "$[0].quotationTotal")).isEqualTo(1800.5);
        assertThat(money(all, "$[0].bankedTotal")).isEqualTo(1500.0);
        assertThat(all.size("$[0].lines")).isEqualTo(2);
        assertThat(all.value("$[0].typedIn")).isEqualTo(true);
        assertThat(all.body()).doesNotContain("idNumber");

        ApiClient.Response week = thandi.get(
                "/api/transactions?clientId=" + thandisClient + "&from=2026-09-14&to=2026-09-20");
        assertThat(week.size("$")).isEqualTo(1);
        assertThat(week.number("$[0].id")).isEqualTo(saved);

        assertThat(thandi.get("/api/transactions?clientId=" + thandisClient + "&from=2026-09-20&to=2026-09-14")
                .text("$.type")).isEqualTo("urn:credit-advisory:sale:invalid-date-range");
        assertThat(thandi.get("/api/transactions?clientId=" + siphosClient).status())
                .as("someone else's client looks missing").isEqualTo(404);
        assertThat(manager.get("/api/transactions?clientId=" + siphosClient).size("$")).isEqualTo(1);
    }
}
