package za.co.mafori.creditadvisory.onboarding;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQL for consultant onboarding (V23). Never reads or returns a plain password.
 */
@Component
public class OnboardingStore {

    private final JdbcTemplate jdbc;

    public OnboardingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- consultants ----------

    /**
     * Active consultants with an email, no login of their own, and whose email no
     * other login uses. These can get a login.
     */
    private static final String ELIGIBLE = """
            from consultants c
            where c.status = 'ACTIVE' and c.email is not null
              and not exists (select 1 from users u where u.linked_consultant_id = c.id)
              and not exists (select 1 from users u where lower(u.email) = c.email)
            """;

    public record EligibleConsultant(long id, String code, String fullName, String email) {
    }

    public List<EligibleConsultant> eligible(Collection<Long> onlyIds, int limit) {
        if (onlyIds == null) {
            return jdbc.query("select c.id, c.consultant_code, c.full_name, c.email " + ELIGIBLE
                    + " order by c.id limit ?", (rs, n) -> eligible(rs), limit);
        }
        return jdbc.query("select c.id, c.consultant_code, c.full_name, c.email " + ELIGIBLE
                + " and c.id = any (cast(? as bigint[])) order by c.id limit ?", (rs, n) -> eligible(rs),
                longArray(onlyIds), limit);
    }

    public long countEligible() {
        return jdbc.queryForObject("select count(*) " + ELIGIBLE, Long.class);
    }

