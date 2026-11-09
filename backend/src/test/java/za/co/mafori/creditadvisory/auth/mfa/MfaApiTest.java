package za.co.mafori.creditadvisory.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * Two-step sign-in, switched on (the other tests run with
 * app.mfa.required=false
 * so they do not need codes). The codes are worked out here exactly like an
 * authenticator app does, from the secret the setup returns.
 */
@TestPropertySource(properties = "app.mfa.required=true")
class MfaApiTest extends ApiTestBase {

    private static final String THANDI = "thandi@test.local";

    /** The last step used per user: the server never accepts a step twice. */
    private final Map<String, Long> lastStep = new HashMap<>();

    @BeforeEach
    void createData() {
        long thandiId = createConsultant("C001", "Thandi Test");
        createUser(THANDI, thandiId, RoleName.CONSULTANT);
        createClient("Test", "Alpha", thandiId);
    }

    // ---------- helpers ----------

    /** The code an authenticator app would show, never one already used. */
    private String nextCode(String email, byte[] secret) {
        long now = Totp.stepAt(Instant.now());
        long step = Math.max(lastStep.getOrDefault(email, Long.MIN_VALUE) + 1, now);
        if (step > now + 1) {
            throw new IllegalStateException("This test uses more codes than the clock allows");
        }
        lastStep.put(email, step);
        return Totp.code(secret, step);
    }

    /** A code that is valid in form but not now: 5 steps (2.5 minutes) ahead. */
    private static String wrongCode(byte[] secret) {
        return Totp.code(secret, Totp.stepAt(Instant.now()) + 5);
    }

    private static ApiClient.Response send(ApiClient as, String path, String code) {
        return as.post(path, """
                {"code":"%s"}""".formatted(code));
    }

    private static ApiClient.Response setup(ApiClient as) {
        return as.post("/api/auth/mfa/setup", "{}");
    }

    /**
     * Sets up MFA in this signed-in browser. Returns the secret and the recovery
     * codes.
     */
    private record Enrolment(byte[] secret, List<String> recoveryCodes) {
    }

    private Enrolment enrol(ApiClient as, String email) {
        ApiClient.Response started = setup(as);
        assertThat(started.status()).as(started.body()).isEqualTo(200);
        byte[] secret = Base32.decode(started.text("$.secret"));
        ApiClient.Response enabled = send(as, "/api/auth/mfa/enable", nextCode(email, secret));
        assertThat(enabled.status()).as(enabled.body()).isEqualTo(200);
        return new Enrolment(secret, enabled.texts("$.recoveryCodes"));
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ?", Long.class, action);
    }

    // ---------- before the second step ----------

    @Test
    void afterThePasswordOnlyTheSecondStepIsOpen() {
        ApiClient browser = browser();
        ApiClient.Response login = browser.signIn(THANDI, PASSWORD);

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.text("$.mfa")).isEqualTo("SETUP_REQUIRED");
        assertThat(browser.get("/api/auth/me").text("$.mfa")).isEqualTo("SETUP_REQUIRED");

