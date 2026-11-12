package za.co.mafori.creditadvisory.auth.mfa;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.auth.AuthProperties;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.user.UserAccount;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * Two-step sign-in. After the password, a session is "pending" until the user
 * either sets up an authenticator (first time) or enters its current code (or
 * a recovery code).
 *
 * <p>
 * Wrong codes count toward the same lockout as wrong passwords, so the second
 * step cannot be guessed (1 in a million per try, 5 tries). A code is never
 * accepted twice, so one seen over someone's shoulder is useless.
 *
 * <p>
 * Methods that may fail return a result instead of throwing: an exception
 * would roll back the transaction, and with it the failed-attempt count and
 * the audit entries.
 */
@Service
public class MfaService {

    /** What the session still has to do. Shown as "mfa" in GET /api/auth/me. */
    public enum Status {
        /** app.mfa.required=false (development and most tests only). */
        NOT_REQUIRED,
        /** Signed in with password; no authenticator set up yet. */
        SETUP_REQUIRED,
        /** Signed in with password; the authenticator code is still needed. */
        CODE_REQUIRED,
        /** Both steps done. */
        VERIFIED
    }

    public enum Result {
        OK, WRONG_CODE, LOCKED, NOT_SET_UP, ALREADY_SET_UP
    }

    /** A new secret for the authenticator app, before it is confirmed. */
    public record Setup(byte[] secret, String secretBase32, String otpauthUri) {
    }

    /** Answer of enable(): the recovery codes, only when result is OK. */
    public record Enabled(Result result, List<String> recoveryCodes) {
    }

    private static final int SECRET_BYTES = 20; // 160 bits, as RFC 4226 recommends

    private final MfaStore store;
    private final MfaSecretCipher cipher;
    private final MfaProperties properties;
    private final AuthProperties authProperties;
    private final UserAccountRepository users;
    private final AuditLogWriter audit;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    public MfaService(MfaStore store, MfaSecretCipher cipher, MfaProperties properties,
            AuthProperties authProperties, UserAccountRepository users, AuditLogWriter audit) {
        this.store = store;
        this.cipher = cipher;
        this.properties = properties;
        this.authProperties = authProperties;
        this.users = users;
        this.audit = audit;
    }

    public boolean required() {
        return properties.required();
    }

    @Transactional(readOnly = true)
    public Status statusOf(AppUserPrincipal principal) {
        if (!principal.mfaPending()) {
            return properties.required() ? Status.VERIFIED : Status.NOT_REQUIRED;
        }
        return store.isEnrolled(principal.userId()) ? Status.CODE_REQUIRED : Status.SETUP_REQUIRED;
    }

    // ---------- first time: set up ----------

    /**
     * A fresh secret. Not stored until enable() confirms the app shows the right
     * code.
     */
    @Transactional(readOnly = true)
    public Setup startSetup(AppUserPrincipal principal) {
        if (store.isEnrolled(principal.userId())) {
            throw ApiException.conflict("auth:mfa-already-set-up", "Already set up",
                    "An authenticator is already set up for this account. Enter its code instead.");
        }
        return newSecret(principal.email());
    }

    /**
     * Saves the secret if the code proves the app was set up correctly, and
     * creates the recovery codes. A wrong code here does not count toward the
     * lockout: the secret is not in use yet.
     */
    @Transactional
    public Enabled enable(long userId, byte[] pendingSecret, String code) {
        if (store.isEnrolled(userId)) {
            return new Enabled(Result.ALREADY_SET_UP, List.of());
        }
        OptionalLong step = Totp.matchingStep(pendingSecret, normalise(code), Instant.now(clock));
        if (step.isEmpty()) {
            return new Enabled(Result.WRONG_CODE, List.of());
        }
        List<String> recoveryCodes = RecoveryCodes.generate();
        store.enrol(userId, cipher.encrypt(pendingSecret), step.getAsLong(),
                recoveryCodes.stream().map(RecoveryCodes::hash).toList());
        audit.record("MFA_ENABLED", "USER", userId, meta("recoveryCodes", recoveryCodes.size()));
        return new Enabled(Result.OK, recoveryCodes);
    }

    // ---------- moving to a new phone ----------

    /**
     * A signed-in user with a new phone: a fresh secret for it. The old
     * authenticator keeps working until confirmChange() succeeds.
     */
    @Transactional(readOnly = true)
    public Setup startChange(AppUserPrincipal principal) {
        if (!store.isEnrolled(principal.userId())) {
            throw ApiException.conflict("auth:mfa-setup-required", "Set-up required",
                    "No authenticator is set up for this account yet, so there is nothing to move.");
        }
        return newSecret(principal.email());
    }

