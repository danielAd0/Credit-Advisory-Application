package za.co.mafori.creditadvisory.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import za.co.mafori.creditadvisory.user.UserAccount;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

@Service
public class SignInService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final Clock clock = Clock.systemUTC();

    /**
     * Hash of a throwaway value, so unknown emails take as long to reject as wrong
     * passwords.
     */
    private final String timingHash;

    public SignInService(UserAccountRepository users, PasswordEncoder passwordEncoder, AuthProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.timingHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    @Transactional
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
            return Optional.empty();
        }

        if (!user.canSignIn(now)) {
            // Disabled, or the linked consultant is INACTIVE / SUSPENDED (FR-18).
            return Optional.empty();
        }

        user.recordSuccessfulSignIn(now);
        return Optional.of(AppUserPrincipal.from(user));
    }
}
