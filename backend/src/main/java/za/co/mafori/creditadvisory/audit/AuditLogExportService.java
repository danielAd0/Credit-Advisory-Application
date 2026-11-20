package za.co.mafori.creditadvisory.audit;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.report.ReportCsv;

/**
 * GET /api/audit-logs/export: the audit trail as a CSV file, with the same
 * filters as the list, for an auditor or the information officer
 * (docs/api-auth-consultants-clients.md, section 8). Oldest first, as a file is
 * read. Times in South African time.
 *
 * <p>
 * The export is audited itself (AUDIT_LOG_EXPORTED, with the filters and the
 * row count), written after the rows are read, so it is not in its own file.
 * The audit log never holds a full ID number or a password, so neither can the
 * file.
 */
@Service
public class AuditLogExportService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Johannesburg");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String SELECT_ALL = """
            select a.occurred_at, a.action, u.email as actor_email, a.actor_user_id, a.target_type, a.target_id,
                   a.metadata::text as metadata
            from audit_logs a
            left join users u on u.id = a.actor_user_id
            """ + AuditLogService.WHERE + """
            order by a.occurred_at, a.id
            """;

    private static final String COUNT = "select count(*) from audit_logs a\n" + AuditLogService.WHERE;

    /** The file and its name. */
    public record CsvFile(String fileName, String content) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditLogWriter audit;
    private final int maxRows;

    public AuditLogExportService(NamedParameterJdbcTemplate jdbc, AuditLogWriter audit,
            @Value("${app.audit.export-max-rows:100000}") int maxRows) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.maxRows = maxRows;
    }

    /**
     * Not read-only: the export itself is written to the audit log.
     *
     * @throws ApiException 422 audit:export-too-large when the filters match more
     *                      than app.audit.export-max-rows entries
     */
    @Transactional
    public CsvFile export(AuditLogQuery query) {
        MapSqlParameterSource params = AuditLogService.filters(query);
        Long total = jdbc.queryForObject(COUNT, params, Long.class);
        if (total != null && total > maxRows) {
            throw ApiException.unprocessable("audit:export-too-large", "Too many entries",
                    "These filters match " + total + " entries; one file holds at most " + maxRows
                            + ". Choose a shorter period with from and to.");
        }

        List<List<Object>> rows = new ArrayList<>();
        jdbc.query(SELECT_ALL, params, rs -> {
            long actorId = rs.getLong("actor_user_id");
            boolean noActor = rs.wasNull();
            rows.add(Arrays.<Object>asList(
                    rs.getObject("occurred_at", OffsetDateTime.class).atZoneSameInstant(BUSINESS_ZONE).format(TIME),
                    rs.getString("action"),
                    noActor ? "the system" : rs.getString("actor_email"),
                    noActor ? null : actorId,
                    rs.getString("target_type"),
                    rs.getString("target_id"),
                    rs.getString("metadata")));
        });
        String content = ReportCsv.document(List.of("Time (South Africa)", "Action", "By", "By user id",
                "Target type", "Target id", "Details"), rows);

        audit.record("AUDIT_LOG_EXPORTED", null, null, meta("rows", rows.size(),
                "action", query.action(), "targetType", query.targetType(), "targetId", query.targetId(),
                "actorUserId", query.actorUserId(),
                "from", query.from() == null ? null : query.from().toString(),
                "to", query.to() == null ? null : query.to().toString()));
        return new CsvFile("audit-log-" + LocalDate.now(BUSINESS_ZONE) + ".csv", content);
    }
}
