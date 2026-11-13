package za.co.mafori.creditadvisory.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.user.UserAccount;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

@Service
public class SignInService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final AuditLogWriter audit;
    private final Clock clock = Clock.systemUTC();

    /**
     * Hash of a throwaway value, so unknown emails take as long to reject as wrong
     * passwords.
     */
    private final String timingHash;

    public SignInService(UserAccountRepository users, PasswordEncoder passwordEncoder, AuthProperties properties,
            AuditLogWriter audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.audit = audit;
        this.timingHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    /**
     * dontRollbackOn: the expired-password refusal below is an ApiException, and
     * its
     * audit entry must still be saved.
     */
    @Transactional(dontRollbackOn = ApiException.class)
    public Optional<AppUserPrincipal> attempt(String email, String rawPassword) {
        Instant now = Instant.now(clock);

        Optional<UserAccount> found = users.findByEmailForSignIn(UserAccount.normalizeEmail(email));
        if (found.isEmpty()) {
            passwordEncoder.matches(rawPassword, timingHash);
            return Optional.empty();
        }

        UserAccount user = found.get();

        if (user.isLocked(now)) {
            // Do not check the real password while locked: the answer must look the same
            // either way.
            passwordEncoder.matches(rawPassword, timingHash);
            return Optional.empty();
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            user.recordFailedSignIn(now, properties.maxFailedAttempts(), properties.lockDuration());
            if (user.isLocked(now)) {
                // This failure locked the account. Saved in the same transaction as the lock.
                audit.record("ACCOUNT_LOCKED", "USER", user.getId(),
                        AuditLogWriter.meta("lockedUntil", user.getLockedUntil().toString()));
            }
            return Optional.empty();
        }

        if (!user.canSignIn(now)) {
            // Disabled, or the linked consultant is INACTIVE / SUSPENDED (FR-18).
            return Optional.empty();
        }

        if (user.temporaryPasswordExpired(now)) {
            // Only said once the password was right, so it tells a guesser nothing. Not
            // counted as a failed attempt: the person did know the password.
            audit.record("TEMPORARY_PASSWORD_EXPIRED", "USER", user.getId(),
                    AuditLogWriter.meta("expiredAt", user.getTemporaryPasswordExpiresAt().toString()));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "auth:temporary-password-expired",
                    "Temporary password expired",
                    "This temporary password has expired. Ask your administrator to send you new login details.");
        }

        user.recordSuccessfulSignIn(now);
        return Optional.of(AppUserPrincipal.from(user));
    }
}
