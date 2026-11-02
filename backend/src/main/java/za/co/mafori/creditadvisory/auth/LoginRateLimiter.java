package za.co.mafori.creditadvisory.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Limits FAILED sign-ins per network address. The per-account lockout stops
 * someone guessing one account's password; this stops one address trying a
 * common password against many accounts (password spraying), and limits how
 * many
 * real users one address can lock out.
 *
 * <p>
 * Only failures count, so many staff signing in from one office address are
 * never blocked. A success does not reset the count, so an attacker cannot mix
 * in
 * sign-ins to their own account to keep going.
 *
 * <p>
 * Kept in memory: it resets when the backend restarts, and each backend
 * instance
 * counts separately. That is fine for a single server.
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    /** Above this many tracked addresses, expired ones are cleaned out. */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Window(Instant start, int failures) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration window;
    private final Clock clock = Clock.systemUTC();

    public LoginRateLimiter(AuthProperties properties) {
        this.maxFailures = properties.maxFailedSignInsPerIp();
        this.window = properties.ipWindow();
    }

    /** Call BEFORE checking the password. Throws if this address must wait. */
    public void checkAllowed(String address) {
        Instant now = Instant.now(clock);
        Window current = windows.get(address);
        if (current != null && !isExpired(current, now) && current.failures() >= maxFailures) {
            long secondsLeft = Duration.between(now, current.start().plus(window)).toSeconds();
            throw new TooManySignInAttemptsException(Math.max(1, secondsLeft));
        }
    }

    /** Call after a failed sign-in from this address. */
    public void recordFailure(String address) {
        Instant now = Instant.now(clock);
        Window updated = windows.compute(address, (key, current) -> current == null || isExpired(current, now)
                ? new Window(now, 1)
                : new Window(current.start(), current.failures() + 1));

        if (updated.failures() == maxFailures) {
            // The address only, never the email that was tried.
            log.warn("Sign-in blocked for {} after {} failed attempts in {}", address, maxFailures, window);
        }
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> isExpired(w, now));
        }
    }

    /** Forgets every address. Used by the automated tests between tests. */
    public void clear() {
        windows.clear();
    }

    private boolean isExpired(Window w, Instant now) {
        return !now.isBefore(w.start().plus(window));
    }
}
