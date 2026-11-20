package za.co.mafori.creditadvisory.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/**
 * Logins nobody has used for longer than app.auth.dormant-after are disabled
 * (DormantLoginService). Runs with 90 days.
 */
@TestPropertySource(properties = "app.auth.dormant-after=90d")
class DormantLoginApiTest extends ApiTestBase {

    @Autowired
    private DormantLoginService dormant;

    @Autowired
    private UserAccountRepository users;

    private final Instant now = Instant.now();

    @BeforeEach
    void signInOnce() {
        // The first administrator from ApiTestBase signed in just now.
        lastSignIn(adminId, now);
    }

    private void lastSignIn(long userId, Instant at) {
        jdbc.update("update users set last_login_at = ? where id = ?", at == null ? null : Timestamp.from(at),
                userId);
    }

    private boolean enabled(long userId) {
        return jdbc.queryForObject("select enabled from users where id = ?", Boolean.class, userId);
    }

    private long audited() {
        return jdbc.queryForObject("select count(*) from audit_logs where action = 'USER_DISABLED_DORMANT'",
                Long.class);
    }

    @Test
    void aLoginUnusedForTooLongIsDisabledAndAudited() {
        long gone = createUser("gone@test.local", null, RoleName.MANAGER);
        long recent = createUser("recent@test.local", null, RoleName.MANAGER);
        lastSignIn(gone, now.minus(Duration.ofDays(91)));
        lastSignIn(recent, now.minus(Duration.ofDays(89)));

        assertThat(dormant.disableDormant(now)).isEqualTo(1);

        assertThat(enabled(gone)).isFalse();
        assertThat(enabled(recent)).isTrue();
        assertThat(audited()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select target_id from audit_logs where action = 'USER_DISABLED_DORMANT'",
                String.class)).isEqualTo(String.valueOf(gone));
        assertThat(jdbc.queryForObject("select actor_user_id from audit_logs where action = 'USER_DISABLED_DORMANT'",
                Long.class)).as("done by the system").isNull();
        assertThat(jdbc.queryForObject("select metadata ->> 'dormantAfterDays' from audit_logs"
                + " where action = 'USER_DISABLED_DORMANT'", String.class)).isEqualTo("90");
        assertThat(users.findById(gone).orElseThrow().getVersion()).as("an open edit gets a stale-version")
                .isPositive();
    }

    @Test
    void aLoginThatNeverSignedInIsLeftToItsTemporaryPassword() {
        long neverSignedIn = createUser("new@test.local", null, RoleName.MANAGER);
        lastSignIn(neverSignedIn, null);
        jdbc.update("update users set created_at = ? where id = ?", Timestamp.from(now.minus(Duration.ofDays(400))),
                neverSignedIn);

        assertThat(dormant.disableDormant(now)).isZero();
        assertThat(enabled(neverSignedIn)).isTrue();
    }

    @Test
    void theLastActiveAdministratorIsNeverDisabled() {
        lastSignIn(adminId, now.minus(Duration.ofDays(200)));

        assertThat(dormant.disableDormant(now)).isZero();
        assertThat(enabled(adminId)).isTrue();

        long secondAdmin = createUser("admin2@test.local", null, RoleName.ADMIN);
        lastSignIn(secondAdmin, now);
        assertThat(dormant.disableDormant(now)).as("now there is another administrator").isEqualTo(1);
        assertThat(enabled(adminId)).isFalse();
        assertThat(enabled(secondAdmin)).isTrue();
    }

    @Test
    void alreadyDisabledLoginsAreLeftAlone() {
        long disabled = createUser("off@test.local", null, RoleName.MANAGER);
        lastSignIn(disabled, now.minus(Duration.ofDays(300)));
        jdbc.update("update users set enabled = false where id = ?", disabled);

        assertThat(dormant.disableDormant(now)).isZero();
        assertThat(audited()).isZero();
    }

    @Test
    void offWhenNoPeriodIsSet() {
        DormantLoginService off = new DormantLoginService(jdbc, users, null, Duration.ZERO);
        long gone = createUser("gone@test.local", null, RoleName.MANAGER);
        lastSignIn(gone, now.minus(Duration.ofDays(1000)));

        assertThat(off.isOn()).isFalse();
        assertThat(off.disableDormant(now)).isZero();
        assertThat(enabled(gone)).isTrue();
    }
}
