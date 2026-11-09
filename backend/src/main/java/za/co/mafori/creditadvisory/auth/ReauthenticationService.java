package za.co.mafori.creditadvisory.auth;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.time.Clock;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.user.UserAccount;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * Checks the password of someone who is ALREADY signed in, before something
 * sensitive (showing a full ID number). A stolen session alone is then not
 * enough.
 *
 * <p>
 * Wrong passwords count toward the same lockout as sign-in, so a stolen
 * session cannot be used to guess the password. The failed-attempt columns are
 * excluded from the account version, so a wrong password does not sign the
 * user's other sessions out.
 *
 * <p>
 * Returns a result instead of throwing: an exception would roll back the
 * transaction, and with it the failed-attempt count and the audit entries.
 */
@Service
public class ReauthenticationService {

    public enum Result {
        CONFIRMED, WRONG_PASSWORD, LOCKED
    }

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final AuditLogWriter audit;
    private final Clock clock = Clock.systemUTC();

    public ReauthenticationService(UserAccountRepository users, PasswordEncoder passwordEncoder,
            AuthProperties properties, AuditLogWriter audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.audit = audit;
    }

    @Transactional
    public Result confirm(long userId, String rawPassword) {
        Instant now = Instant.now(clock);
        UserAccount user = users.findById(userId).orElseThrow();

        if (user.isLocked(now)) {
            return Result.LOCKED;
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            user.recordFailedSignIn(now, properties.maxFailedAttempts(), properties.lockDuration());
            boolean lockedNow = user.isLocked(now);
            audit.record("PASSWORD_CONFIRMATION_FAILED", "USER", userId, meta("locked", lockedNow));
            if (lockedNow) {
                audit.record("ACCOUNT_LOCKED", "USER", userId,
                        meta("lockedUntil", user.getLockedUntil().toString()));
                return Result.LOCKED;
            }
            return Result.WRONG_PASSWORD;
        }

        // Right password: start counting failures from zero again, like a sign-in.
        user.unlock();
        return Result.CONFIRMED;
    }
}
