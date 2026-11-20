package za.co.mafori.creditadvisory.employer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * POST /api/employers/{targetId}/merge-group: several spellings merged into one
 * employer at once, all or nothing. Starts with no employers, so only the
 * made-up ones below exist.
 */
class EmployerMergeGroupApiTest extends ApiTestBase {

    private ApiClient admin;
    private long target;
    private long second;
    private long third;
    private long clientOfSecond;
    private long clientOfThird;

    @Override
    protected boolean standardCatalogue() {
        return false;
    }

    private long employer(String name, boolean needsReview) {
        return jdbc.queryForObject("insert into employers (name, needs_review) values (?, ?) returning id",
                Long.class, name, needsReview);
    }

    private long version(long id) {
        return jdbc.queryForObject("select version from employers where id = ?", Long.class, id);
    }

    private long employers() {
        return jdbc.queryForObject("select count(*) from employers", Long.class);
    }

    private ApiClient.Response mergeGroup(ApiClient as, long into, String employersJson) {
        return as.post("/api/employers/" + into + "/merge-group", """
                {"employers":[%s],"reason":"Same employer: spelling variant"}""".formatted(employersJson));
    }

    private String entry(long id) {
        return "{\"id\":%d,\"version\":%d}".formatted(id, version(id));
    }

    @BeforeEach
    void setUp() {
        admin = signIn(ADMIN_EMAIL);
        target = employer("TEST RETAIL", false);
        second = employer("Test Retail (Pty) Ltd", true);
        third = employer("TEST RETAIL PTY LTD.", true);
        clientOfSecond = createClient("Alpha", "Person", null);
        clientOfThird = createClient("Bravo", "Person", null);
        jdbc.update("update clients set employer_id = ? where id = ?", second, clientOfSecond);
        jdbc.update("update clients set employer_id = ? where id = ?", third, clientOfThird);
    }

    @Test
    void mergesTheWholeGroupInOneGo() {
        ApiClient.Response r = mergeGroup(admin, target, entry(third) + "," + entry(second));

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.number("$.target.id")).isEqualTo(target);
        assertThat(r.numbers("$.merged[*].mergedEmployerId")).as("in id order").containsExactly(second, third);
        assertThat(r.number("$.clientsMoved")).isEqualTo(2);

        assertThat(employers()).isEqualTo(1);
        assertThat(jdbc.queryForList("select employer_id from clients where id in (?, ?)", Long.class,
                clientOfSecond, clientOfThird)).containsOnly(target);
        assertThat(admin.get("/api/employers/" + target + "/aliases").texts("$[*].alias"))
                .contains("Test Retail (Pty) Ltd", "TEST RETAIL PTY LTD.");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'EMPLOYER_MERGED'",
                Long.class)).as("audited like single merges").isEqualTo(2);
    }

    @Test
    void oneProblemAndNothingIsMerged() {
        String staleThird = "{\"id\":%d,\"version\":%d}".formatted(third, version(third) + 1);

        ApiClient.Response stale = mergeGroup(admin, target, entry(second) + "," + staleThird);

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.text("$.type")).isEqualTo("urn:credit-advisory:conflict:stale-version");
        assertThat(stale.text("$.detail")).contains("TEST RETAIL PTY LTD.");
        assertThat(employers()).as("the first one was not merged either").isEqualTo(3);
        assertThat(jdbc.queryForObject("select employer_id from clients where id = ?", Long.class, clientOfSecond))
                .isEqualTo(second);

        jdbc.update("update employers set active = false where id = ?", target);
        ApiClient.Response inactive = mergeGroup(admin, target, entry(second) + "," + entry(third));
        assertThat(inactive.status()).isEqualTo(409);
        assertThat(inactive.text("$.type")).isEqualTo("urn:credit-advisory:employer:merge-target-inactive");
        assertThat(employers()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'EMPLOYER_MERGED'",
                Long.class)).isZero();
    }

    @Test
    void theListMustMakeSense() {
        assertThat(mergeGroup(admin, target, entry(target)).text("$.type"))
                .isEqualTo("urn:credit-advisory:employer:merge-into-itself");
        assertThat(mergeGroup(admin, target, entry(second) + "," + entry(second)).text("$.type"))
                .isEqualTo("urn:credit-advisory:employer:merge-group-duplicate");
        assertThat(mergeGroup(admin, target, "{\"id\":999999,\"version\":0}").status()).isEqualTo(404);
        assertThat(mergeGroup(admin, target, "").status()).as("an empty list").isEqualTo(400);
        assertThat(admin.post("/api/employers/" + target + "/merge-group",
                "{\"employers\":[%s]}".formatted(entry(second))).status()).as("a reason is required")
                .isEqualTo(400);
        assertThat(employers()).isEqualTo(3);
    }

    @Test
    void onlyAnAdmin() {
        createUser("manager@test.local", null, RoleName.MANAGER);

        assertThat(mergeGroup(signIn("manager@test.local"), target, entry(second)).status()).isEqualTo(403);
        assertThat(employers()).isEqualTo(3);
    }
}
