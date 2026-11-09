package za.co.mafori.creditadvisory.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.auth.AuthProperties;

/**
 * Caps how many full ID numbers one user can see per hour
 * (app.auth.max-id-reveals-per-hour, default 20). Ordinary work stays well
 * below it; a stolen session or an insider copying ID numbers hits it fast.
 *
 * <p>
 * Every request for a full ID number counts, whether the client exists or not,
 * so the limit also slows down guessing. The first refused request of an hour
 * writes ID_REVEAL_LIMIT_REACHED to the audit log and a WARN line, so an admin
 * can find it (GET /api/audit-logs?action=ID_REVEAL_LIMIT_REACHED).
 *
 * <p>
 * Kept in memory, like LoginRateLimiter: it resets when the backend restarts,
 * and each backend instance counts separately. Fine for a single server.
 */
@Component
public class IdRevealLimiter {

    private static final Logger log = LoggerFactory.getLogger(IdRevealLimiter.class);
    private static final Duration WINDOW = Duration.ofHours(1);

    /** @param alerted true once the limit was reported in this window */
    private record Window(Instant start, int reveals, boolean alerted) {
    }

    /** Guarded by "this". Reveals are rare, so one lock is plenty. */
    private final Map<Long, Window> windows = new HashMap<>();
    private final int maxPerHour;
    private final AuditLogWriter audit;
    private final Clock clock;

    @Autowired
    public IdRevealLimiter(AuthProperties properties, AuditLogWriter audit) {
        this(properties.maxIdRevealsPerHour(), audit, Clock.systemUTC());
    }

    /** For the unit test, which needs a small limit and to move time forward. */
    IdRevealLimiter(int maxPerHour, AuditLogWriter audit, Clock clock) {
        this.maxPerHour = maxPerHour;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Counts one reveal for this user, or throws IdRevealLimitException (429) if
     * this hour's allowance is used up. Call BEFORE decrypting.
     */
    public void recordReveal(long userId) {
        Instant now = Instant.now(clock);
        Window refusedIn;
        boolean firstRefusal;

        synchronized (this) {
            Window current = windows.get(userId);
            if (current == null || !now.isBefore(current.start().plus(WINDOW))) {
                windows.put(userId, new Window(now, 1, false));
                return;
            }
            if (current.reveals() < maxPerHour) {
                windows.put(userId, new Window(current.start(), current.reveals() + 1, current.alerted()));
                return;
            }
            firstRefusal = !current.alerted();
            refusedIn = new Window(current.start(), current.reveals(), true);
            windows.put(userId, refusedIn);
        }

        // Outside the lock: writing to the database while holding it would make
        // every other reveal wait.
        if (firstRefusal) {
            log.warn("User {} reached the limit of {} full ID numbers per hour", userId, maxPerHour);
            audit.record("ID_REVEAL_LIMIT_REACHED", "USER", userId,
                    AuditLogWriter.meta("limit", maxPerHour, "window", WINDOW.toString()));
        }
        long secondsLeft = Duration.between(now, refusedIn.start().plus(WINDOW)).toSeconds();
        throw new IdRevealLimitException(Math.max(1, secondsLeft));
    }

    /** Forgets every user. Used by the automated tests between tests. */
    public synchronized void clear() {
        windows.clear();
    }
}
