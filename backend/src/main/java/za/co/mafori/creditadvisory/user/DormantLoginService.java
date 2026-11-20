package za.co.mafori.creditadvisory.user;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;

/**
 * Disables logins nobody has used for a long time
 * (docs/api-auth-consultants-clients.md,
 * section 6, "Dormant logins"): an account of someone who left, and that nobody
 * remembered to disable, should not stay a way in to every client's ID number.
 *
 * <ul>
 * <li>Off unless app.auth.dormant-after is set (DORMANT_LOGINS_AFTER, e.g.
 * 90d).</li>
 * <li>Only accounts that signed in at least once and not since then. An
 * account that never signed in has a temporary password, which expires on its
 * own (V24).</li>
 * <li>Never the last enabled administrator, so the system can always be
 * managed.</li>
 * <li>Each one is audited as USER_DISABLED_DORMANT, by the system (no actor).
 * An administrator enables it again on the Users page, as for any disabled
 * user; their session ends on the next request (AccountStillActiveFilter).</li>
 * </ul>
 */
@Service
public class DormantLoginService {

    private static final Logger log = LoggerFactory.getLogger(DormantLoginService.class);

    private final JdbcTemplate jdbc;
    private final UserAccountRepository users;
    private final AuditLogWriter audit;
    private final Duration dormantAfter;

    public DormantLoginService(JdbcTemplate jdbc, UserAccountRepository users, AuditLogWriter audit,
            @Value("${app.auth.dormant-after:0d}") Duration dormantAfter) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.dormantAfter = dormantAfter;
    }

    /** False when app.auth.dormant-after is 0 (the default) or negative. */
    public boolean isOn() {
        return dormantAfter.compareTo(Duration.ZERO) > 0;
    }

    /**
     * Disables every enabled login whose last sign-in was more than
     * app.auth.dormant-after before now.
     *
     * @return how many were disabled
     */
    @Transactional
    public int disableDormant(Instant now) {
        if (!isOn()) {
            return 0;
        }
        List<Long> ids = jdbc.queryForList("""
                select id from users
                where enabled and last_login_at is not null and last_login_at < ?
                order by last_login_at, id
                """, Long.class, Timestamp.from(now.minus(dormantAfter)));
        int disabled = 0;
        for (long id : ids) {
            UserAccount user = users.findById(id).orElse(null);
            if (user == null || !user.isEnabled()) {
                continue;
            }
            if (user.hasRole(RoleName.ADMIN) && users.countEnabledWithRole(RoleName.ADMIN) <= 1) {
                log.warn("Login {} has not been used for over {} days but is the only active administrator; "
                        + "left enabled", id, dormantAfter.toDays());
                continue;
            }
            Instant lastLogin = user.getLastLoginAt();
            user.disable();
            users.saveAndFlush(user);
            audit.record("USER_DISABLED_DORMANT", "USER", id, meta("lastLoginAt", String.valueOf(lastLogin),
                    "dormantAfterDays", dormantAfter.toDays()));
            disabled++;
        }
        return disabled;
    }
}