        ApiClient.Response blocked = browser.get("/api/clients");
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.text("$.type")).isEqualTo("urn:credit-advisory:auth:mfa-required");
        assertThat(browser.post("/api/auth/reauthenticate", """
                {"password":"%s"}""".formatted(PASSWORD)).status()).isEqualTo(403);
    }

    @Test
    void enableWithoutSetupIsRefused() {
        ApiClient thandi = signIn(THANDI);

        ApiClient.Response response = send(thandi, "/api/auth/mfa/enable", "123456");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:auth:mfa-setup-not-started");
    }

    // ---------- first time ----------

    @Test
    void settingUpTheAppOpensEverythingAndGivesTenRecoveryCodes() {
        ApiClient thandi = signIn(THANDI);

        ApiClient.Response started = setup(thandi);
        assertThat(started.status()).as(started.body()).isEqualTo(200);
        String secret = started.text("$.secret");
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(started.text("$.otpauthUri"))
                .startsWith("otpauth://totp/Credit%20Advisory:thandi%40test.local?secret=" + secret)
                .contains("&issuer=Credit%20Advisory", "&digits=6", "&period=30");

        byte[] secretBytes = Base32.decode(secret);
        ApiClient.Response wrong = send(thandi, "/api/auth/mfa/enable", wrongCode(secretBytes));
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.text("$.type")).isEqualTo("urn:credit-advisory:auth:wrong-code");

        ApiClient.Response enabled = send(thandi, "/api/auth/mfa/enable", nextCode(THANDI, secretBytes));
        assertThat(enabled.status()).as(enabled.body()).isEqualTo(200);
        assertThat(enabled.size("$.recoveryCodes")).isEqualTo(10);
        assertThat(enabled.text("$.account.mfa")).isEqualTo("VERIFIED");

        assertThat(thandi.get("/api/clients").status()).isEqualTo(200);
        assertThat(thandi.get("/api/auth/me").text("$.mfa")).isEqualTo("VERIFIED");
        assertThat(auditCount("MFA_ENABLED")).isEqualTo(1);

        // Stored encrypted; recovery codes only as hashes.
        assertThat(jdbc.queryForObject("select octet_length(secret_encrypted) from user_mfa", Integer.class))
                .isEqualTo(49);
        List<String> hashes = jdbc.queryForList("select code_hash from user_mfa_recovery_codes", String.class);
        assertThat(hashes).hasSize(10).noneMatch(h -> enabled.texts("$.recoveryCodes").contains(h));
    }

    // ---------- every sign-in ----------

    @Test
    void theNextSignInNeedsTheCurrentCode() {
        Enrolment enrolment = enrol(signIn(THANDI), THANDI);
        ApiClient laptop = browser();

        ApiClient.Response login = laptop.signIn(THANDI, PASSWORD);
        assertThat(login.text("$.mfa")).isEqualTo("CODE_REQUIRED");
        assertThat(laptop.get("/api/clients").status()).isEqualTo(403);
        assertThat(setup(laptop).status()).isEqualTo(409); // no second authenticator

        assertThat(send(laptop, "/api/auth/mfa/verify", wrongCode(enrolment.secret())).status()).isEqualTo(400);

        ApiClient.Response verified = send(laptop, "/api/auth/mfa/verify", nextCode(THANDI, enrolment.secret()));
        assertThat(verified.status()).as(verified.body()).isEqualTo(200);
        assertThat(verified.text("$.mfa")).isEqualTo("VERIFIED");
        assertThat(laptop.get("/api/clients").status()).isEqualTo(200);

        // Done once per session.
        ApiClient.Response again = send(laptop, "/api/auth/mfa/verify", "123456");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.text("$.type")).isEqualTo("urn:credit-advisory:auth:mfa-not-pending");
    }

    @Test
    void aCodeIsAcceptedOnlyOnce() {
        Enrolment enrolment = enrol(signIn(THANDI), THANDI);
        String code = nextCode(THANDI, enrolment.secret());

        ApiClient first = signIn(THANDI);
        assertThat(send(first, "/api/auth/mfa/verify", code).status()).isEqualTo(200);

        ApiClient second = signIn(THANDI);
        ApiClient.Response reused = send(second, "/api/auth/mfa/verify", code);
        assertThat(reused.status()).isEqualTo(400);
        assertThat(second.get("/api/clients").status()).isEqualTo(403);
    }

    @Test
    void fiveWrongCodesLockTheAccountAndSignThisSessionOut() {
        Enrolment enrolment = enrol(signIn(THANDI), THANDI);
        ApiClient laptop = signIn(THANDI);

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(send(laptop, "/api/auth/mfa/verify", wrongCode(enrolment.secret())).status())
                    .isEqualTo(400);
        }
        ApiClient.Response fifth = send(laptop, "/api/auth/mfa/verify", wrongCode(enrolment.secret()));

        assertThat(fifth.status()).isEqualTo(401);
        assertThat(fifth.text("$.type")).isEqualTo("urn:credit-advisory:auth:account-locked");
        assertThat(laptop.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(auditCount("MFA_CODE_FAILED")).isEqualTo(5);
        assertThat(auditCount("ACCOUNT_LOCKED")).isEqualTo(1);
    }

    // ---------- lost phone ----------

    @Test
    void aRecoveryCodeWorksOnceInsteadOfTheAppCode() {
        Enrolment enrolment = enrol(signIn(THANDI), THANDI);
        String recoveryCode = enrolment.recoveryCodes().get(0);

        ApiClient laptop = signIn(THANDI);
        ApiClient.Response used = laptop.post("/api/auth/mfa/recovery", """
                {"recoveryCode":"%s"}""".formatted(recoveryCode.toLowerCase()));
        assertThat(used.status()).as(used.body()).isEqualTo(200);
        assertThat(used.text("$.mfa")).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("""
                select metadata ->> 'remaining' from audit_logs where action = 'MFA_RECOVERY_CODE_USED'
                """, String.class)).isEqualTo("9");

        ApiClient phone = signIn(THANDI);
        ApiClient.Response reused = phone.post("/api/auth/mfa/recovery", """
                {"recoveryCode":"%s"}""".formatted(recoveryCode));
        assertThat(reused.status()).isEqualTo(400);
    }

    @Test
    void anAdminResetsALostAuthenticatorAndTheUserSetsUpANewOne() {
        ApiClient admin = signIn(ADMIN_EMAIL);
        enrol(admin, ADMIN_EMAIL);
        ApiClient thandi = signIn(THANDI);
        enrol(thandi, THANDI);
        long thandiUserId = jdbc.queryForObject("select id from users where email = ?", Long.class, THANDI);

        ApiClient.Response reset = admin.post("/api/users/" + thandiUserId + "/mfa-reset", "{}");

        assertThat(reset.status()).as(reset.body()).isEqualTo(204);
        assertThat(thandi.get("/api/auth/me").status()).isEqualTo(401); // signed out everywhere
        assertThat(browser().signIn(THANDI, PASSWORD).text("$.mfa")).isEqualTo("SETUP_REQUIRED");
        assertThat(auditCount("MFA_RESET")).isEqualTo(1);
        assertThat(admin.post("/api/users/999999/mfa-reset", "{}").status()).isEqualTo(404);
    }

    @Test
    void onlyAnAdminCanResetMfa() {
        ApiClient thandi = signIn(THANDI);
        enrol(thandi, THANDI);

        assertThat(thandi.post("/api/users/" + adminId + "/mfa-reset", "{}").status()).isEqualTo(403);
    }

    // ---------- with an admin-set password ----------

    @Test
    void mfaComesFirstThenTheNewPassword() {
        jdbc.update("update users set must_change_password = true where email = ?", THANDI);
        ApiClient thandi = signIn(THANDI);

        assertThat(thandi.get("/api/clients").text("$.type")).isEqualTo("urn:credit-advisory:auth:mfa-required");

        enrol(thandi, THANDI);
        assertThat(thandi.get("/api/clients").text("$.type"))
                .isEqualTo("urn:credit-advisory:auth:password-change-required");

        ApiClient.Response changed = thandi.post("/api/auth/change-password", """
                {"currentPassword":"%s","newPassword":"A-Brand-New-Password-2026"}""".formatted(PASSWORD));
        assertThat(changed.status()).as(changed.body()).isEqualTo(204);
        assertThat(thandi.get("/api/clients").status()).isEqualTo(200);
    }
}
