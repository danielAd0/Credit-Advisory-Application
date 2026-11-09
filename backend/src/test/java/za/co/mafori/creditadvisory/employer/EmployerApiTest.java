package za.co.mafori.creditadvisory.employer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * Merging an employer an import created (needsReview) into the right one, or
 * keeping it.
 */
class EmployerMergeApiTest extends ApiTestBase {

    private ApiClient admin;
    private long target;
    private long flagged;
    private long client;

    @BeforeEach
    void setUp() {
        admin = signIn(ADMIN_EMAIL);
        target = jdbc.queryForObject("select min(id) from employers where active and not needs_review", Long.class);
        // What an import does with a spelling nobody has seen: a new employer, flagged.
        flagged = jdbc.queryForObject("""
                insert into employers (name, needs_review) values ('PHUMELELA LOCAL MUNIC', true) returning id
                """, Long.class);
        client = createClient("Alpha", "Person", null);
        jdbc.update("update clients set employer_id = ? where id = ?", flagged, client);
    }

    private long version(long id) {
        return jdbc.queryForObject("select version from employers where id = ?", Long.class, id);
    }

    @Test
    void flaggedEmployersCanBeListed() {
        ApiClient.Response list = admin.get("/api/employers?needsReview=true");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.numbers("$.items[*].id")).containsExactly(flagged);
        assertThat(list.value("$.items[0].needsReview")).isEqualTo(true);
    }

    @Test
    void mergeMovesEverythingAndKeepsTheSpelling() {
        ApiClient.Response merged = admin.post("/api/employers/" + flagged + "/merge", """
                {"targetEmployerId":%d,"version":%d,"reason":"Same municipality"}"""
                .formatted(target, version(flagged)));
        assertThat(merged.status()).as(merged.body()).isEqualTo(200);
        assertThat(merged.number("$.clientsMoved")).isEqualTo(1);
        assertThat(merged.value("$.nameKeptAsAlias")).isEqualTo(true);
        assertThat(merged.number("$.target.id")).isEqualTo(target);

        assertThat(admin.get("/api/employers/" + flagged).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select employer_id from clients where id = ?", Long.class, client))
                .isEqualTo(target);
        assertThat(admin.get("/api/employers/" + target + "/aliases").texts("$[*].alias"))
                .contains("PHUMELELA LOCAL MUNIC");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'EMPLOYER_MERGED'",
                Long.class)).isEqualTo(1);
    }

    @Test
    void mergeIsRefusedWhenItMakesNoSense() {
        assertThat(admin.post("/api/employers/" + flagged + "/merge", """
                {"targetEmployerId":%d,"version":%d,"reason":"x"}""".formatted(flagged, version(flagged)))
                .text("$.type")).isEqualTo("urn:credit-advisory:employer:merge-into-itself");
        assertThat(admin.post("/api/employers/" + flagged + "/merge", """
                {"targetEmployerId":%d,"version":%d,"reason":"x"}""".formatted(target, version(flagged) + 1))
                .status()).isEqualTo(409);
        assertThat(admin.post("/api/employers/" + flagged + "/merge", """
                {"targetEmployerId":%d,"version":%d}""".formatted(target, version(flagged))).status())
                .as("a reason is required").isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from employers where id = ?", Long.class, flagged))
                .isEqualTo(1);
    }

    @Test
    void aFlaggedEmployerCanBeKept() {
        ApiClient.Response kept = admin.patch("/api/employers/" + flagged + "/review",
                "{\"version\":%d}".formatted(version(flagged)));
        assertThat(kept.status()).as(kept.body()).isEqualTo(200);
        assertThat(kept.value("$.needsReview")).isEqualTo(false);
        assertThat(admin.get("/api/employers?needsReview=true").number("$.totalItems")).isZero();
    }

    @Test
    void onlyAnAdminCanMergeOrKeep() {
        createUser("manager@test.local", null, RoleName.MANAGER);
        ApiClient manager = signIn("manager@test.local");
        assertThat(manager.post("/api/employers/" + flagged + "/merge", """
                {"targetEmployerId":%d,"version":%d,"reason":"x"}""".formatted(target, version(flagged))).status())
                .isEqualTo(403);
        assertThat(manager.patch("/api/employers/" + flagged + "/review", "{\"version\":0}").status()).isEqualTo(403);
        assertThat(manager.get("/api/employers/" + target + "/aliases").status()).isEqualTo(200);
    }
}
