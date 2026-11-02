package za.co.mafori.creditadvisory.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/** GET /api/audit-logs - reading the audit trail (ADMIN only). */
class AuditLogApiTest extends ApiTestBase {

    private ApiClient admin;
    private long consultantId;
    private long lockedUserId;

    /**
     * Produces a few known entries: CONSULTANT_CREATED, CONSULTANT_STATUS_CHANGED,
     * ACCOUNT_LOCKED.
     */
    @BeforeEach
    void createEntries() {
        admin = signIn(ADMIN_EMAIL);

        ApiClient.Response created = admin.post("/api/consultants", """
                {"consultantCode":"C001","fullName":"Thandi Test"}""");
        consultantId = created.number("$.id");
        admin.patch("/api/consultants/" + consultantId + "/status", """
                {"status":"SUSPENDED","reason":"Under investigation","version":%d}"""
                .formatted(created.number("$.version")));

        lockedUserId = createUser("locked@test.local", null, RoleName.MANAGER);
        for (int attempt = 1; attempt <= 5; attempt++) {
            browser().signIn("locked@test.local", "Wrong-Password-000");
        }
    }

    @Test
    void adminSeesNewestFirstWithActorAndMetadata() {
        ApiClient.Response page = admin.get("/api/audit-logs");

        assertThat(page.status()).as(page.body()).isEqualTo(200);
        assertThat(page.number("$.totalItems")).isEqualTo(3);
        assertThat(page.texts("$.items[*].action"))
                .containsExactly("ACCOUNT_LOCKED", "CONSULTANT_STATUS_CHANGED", "CONSULTANT_CREATED");

        // Metadata comes back as a real JSON object, not a string.
        assertThat(page.text("$.items[1].metadata.reason")).isEqualTo("Under investigation");
        assertThat(page.text("$.items[1].metadata.to")).isEqualTo("SUSPENDED");
        assertThat(page.text("$.items[1].actor.email")).isEqualTo(ADMIN_EMAIL);

        // Nobody was signed in when the account locked.
        assertThat(page.value("$.items[0].actor")).isNull();
        assertThat(page.text("$.items[0].targetType")).isEqualTo("USER");
        assertThat(page.text("$.items[0].targetId")).isEqualTo(String.valueOf(lockedUserId));
    }

    @Test
    void filtersNarrowTheList() {
        assertThat(admin.get("/api/audit-logs?action=account_locked").texts("$.items[*].action"))
                .containsExactly("ACCOUNT_LOCKED");

        assertThat(admin.get("/api/audit-logs?targetType=CONSULTANT&targetId=" + consultantId)
                .texts("$.items[*].action"))
                .containsExactly("CONSULTANT_STATUS_CHANGED", "CONSULTANT_CREATED");

        assertThat(admin.get("/api/audit-logs?actorUserId=" + adminId).number("$.totalItems")).isEqualTo(2);
    }

    @Test
    void dateRangeFilters() {
        String hourAgo = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        String inAnHour = Instant.now().plus(1, ChronoUnit.HOURS).toString();

        assertThat(admin.get("/api/audit-logs?from=" + hourAgo + "&to=" + inAnHour).number("$.totalItems"))
                .isEqualTo(3);
        assertThat(admin.get("/api/audit-logs?from=" + inAnHour).number("$.totalItems")).isZero();
    }

    @Test
    void pagingWorks() {
        ApiClient.Response first = admin.get("/api/audit-logs?size=2");
        ApiClient.Response second = admin.get("/api/audit-logs?size=2&page=1");

        assertThat(first.number("$.totalPages")).isEqualTo(2);
        assertThat(first.texts("$.items[*].action")).containsExactly("ACCOUNT_LOCKED", "CONSULTANT_STATUS_CHANGED");
        assertThat(second.texts("$.items[*].action")).containsExactly("CONSULTANT_CREATED");
    }

    @Test
    void badFiltersAreRejected() {
        String now = Instant.now().toString();
        ApiClient.Response backwards = admin.get("/api/audit-logs?from=" + now + "&to=" + now);
        assertThat(backwards.status()).isEqualTo(400);
        assertThat(backwards.text("$.type")).isEqualTo("urn:credit-advisory:validation:invalid-filter");

        assertThat(admin.get("/api/audit-logs?from=yesterday").status()).isEqualTo(400);
    }

    @Test
    void onlyAdminsCanReadTheAuditLog() {
        createUser("manager@test.local", null, RoleName.MANAGER);

        assertThat(signIn("manager@test.local").get("/api/audit-logs").status()).isEqualTo(403);
        assertThat(browser().get("/api/audit-logs").status()).isEqualTo(401);
    }
}