    /**
     * Replaces the authenticator with the new one if the code proves the new
     * phone was set up correctly. The old recovery codes stop working and 10 new
     * ones are made, and every session of the user is signed out (the caller
     * keeps its own). A wrong code does not count toward the lockout, as in
     * enable(): the session already passed both steps.
     */
    @Transactional
    public Enabled confirmChange(long userId, byte[] newSecret, String code) {
        if (!store.isEnrolled(userId)) {
            return new Enabled(Result.NOT_SET_UP, List.of());
        }
        OptionalLong step = Totp.matchingStep(newSecret, normalise(code), Instant.now(clock));
        if (step.isEmpty()) {
            return new Enabled(Result.WRONG_CODE, List.of());
        }
        List<String> recoveryCodes = RecoveryCodes.generate();
        store.remove(userId); // the old recovery codes go with it
        store.enrol(userId, cipher.encrypt(newSecret), step.getAsLong(),
                recoveryCodes.stream().map(RecoveryCodes::hash).toList());
        store.signOutEverywhere(userId);
        audit.record("MFA_CHANGED", "USER", userId, meta("recoveryCodes", recoveryCodes.size()));
        return new Enabled(Result.OK, recoveryCodes);
    }

    // ---------- every sign-in: the code ----------

    @Transactional
    public Result verify(long userId, String code) {
        UserAccount user = users.findById(userId).orElseThrow();
        Instant now = Instant.now(clock);
        if (user.isLocked(now)) {
            return Result.LOCKED;
        }
        var stored = store.findSecret(userId);
        if (stored.isEmpty()) {
            return Result.NOT_SET_UP;
        }
        OptionalLong step = Totp.matchingStep(cipher.decrypt(stored.get()), normalise(code), now);
        // A correct code that was already used counts as wrong.
        if (step.isPresent() && store.markStepUsed(userId, step.getAsLong())) {
            user.unlock(); // start counting failures from zero again
            return Result.OK;
        }
        return failure(user, now, "MFA_CODE_FAILED");
    }

    /** Lost phone: one of the recovery codes, each usable once. */
    @Transactional
    public Result recover(long userId, String recoveryCode) {
        UserAccount user = users.findById(userId).orElseThrow();
        Instant now = Instant.now(clock);
        if (user.isLocked(now)) {
            return Result.LOCKED;
        }
        if (!store.isEnrolled(userId)) {
            return Result.NOT_SET_UP;
        }
        if (recoveryCode != null && store.useRecoveryCode(userId, RecoveryCodes.hash(recoveryCode))) {
            user.unlock();
            audit.record("MFA_RECOVERY_CODE_USED", "USER", userId,
                    meta("remaining", store.unusedRecoveryCodes(userId)));
            return Result.OK;
        }
        return failure(user, now, "MFA_RECOVERY_CODE_FAILED");
    }

    // ---------- admin ----------

    /**
     * Removes a user's authenticator (lost phone, no recovery codes left). They
     * set up a new one at their next sign-in, and every session of theirs is
     * signed out now.
     */
    @Transactional
    public void reset(long userId) {
        if (!users.existsById(userId)) {
            throw ApiException.notFound("user:not-found", "User " + userId + " does not exist.");
        }
        boolean hadMfa = store.remove(userId);
        store.signOutEverywhere(userId);
        audit.record("MFA_RESET", "USER", userId, meta("hadMfa", hadMfa));
    }

    // ---------- rules ----------

    private Result failure(UserAccount user, Instant now, String action) {
        user.recordFailedSignIn(now, authProperties.maxFailedAttempts(), authProperties.lockDuration());
        boolean lockedNow = user.isLocked(now);
        audit.record(action, "USER", user.getId(), meta("locked", lockedNow));
        if (lockedNow) {
            audit.record("ACCOUNT_LOCKED", "USER", user.getId(),
                    meta("lockedUntil", user.getLockedUntil().toString()));
            return Result.LOCKED;
        }
        return Result.WRONG_CODE;
    }

    private Setup newSecret(String email) {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        String base32 = Base32.encode(secret);
        return new Setup(secret, base32, otpauthUri(email, base32));
    }

    /** "123 456" -> "123456". */
    private static String normalise(String code) {
        return code == null ? null : code.replace(" ", "").strip();
    }

    /**
     * What the QR code holds:
     * otpauth://totp/Credit%20Advisory:user@x?secret=...&issuer=...
     */
    private String otpauthUri(String email, String secretBase32) {
        String issuer = encode(properties.issuer());
        return "otpauth://totp/" + issuer + ":" + encode(email)
                + "?secret=" + secretBase32
                + "&issuer=" + issuer
                + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.STEP_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
