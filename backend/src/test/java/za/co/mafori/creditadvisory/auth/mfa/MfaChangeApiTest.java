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
 * Moving two-step sign-in to a new phone: POST /api/auth/mfa/change and
 * /change/confirm. Codes are worked out here like an authenticator app does.
 * The server takes each 30-second code once, so each test uses at most two
 * app codes; other sessions sign in with recovery codes instead.
 */
@TestPropertySource(properties = "app.mfa.required=true")
class MfaChangeApiTest extends ApiTestBase {

    private static final String THANDI = "thandi@test.local";

    /** The last step used: the server never accepts a step twice for a user. */
    private final Map<String, Long> lastStep = new HashMap<>();

    @BeforeEach
    void createData() {
        long thandiId = createConsultant("C001", "Thandi Test");
        createUser(THANDI, thandiId, RoleName.CONSULTANT);
    }

    // ---------- helpers ----------

    private String nextCode(byte[] secret) {
        long now = Totp.stepAt(Instant.now());
        long step = Math.max(lastStep.getOrDefault(THANDI, Long.MIN_VALUE) + 1, now);
        if (step > now + 1) {
            throw new IllegalStateException("This test uses more codes than the clock allows");
        }
        lastStep.put(THANDI, step);
        return Totp.code(secret, step);
    }

    private static ApiClient.Response send(ApiClient as, String path, String code) {
        return as.post(path, """
                {"code":"%s"}""".formatted(code));
    }

    private record Enrolment(byte[] secret, List<String> recoveryCodes) {
    }

    /**
     * Sets up the first phone in this signed-in browser, which is then fully signed
     * in.
     */
    private Enrolment enrol(ApiClient as) {
        ApiClient.Response started = as.post("/api/auth/mfa/setup", "{}");
        assertThat(started.status()).as(started.body()).isEqualTo(200);
        byte[] secret = Base32.decode(started.text("$.secret"));
        ApiClient.Response enabled = send(as, "/api/auth/mfa/enable", nextCode(secret));
        assertThat(enabled.status()).as(enabled.body()).isEqualTo(200);
        return new Enrolment(secret, enabled.texts("$.recoveryCodes"));
    }

    /** A new browser, past both steps with a recovery code. */
    private ApiClient signInWithRecoveryCode(String recoveryCode) {
        ApiClient browser = signIn(THANDI);
        ApiClient.Response used = browser.post("/api/auth/mfa/recovery", """
                {"recoveryCode":"%s"}""".formatted(recoveryCode));
        assertThat(used.status()).as(used.body()).isEqualTo(200);
        return browser;
    }

    private byte[] storedSecret() {
        return jdbc.queryForObject("""
                select m.secret_encrypted from user_mfa m join users u on u.id = m.user_id where u.email = ?
                """, byte[].class, THANDI);
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ?", Long.class, action);
    }

    // ---------- tests ----------

    @Test
    void aUserMovesToANewPhoneAndKeepsThisSession() {
        ApiClient phone = signIn(THANDI);
        Enrolment old = enrol(phone);
        ApiClient laptop = signInWithRecoveryCode(old.recoveryCodes().get(0));
        byte[] oldStored = storedSecret();
        confirmPassword(phone);

        ApiClient.Response started = phone.post("/api/auth/mfa/change", "{}");
        assertThat(started.status()).as(started.body()).isEqualTo(200);
        assertThat(started.text("$.otpauthUri")).startsWith("otpauth://totp/");
        byte[] newSecret = Base32.decode(started.text("$.secret"));

        ApiClient.Response confirmed = send(phone, "/api/auth/mfa/change/confirm", nextCode(newSecret));

        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        List<String> newCodes = confirmed.texts("$.recoveryCodes");
        assertThat(newCodes).hasSize(10).doesNotContainAnyElementsOf(old.recoveryCodes());
        assertThat(confirmed.text("$.account.mfa")).isEqualTo("VERIFIED");
        assertThat(phone.get("/api/clients").status()).as("this session stays signed in").isEqualTo(200);
        assertThat(laptop.get("/api/auth/me").status()).as("every other session is signed out").isEqualTo(401);
        assertThat(storedSecret()).isNotEqualTo(oldStored);
        assertThat(auditCount("MFA_CHANGED")).isEqualTo(1);

        ApiClient.Response oldCode = signIn(THANDI).post("/api/auth/mfa/recovery", """
                {"recoveryCode":"%s"}""".formatted(old.recoveryCodes().get(1)));
        assertThat(oldCode.status()).as("the old recovery codes are gone").isEqualTo(400);
        signInWithRecoveryCode(newCodes.get(0));
    }

    @Test
    void thePasswordMustBeEnteredAgainFirst() {
        ApiClient phone = signIn(THANDI);
        enrol(phone);

        ApiClient.Response refused = phone.post("/api/auth/mfa/change", "{}");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.text("$.type")).isEqualTo("urn:credit-advisory:auth:reauthentication-required");
        assertThat(refused.text("$.detail")).contains("new phone");
    }

    @Test
    void aWrongCodeChangesNothing() {
        ApiClient phone = signIn(THANDI);
        Enrolment old = enrol(phone);
        byte[] oldStored = storedSecret();

        ApiClient.Response notStarted = send(phone, "/api/auth/mfa/change/confirm", "123456");
        assertThat(notStarted.status()).isEqualTo(409);
        assertThat(notStarted.text("$.type")).isEqualTo("urn:credit-advisory:auth:mfa-change-not-started");

        confirmPassword(phone);
        byte[] newSecret = Base32.decode(phone.post("/api/auth/mfa/change", "{}").text("$.secret"));
        String wrong = Totp.code(newSecret, Totp.stepAt(Instant.now()) + 5);
        ApiClient.Response wrongCode = send(phone, "/api/auth/mfa/change/confirm", wrong);

        assertThat(wrongCode.status()).isEqualTo(400);
        assertThat(wrongCode.text("$.type")).isEqualTo("urn:credit-advisory:auth:wrong-code");
        assertThat(storedSecret()).isEqualTo(oldStored);
        assertThat(auditCount("MFA_CHANGED")).isZero();
        assertThat(auditCount("MFA_CODE_FAILED")).as("does not count toward the lockout").isZero();

        ApiClient laptop = signIn(THANDI);
        ApiClient.Response oldPhone = send(laptop, "/api/auth/mfa/verify", nextCode(old.secret()));
        assertThat(oldPhone.status()).as("the old phone still works").isEqualTo(200);
    }

    @Test
    void notBeforeBothSignInStepsAreDone() {
        ApiClient phone = signIn(THANDI);
        enrol(phone);
        ApiClient halfway = signIn(THANDI); // password only

        ApiClient.Response refused = halfway.post("/api/auth/mfa/change", "{}");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.text("$.type")).isEqualTo("urn:credit-advisory:auth:mfa-required");
    }
}
