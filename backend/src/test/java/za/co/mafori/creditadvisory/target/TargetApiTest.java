package za.co.mafori.creditadvisory.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/** /api/targets - read and set by MANAGER or ADMIN only. */
class TargetApiTest extends ApiTestBase {

    private ApiClient manager;
    private ApiClient consultant;

    @BeforeEach
    void signInUsers() {
        long consultantId = createConsultant("C001", "Thandi Test");
        createUser("consultant@test.local", consultantId, RoleName.CONSULTANT);
        consultant = signIn("consultant@test.local");
        createUser("manager@test.local", null, RoleName.MANAGER);
        manager = signIn("manager@test.local");
    }

    private static String body(LocalDate from, String daily, String weekly) {
        return """
                {"effectiveFrom":"%s","dailyTarget":%s,"weeklyTarget":%s}""".formatted(from, daily, weekly);
    }

    private static LocalDate today() {
        return LocalDate.now(TargetService.BUSINESS_ZONE);
    }

    @Test
    void aFreshDatabaseHasNoTargets() {
        ApiClient.Response response = manager.get("/api/targets");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.value("$.current")).isNull();
        assertThat(response.size("$.history")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sales_targets", Long.class)).isZero();
    }

    @Test
    void managerSetsANewTargetFromToday() {
        ApiClient.Response set = manager.post("/api/targets", body(today(), "150000", "750000"));

        assertThat(set.status()).as(set.body()).isEqualTo(200);
        assertThat(set.text("$.setByEmail")).isEqualTo("manager@test.local");

        ApiClient.Response list = manager.get("/api/targets");
        assertThat(list.number("$.current.dailyTarget")).isEqualTo(150_000);
        assertThat(list.number("$.current.weeklyTarget")).isEqualTo(750_000);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'SALES_TARGET_SET'", Long.class)).isEqualTo(1);
    }

    @Test
    void settingTheSameDateTwiceReplacesIt() {
        LocalDate next = today().plusDays(7);
        manager.post("/api/targets", body(next, "130000", "650000"));
        ApiClient.Response again = manager.post("/api/targets", body(next, "140000", "700000"));

        assertThat(again.status()).as(again.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from sales_targets where effective_from = ?",
                Long.class, next)).isEqualTo(1);

        ApiClient.Response list = manager.get("/api/targets");
        assertThat(list.number("$.history[0].dailyTarget")).isEqualTo(140_000);
        // A future target does not apply yet: today still has none.
        assertThat(list.value("$.current")).isNull();
    }

    @Test
    void aPastDateCanBeGivenATargetWhileNoneAppliesToIt() {
        // After importing old registers, a manager can fill in the weeks that
        // show "Target not configured".
        ApiClient.Response first = manager.post("/api/targets", body(today().minusDays(60), "100000", "500000"));
        assertThat(first.status()).as(first.body()).isEqualTo(200);

        // Still allowed: earlier than every saved target, so it changes no week that
        // had one.
        ApiClient.Response earlier = manager.post("/api/targets", body(today().minusDays(90), "90000", "450000"));
        assertThat(earlier.status()).as(earlier.body()).isEqualTo(200);

        ApiClient.Response list = manager.get("/api/targets");
        assertThat(list.number("$.current.dailyTarget")).isEqualTo(100_000);
        assertThat(list.size("$.history")).isEqualTo(2);
    }

    @Test
    void aPastDateThatAlreadyHasATargetIsRefused() {
        manager.post("/api/targets", body(today().minusDays(60), "100000", "500000"));

        ApiClient.Response change = manager.post("/api/targets", body(today().minusDays(30), "150000", "750000"));

        assertThat(change.status()).isEqualTo(422);
        assertThat(change.body()).contains("target:past-date");
        assertThat(jdbc.queryForObject("select count(*) from sales_targets", Long.class)).isEqualTo(1);
    }

    @Test
    void badAmountsAreRefused() {
        assertThat(manager.post("/api/targets", body(today(), "0", "750000")).status()).isEqualTo(400);
        assertThat(manager.post("/api/targets", """
                {"effectiveFrom":"%s","dailyTarget":150000}""".formatted(today())).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from sales_targets", Long.class)).isZero();
    }

    @Test
    void consultantsCannotSeeOrSetTargets() {
        assertThat(consultant.get("/api/targets").status()).isEqualTo(403);
        assertThat(consultant.post("/api/targets", body(today(), "150000", "750000")).status()).isEqualTo(403);
    }
}
