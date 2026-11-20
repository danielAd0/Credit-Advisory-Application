package za.co.mafori.creditadvisory.employer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * GET /api/employers/duplicate-suggestions: groups of employers that are
 * probably the same, ready for POST /api/employers/{id}/merge. Starts with no
 * employers, so only the made-up ones below exist.
 */
class EmployerDuplicatesApiTest extends ApiTestBase {

    private ApiClient admin;
    private long checked;
    private long fromImport;

    @Override
    protected boolean standardCatalogue() {
        return false;
    }

    private long employer(String name, boolean needsReview) {
        return jdbc.queryForObject("insert into employers (name, needs_review) values (?, ?) returning id",
                Long.class, name, needsReview);
    }

    @BeforeEach
    void setUp() {
        admin = signIn(ADMIN_EMAIL);
        checked = employer("TEST RETAIL", false);
        fromImport = employer("Test Retail (Pty) Ltd", true);
        employer("SOMETHING ELSE", true);
        long client = createClient("Alpha", "Person", null);
        jdbc.update("update clients set employer_id = ? where id = ?", fromImport, client);
    }

    @Test
    void groupsTheSpellingsAndSuggestsWhichOneToKeep() {
        ApiClient.Response r = admin.get("/api/employers/duplicate-suggestions");

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.number("$.groupCount")).isEqualTo(1);
        assertThat(r.number("$.employerCount")).isEqualTo(2);
        assertThat(r.number("$.groups[0].suggestedTargetId")).as("checked beats waiting for review")
                .isEqualTo(checked);
        assertThat(r.numbers("$.groups[0].employers[*].id")).containsExactly(checked, fromImport);
        assertThat(r.numbers("$.groups[0].employers[*].clients")).containsExactly(0L, 1L);
        assertThat(r.texts("$.groups[0].employers[*].name")).doesNotContain("SOMETHING ELSE");
    }

    @Test
    void theSuggestionFeedsStraightIntoAMerge() {
        ApiClient.Response r = admin.get("/api/employers/duplicate-suggestions");
        long version = r.number("$.groups[0].employers[1].version");

        ApiClient.Response merged = admin.post("/api/employers/" + fromImport + "/merge", """
                {"targetEmployerId":%d,"version":%d,"reason":"Same employer"}""".formatted(checked, version));

        assertThat(merged.status()).as(merged.body()).isEqualTo(200);
        assertThat(admin.get("/api/employers/duplicate-suggestions").number("$.groupCount")).isZero();
    }

    @Test
    void managersMayLookButConsultantsMayNot() {
        createUser("manager@test.local", null, RoleName.MANAGER);
        long consultant = createConsultant("C001", "Thandi Test");
        createUser("thandi@test.local", consultant, RoleName.CONSULTANT);

        assertThat(signIn("manager@test.local").get("/api/employers/duplicate-suggestions").status())
                .isEqualTo(200);
        assertThat(signIn("thandi@test.local").get("/api/employers/duplicate-suggestions").status())
                .isEqualTo(403);
    }

    @Test
    void nothingIsChangedOrAudited() {
        long employersBefore = jdbc.queryForObject("select count(*) from employers", Long.class);
        long auditBefore = jdbc.queryForObject("select count(*) from audit_logs", Long.class);

        admin.get("/api/employers/duplicate-suggestions");

        assertThat(jdbc.queryForObject("select count(*) from employers", Long.class)).isEqualTo(employersBefore);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs", Long.class)).isEqualTo(auditBefore);
    }
}
