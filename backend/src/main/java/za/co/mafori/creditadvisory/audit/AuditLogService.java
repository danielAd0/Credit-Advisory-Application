package za.co.mafori.creditadvisory.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.PageResponse;

/**
 * Reads the audit trail for admins (FR-21). Read-only: the table itself refuses
 * updates and deletes.
 *
 * <p>
 * Plain SQL instead of an entity, like AuditLogWriter, because metadata is
 * JSONB
 * and is passed straight through to the response.
 */
@Service
public class AuditLogService {

    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Every filter is optional. Each parameter is given an explicit SQL type, so a
     * null filter is still understood by PostgreSQL (an untyped null is what caused
     * the "lower(bytea)" error in the employer search).
     */
    private static final String WHERE = """
            where (cast(:action as varchar) is null or a.action = :action)
              and (cast(:targetType as varchar) is null or a.target_type = :targetType)
              and (cast(:targetId as varchar) is null or a.target_id = :targetId)
              and (cast(:actorUserId as bigint) is null or a.actor_user_id = :actorUserId)
              and (cast(:from as timestamptz) is null or a.occurred_at >= cast(:from as timestamptz))
              and (cast(:to as timestamptz) is null or a.occurred_at < cast(:to as timestamptz))
            """;

    private static final String SELECT_PAGE = """
            select a.id, a.occurred_at, a.action, a.actor_user_id, u.email as actor_email,
                   a.target_type, a.target_id, a.metadata::text as metadata
            from audit_logs a
            left join users u on u.id = a.actor_user_id
            """ + WHERE + """
            order by a.occurred_at desc, a.id desc
            limit :limit offset :offset
            """;

    private static final String COUNT = "select count(*) from audit_logs a\n" + WHERE;

    private final NamedParameterJdbcTemplate jdbc;

    public AuditLogService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogEntry> search(AuditLogQuery query, int page, int size) {
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation:invalid-filter", "Invalid filter",
                    "'from' must be before 'to'.");
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("action", upperOrNull(query.action()), Types.VARCHAR)
                .addValue("targetType", upperOrNull(query.targetType()), Types.VARCHAR)
                .addValue("targetId", blankToNull(query.targetId()), Types.VARCHAR)
                .addValue("actorUserId", query.actorUserId(), Types.BIGINT)
                .addValue("from", isoOrNull(query.from()), Types.VARCHAR)
                .addValue("to", isoOrNull(query.to()), Types.VARCHAR);

        Long total = jdbc.queryForObject(COUNT, params, Long.class);
        long totalItems = total == null ? 0 : total;

        params.addValue("limit", safeSize, Types.INTEGER)
                .addValue("offset", (long) safePage * safeSize, Types.BIGINT);
        List<AuditLogEntry> items = jdbc.query(SELECT_PAGE, params, AuditLogService::toEntry);

        int totalPages = (int) ((totalItems + safeSize - 1) / safeSize);
        return new PageResponse<>(items, safePage, safeSize, totalItems, totalPages);
    }

    private static AuditLogEntry toEntry(ResultSet rs, int rowNum) throws SQLException {
        long actorId = rs.getLong("actor_user_id");
        AuditLogEntry.Actor actor = rs.wasNull() ? null : new AuditLogEntry.Actor(actorId, rs.getString("actor_email"));
        return new AuditLogEntry(
                rs.getLong("id"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("action"),
                actor,
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("metadata"));
    }

    /**
     * Sent as ISO text (e.g. 2026-11-02T13:00:00Z) and cast by PostgreSQL - no time
     * zone surprises.
     */
    private static String isoOrNull(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Actions and target types are stored upper-case (e.g. USER,
     * CONSULTANT_STATUS_CHANGED).
     */
    private static String upperOrNull(String value) {
        String v = blankToNull(value);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }
}
