package za.co.mafori.creditadvisory.sale;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * GET /api/transactions?from=&to= - the Sales history page (docs/api-sales.md
 * section 3). Three
 * typed-in sales: Thandi's on Tue 15 Sept, Sipho's on Wed 16 Sept, and one in
 * the next week.
 */
class SaleHistoryApiTest extends ApiTestBase {

    private static final String WEEK = "from=2026-09-14&to=2026-09-20";

    private long thandiId;
    private long siphoId;
    private long thandisSale;
    private long siphosSale;
    private ApiClient thandi;
    private ApiClient manager;

    @BeforeEach
    void captureThreeSales() {
        thandiId = createConsultant("C001", "Thandi Test");
        siphoId = createConsultant("C002", "Sipho Test");
        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
        long thandisClient = createClient("Test", "Alpha", thandiId);
        long siphosClient = createClient("Test", "Bravo", siphoId);
        long combo = productId("COMBO");
        long creditRepair = productId("CREDIT_REPAIR"); // counts toward quotation, not banked
        thandi = signIn("thandi@test.local");
        manager = signIn("manager@test.local");

        thandisSale = capture(thandi, thandisClient, "2026-09-15", """
                {"productId":%d,"amount":1500},{"productId":%d,"amount":300.50}""".formatted(combo, creditRepair));
        siphosSale = capture(manager, siphosClient, "2026-09-16", """
                {"productId":%d,"amount":200}""".formatted(combo));
        capture(thandi, thandisClient, "2026-09-22", """
                {"productId":%d,"amount":50}""".formatted(combo));
    }

    private long productId(String code) {
        return jdbc.queryForObject("select id from products where code = ?", Long.class, code);
    }

    private static long capture(ApiClient as, long clientId, String date, String lines) {
        ApiClient.Response saved = as.sendWithHeader("POST", "/api/transactions", """
                {"clientId":%d,"transactionDate":"%s","lines":[%s]}""".formatted(clientId, date, lines),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertThat(saved.status()).as(saved.body()).isEqualTo(201);
        return saved.number("$.id");
    }

    private static double money(ApiClient.Response response, String path) {
        return ((Number) response.value(path)).doubleValue();
    }

    @Test
    void managerSeesEverySaleInTheRangeNewestFirstWithNamesAndTotals() {
        ApiClient.Response page = manager.get("/api/transactions?" + WEEK);

        assertThat(page.status()).as(page.body()).isEqualTo(200);
        assertThat(page.number("$.totalItems")).as("the sale of the 22nd is outside").isEqualTo(2);
        assertThat(page.numbers("$.items[*].id")).containsExactly(siphosSale, thandisSale);
        assertThat(page.text("$.items[0].clientName")).isEqualTo("Test Bravo");
        assertThat(page.text("$.items[0].consultantName")).isEqualTo("Sipho Test");
        assertThat(page.text("$.items[1].weekStarting")).isEqualTo("2026-09-14");
        assertThat(page.size("$.items[1].lines")).isEqualTo(2);
        assertThat(money(page, "$.items[1].quotationTotal")).isEqualTo(1800.5);
        assertThat(money(page, "$.items[1].bankedTotal")).isEqualTo(1500.0);
        assertThat(page.value("$.items[1].typedIn")).isEqualTo(true);
        assertThat(money(page, "$.quotationTotal")).isEqualTo(2000.5);
        assertThat(money(page, "$.bankedTotal")).isEqualTo(1700.0);
        assertThat(page.body()).as("no ID number, not even masked").doesNotContain("idNumber");
    }

    @Test
    void managerFiltersByConsultantAndPages() {
        ApiClient.Response sipho = manager.get("/api/transactions?" + WEEK + "&consultantId=" + siphoId);
        assertThat(sipho.numbers("$.items[*].id")).containsExactly(siphosSale);
        assertThat(money(sipho, "$.quotationTotal")).isEqualTo(200.0);

        ApiClient.Response second = manager.get("/api/transactions?" + WEEK + "&size=1&page=1");
        assertThat(second.numbers("$.items[*].id")).containsExactly(thandisSale);
        assertThat(second.number("$.totalPages")).isEqualTo(2);
        assertThat(money(second, "$.quotationTotal")).as("totals cover the whole range").isEqualTo(2000.5);

        assertThat(manager.get("/api/transactions?" + WEEK + "&consultantId=999999").status()).isEqualTo(404);
    }

    @Test
    void aConsultantOnlySeesTheirOwnSales() {
        ApiClient.Response own = thandi.get("/api/transactions?" + WEEK + "&consultantId=" + siphoId);

        assertThat(own.status()).as(own.body()).isEqualTo(200);
        assertThat(own.numbers("$.items[*].id")).as("someone else's consultantId is ignored")
                .containsExactly(thandisSale);
        assertThat(money(own, "$.quotationTotal")).isEqualTo(1800.5);
    }

    @Test
    void aSaleTheSpreadsheetReplacedIsLeftOut() {
        long week = jdbc.queryForObject("select id from reporting_weeks where week_starting = date '2026-09-14'",
                Long.class);
        long job = jdbc.queryForObject("""
                insert into import_jobs (file_name, file_digest, file_size_bytes, state, uploaded_by_user_id,
                                         expires_at, committed_at, import_type, week_starting, reporting_week_id)
                values ('fake.xlsx', repeat('a', 64), 10, 'COMMITTED', ?, now(), now(),
                        'WEEKLY_REGISTER', date '2026-09-14', ?)
                returning id
                """, Long.class, adminId, week);
        jdbc.update("update client_transactions set superseded_by_import_job_id = ? where id = ?", job, thandisSale);

        ApiClient.Response page = manager.get("/api/transactions?" + WEEK);

        assertThat(page.numbers("$.items[*].id")).as("the import counts instead").containsExactly(siphosSale);
        assertThat(money(page, "$.quotationTotal")).isEqualTo(200.0);
    }

    @Test
    void badRangesAreRefused() {
        ApiClient.Response backwards = manager.get("/api/transactions?from=2026-09-20&to=2026-09-14");
        assertThat(backwards.status()).isEqualTo(400);
        assertThat(backwards.text("$.type")).isEqualTo("urn:credit-advisory:sale:invalid-date-range");

        assertThat(manager.get("/api/transactions?to=2026-09-20").status()).as("from is required").isEqualTo(400);
        assertThat(manager.get("/api/transactions?from=yesterday&to=2026-09-20").status()).isEqualTo(400);
    }

    @Test
    void withAClientIdItIsStillThatClientsList() {
        ApiClient.Response list = thandi.get("/api/transactions?clientId="
                + jdbc.queryForObject("select client_id from client_transactions where id = ?", Long.class,
                        thandisSale));

        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(list.size("$")).as("a plain list, both of Thandi's sales").isEqualTo(2);
    }
}