    private static EligibleConsultant eligible(ResultSet rs) throws SQLException {
        return new EligibleConsultant(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4));
    }

    /**
     * Why a consultant can NOT get a login; one row per consultant that needs
     * attention.
     */
    public record Blocked(long id, String code, String fullName, String email, String status, String reason) {
    }

    public List<Blocked> blocked(int limit, long offset) {
        return jdbc.query(BLOCKED + " order by reason, c.full_name limit ? offset ?",
                (rs, n) -> new Blocked(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)),
                limit, offset);
    }

    public long countBlocked() {
        return jdbc.queryForObject("select count(*) from (" + BLOCKED + ") b", Long.class);
    }

    /** Active consultants without a login of their own who cannot get one yet. */
    private static final String BLOCKED = """
            select c.id, c.consultant_code, c.full_name, c.email, c.status,
                   case when c.email is null then 'NO_EMAIL' else 'EMAIL_USED_BY_ANOTHER_LOGIN' end as reason
            from consultants c
            where c.status = 'ACTIVE'
              and not exists (select 1 from users u where u.linked_consultant_id = c.id)
              and (c.email is null or exists (select 1 from users u where lower(u.email) = c.email))
            """;

    public record ConsultantCounts(long total, long active, long withEmail, long withLogin) {
    }

    public ConsultantCounts consultantCounts() {
        return jdbc.queryForObject("""
                select count(*),
                       count(*) filter (where status = 'ACTIVE'),
                       count(*) filter (where email is not null),
                       count(*) filter (where exists (select 1 from users u where u.linked_consultant_id = c.id))
                from consultants c
                """, (rs, n) -> new ConsultantCounts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
    }

    public record ConsultantEmailRow(long id, String code, String fullName, String email, boolean hasLogin) {
    }

    public Optional<ConsultantEmailRow> consultant(long id) {
        return jdbc.query("""
                select c.id, c.consultant_code, c.full_name, c.email,
                       exists (select 1 from users u where u.linked_consultant_id = c.id)
                from consultants c where c.id = ?
                """, (rs, n) -> new ConsultantEmailRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getBoolean(5)), id).stream().findFirst();
    }

    public boolean consultantEmailTaken(String email, long exceptConsultantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from consultants where email = ? and id <> ?)", Boolean.class, email,
                exceptConsultantId));
    }

    public void setConsultantEmail(long id, String email) {
        jdbc.update("update consultants set email = ? where id = ?", email, id);
    }

    // ---------- invites ----------

    public long insertInvite(long consultantId, long userId, String email, byte[] sealedPassword,
            long generatedByUserId) {
        return jdbc.queryForObject("""
                insert into consultant_login_invites (consultant_id, user_id, email, password_sealed,
                    generated_by_user_id)
                values (?, ?, ?, ?, ?) returning id
                """, Long.class, consultantId, userId, email, sealedPassword, generatedByUserId);
    }

    /** PENDING -> QUEUED (all, or only these ids). */
    public int queuePending(Collection<Long> onlyIds, long byUserId) {
        String sql = """
                update consultant_login_invites
                set status = 'QUEUED', queued_by_user_id = ?, queued_at = now(), next_attempt_at = now()
                where status = 'PENDING'
                """;
        return onlyIds == null ? jdbc.update(sql, byUserId)
                : jdbc.update(sql + " and id = any (cast(? as bigint[]))", byUserId, longArray(onlyIds));
    }

    /**
     * FAILED -> QUEUED with a fresh set of attempts. The same password is sent:
     * nothing is generated again.
     */
    public int retryFailed(Collection<Long> onlyIds, long byUserId) {
        String sql = """
                update consultant_login_invites
                set status = 'QUEUED', attempts = 0, last_error = null, queued_by_user_id = ?, queued_at = now(),
                    next_attempt_at = now()
                where status = 'FAILED'
                """;
        return onlyIds == null ? jdbc.update(sql, byUserId)
                : jdbc.update(sql + " and id = any (cast(? as bigint[]))", byUserId, longArray(onlyIds));
    }

    public record Claimed(long id, long consultantId, long userId, String email, byte[] passwordSealed,
            int attempts, String consultantName) {

        @Override
        public String toString() {
            return "Claimed[id=" + id + ", attempts=" + attempts + "]";
        }
    }

    /**
     * Takes up to {@code limit} due invites for sending (QUEUED -> SENDING) in one
     * statement. SKIP LOCKED: two servers never take the same invite.
     */
    public List<Claimed> claimDue(int limit) {
        return jdbc.query("""
                with due as (
                    select id from consultant_login_invites
                    where status = 'QUEUED' and next_attempt_at <= now()
                    order by next_attempt_at, id
                    limit ?
                    for update skip locked
                )
                update consultant_login_invites i
                set status = 'SENDING', attempts = i.attempts + 1, last_attempt_at = now()
                from due
                where i.id = due.id
                returning i.id, i.consultant_id, i.user_id, i.email, i.password_sealed, i.attempts,
                          (select c.full_name from consultants c where c.id = i.consultant_id)
                """, (rs, n) -> new Claimed(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                rs.getBytes(5), rs.getInt(6), rs.getString(7)), limit);
    }

    /**
     * SENDING for longer than this (the server stopped mid-send): back in the
     * queue.
     */
    public int releaseStuck(java.time.Duration olderThan) {
        return jdbc.update("""
                update consultant_login_invites set status = 'QUEUED', next_attempt_at = now()
                where status = 'SENDING' and last_attempt_at < now() - make_interval(secs => ?)
                """, olderThan.toSeconds());
    }

    /** Sent: the sealed password is deleted in the same statement. */
    public void markSent(long id) {
        jdbc.update("""
                update consultant_login_invites
                set status = 'SENT', password_sealed = null, sent_at = now(), last_error = null,
                    next_attempt_at = null
                where id = ? and status = 'SENDING'
                """, id);
    }

    /**
     * The emailed password now has {@code days} to be used (V24). Only while the
     * login still has to change its password: one already changed never expires.
     */
    public void startPasswordClock(long userId, int days) {
        jdbc.update("""
                update users set temporary_password_expires_at = now() + make_interval(days => ?)
                where id = ? and must_change_password
                """, days, userId);
    }

    public void markRetry(long id, String error, Instant nextAttempt) {
        jdbc.update("""
                update consultant_login_invites set status = 'QUEUED', last_error = ?, next_attempt_at = ?
                where id = ? and status = 'SENDING'
                """, truncate(error), Timestamp.from(nextAttempt), id);
    }

    public void markFailed(long id, String error) {
        jdbc.update("""
                update consultant_login_invites set status = 'FAILED', last_error = ?, next_attempt_at = null
                where id = ? and status = 'SENDING'
                """, truncate(error), id);
    }

    /**
     * Puts a claimed invite back untouched (sending stopped before it was tried).
     */
    public void unclaim(long id) {
        jdbc.update("""
                update consultant_login_invites set status = 'QUEUED', attempts = greatest(attempts - 1, 0)
                where id = ? and status = 'SENDING'
                """, id);
    }

    public Map<String, Long> inviteCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : List.of("PENDING", "QUEUED", "SENDING", "SENT", "FAILED")) {
            counts.put(s, 0L);
        }
        jdbc.query("select status, count(*) from consultant_login_invites group by status",
                (rs, n) -> counts.put(rs.getString(1), rs.getLong(2)));
        return counts;
    }

    /**
     * @param passwordExpiresAt when the emailed password stops working (null: not
     *                          sent yet, or already changed by the consultant)
     * @param expired           sent, never used to set a new password, and past
     *                          its time limit: regenerate it
     * @param signedIn          the consultant has chosen their own password
     */
    public record InviteRow(long id, long consultantId, String consultantCode, String consultantName, long userId,
            String email, String status, int attempts, String lastError, Instant generatedAt, Instant queuedAt,
            Instant lastAttemptAt, Instant nextAttemptAt, Instant sentAt, Instant passwordExpiresAt, boolean expired,
            boolean signedIn) {
    }

    private static final String INVITE_COLUMNS = """
            select i.id, i.consultant_id, c.consultant_code, c.full_name, i.user_id, i.email, i.status, i.attempts,
                   i.last_error, i.generated_at, i.queued_at, i.last_attempt_at, i.next_attempt_at, i.sent_at,
                   u.temporary_password_expires_at,
                   (i.status = 'SENT' and u.must_change_password and u.temporary_password_expires_at <= now()),
                   (i.status = 'SENT' and not u.must_change_password)
            from consultant_login_invites i
            join consultants c on c.id = i.consultant_id
            join users u on u.id = i.user_id
            """;

    /**
     * Sent emails whose password expired unused. Used as the status filter
     * "EXPIRED".
     */
    private static final String EXPIRED = """
            i.status = 'SENT' and u.must_change_password and u.temporary_password_expires_at <= now()
            """;

    public List<InviteRow> invites(String status, int limit, long offset) {
        String order = " order by i.generated_at desc, i.id desc limit ? offset ?";
        if (status == null) {
            return jdbc.query(INVITE_COLUMNS + order, (rs, n) -> invite(rs), limit, offset);
        }
        if ("EXPIRED".equals(status)) {
            return jdbc.query(INVITE_COLUMNS + " where " + EXPIRED + order, (rs, n) -> invite(rs), limit, offset);
        }
        return jdbc.query(INVITE_COLUMNS + " where i.status = ?" + order, (rs, n) -> invite(rs), status, limit,
                offset);
    }

    public long countInvites(String status) {
        if (status == null) {
            return jdbc.queryForObject("select count(*) from consultant_login_invites", Long.class);
        }
        if ("EXPIRED".equals(status)) {
            return countExpired();
        }
        return jdbc.queryForObject("select count(*) from consultant_login_invites where status = ?", Long.class,
                status);
    }

    public long countExpired() {
        return jdbc.queryForObject("select count(*) from consultant_login_invites i join users u on u.id = i.user_id "
                + "where " + EXPIRED, Long.class);
    }

    private static InviteRow invite(ResultSet rs) throws SQLException {
        return new InviteRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                rs.getString(6), rs.getString(7), rs.getInt(8), rs.getString(9), instant(rs, 10), instant(rs, 11),
                instant(rs, 12), instant(rs, 13), instant(rs, 14), instant(rs, 15), rs.getBoolean(16),
                rs.getBoolean(17));
    }

    // ---------- regenerating ----------

    /**
     * Invites that get a new password from "Regenerate": the login is enabled and
     * still has to change its password, and the email either went out and expired
     * unused, or FAILED (for example the sealed password can no longer be read
     * after a key rotation).
     */
    public record Regeneratable(long inviteId, long userId, long consultantId, String consultantCode,
            String consultantName, String email) {
    }

    public List<Regeneratable> regeneratable(Collection<Long> onlyIds, int limit) {
        String sql = """
                select i.id, i.user_id, i.consultant_id, c.consultant_code, c.full_name, i.email
                from consultant_login_invites i
                join users u on u.id = i.user_id
                join consultants c on c.id = i.consultant_id
                where u.enabled and u.must_change_password and c.status = 'ACTIVE'
                  and ((""" + EXPIRED + ") or i.status = 'FAILED')";
        String order = " order by i.id limit ?";
        var mapper = (org.springframework.jdbc.core.RowMapper<Regeneratable>) (rs, n) -> new Regeneratable(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6));
        return onlyIds == null ? jdbc.query(sql + order, mapper, limit)
                : jdbc.query(sql + " and i.id = any (cast(? as bigint[]))" + order, mapper, longArray(onlyIds), limit);
    }

    public long countRegeneratable() {
        return jdbc.queryForObject("""
                select count(*) from consultant_login_invites i
                join users u on u.id = i.user_id
                join consultants c on c.id = i.consultant_id
                where u.enabled and u.must_change_password and c.status = 'ACTIVE'
                  and ((""" + EXPIRED + ") or i.status = 'FAILED')", Long.class);
    }

    /** A new sealed password: back to "not sent yet", as if just generated. */
    public void resetInvite(long id, byte[] sealedPassword, long byUserId) {
        jdbc.update("""
                update consultant_login_invites
                set status = 'PENDING', password_sealed = ?, attempts = 0, last_error = null,
                    generated_by_user_id = ?, generated_at = now(), queued_by_user_id = null, queued_at = null,
                    next_attempt_at = null, last_attempt_at = null, sent_at = null
                where id = ?
                """, sealedPassword, byUserId, id);
    }

    // ---------- the login an invite belongs to ----------

    /**
     * consultantStatus: of the consultant the login belongs to, or null if it is no
     * longer linked.
     */
    public record LoginState(boolean enabled, boolean mustChangePassword, String passwordHash,
            String consultantStatus) {

        @Override
        public String toString() {
            return "LoginState[enabled=" + enabled + ", mustChangePassword=" + mustChangePassword
                    + ", consultantStatus=" + consultantStatus + "]";
        }
    }

    public Optional<LoginState> loginState(long userId) {
        return jdbc.query("""
                select u.enabled, u.must_change_password, u.password_hash, c.status
                from users u left join consultants c on c.id = u.linked_consultant_id
                where u.id = ?
                """, (rs, n) -> new LoginState(rs.getBoolean(1), rs.getBoolean(2), rs.getString(3),
                rs.getString(4)), userId).stream().findFirst();
    }

    // ---------- helpers ----------

    /** A Postgres array literal ("{1,2,3}"), cast to bigint[] in the SQL. */
    private static String longArray(Collection<Long> ids) {
        StringBuilder sb = new StringBuilder("{");
        for (Long id : ids) {
            if (id != null) {
                sb.append(sb.length() > 1 ? "," : "").append(id.longValue());
            }
        }
        return sb.append('}').toString();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() <= 500 ? s : s.substring(0, 500);
    }
}
