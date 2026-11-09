package za.co.mafori.creditadvisory.employer;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.common.web.ApiException;

@Service
public class EmployerMergeService {

    private final JdbcTemplate jdbc;
    private final AuditLogWriter audit;

    public EmployerMergeService(JdbcTemplate jdbc, AuditLogWriter audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private record Row(long id, String name, long version, boolean active) {
    }

    @Transactional(readOnly = true)
    public List<EmployerAliasResponse> aliases(long employerId) {
        lockedRow(employerId, false);
        return jdbc.query("""
                select id, alias, created_at from employer_aliases where employer_id = ? order by lower(alias), id
                """, (rs, n) -> new EmployerAliasResponse(rs.getLong(1), rs.getString(2),
                rs.getTimestamp(3).toInstant()), employerId);
    }

    @Transactional
    public EmployerMergeResponse merge(long sourceId, EmployerMergeRequest request) {
        long targetId = request.targetEmployerId();
        if (targetId == sourceId) {
            throw ApiException.unprocessable("employer:merge-into-itself", "Same employer",
                    "Choose another employer to merge into.");
        }
        Row first = lockedRow(Math.min(sourceId, targetId), true);
        Row second = lockedRow(Math.max(sourceId, targetId), true);
        Row source = first.id() == sourceId ? first : second;
        Row target = first.id() == targetId ? first : second;
        if (source.version() != request.version()) {
            throw ApiException.staleVersion();
        }
        if (!target.active()) {
            throw ApiException.conflict("employer:merge-target-inactive", "Inactive employer",
                    "Employer " + target.name() + " is inactive. Activate it first, or choose another one.");
        }

        int clients = jdbc.update("update clients set employer_id = ?, version = version + 1 where employer_id = ?",
                targetId, sourceId);
        int transactions = jdbc.update("update client_transactions set employer_id = ? where employer_id = ?",
                targetId, sourceId);
        int aliases = jdbc.update("update employer_aliases set employer_id = ? where employer_id = ?",
                targetId, sourceId);
        boolean keptAsAlias = false;
        if (!sameName(source.name(), target.name()) && !aliasExists(source.name())) {
            jdbc.update("insert into employer_aliases (employer_id, alias, created_by_user_id) values (?, ?, ?)",
                    targetId, source.name(), currentUserIdOrNull());
            keptAsAlias = true;
        }
        jdbc.update("delete from employers where id = ?", sourceId);
        jdbc.update("update employers set version = version + 1 where id = ?", targetId);

        audit.record("EMPLOYER_MERGED", "EMPLOYER", targetId, meta("mergedEmployerId", sourceId,
                "mergedEmployerName", source.name(), "clientsMoved", clients, "transactionsMoved", transactions,
                "aliasesMoved", aliases, "reason", request.reason().strip()));
        EmployerResponse after = response(targetId);
        return new EmployerMergeResponse(sourceId, source.name(), after, clients, transactions, aliases, keptAsAlias);
    }

    @Transactional
    public EmployerResponse markReviewed(long id, EmployerReviewRequest request) {
        Row row = lockedRow(id, true);
        if (row.version() != request.version()) {
            throw ApiException.staleVersion();
        }
        jdbc.update("update employers set needs_review = false, version = version + 1 where id = ?", id);
        audit.record("EMPLOYER_REVIEWED", "EMPLOYER", id, meta("name", row.name()));
        return response(id);
    }

    private EmployerResponse response(long id) {
        return jdbc.queryForObject("""
                select id, name, company, employer_type, active, version, created_at, updated_at, needs_review
                from employers where id = ?
                """, (rs, n) -> new EmployerResponse(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getBoolean(5), rs.getLong(6), rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8).toInstant(), rs.getBoolean(9)), id);
    }

    private Row lockedRow(long id, boolean forUpdate) {
        return jdbc.query("select id, name, version, active from employers where id = ?"
                + (forUpdate ? " for update" : ""),
                (rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4)), id)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("employer:not-found", "Employer " + id + " does not exist."));
    }

    private boolean aliasExists(String alias) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                        select count(*) > 0 from employer_aliases
                        where lower(regexp_replace(btrim(alias), '\\s+', ' ', 'g')) = lower(regexp_replace(btrim(?), '\\s+', ' ', 'g'))
                        """,
                Boolean.class, alias));
    }

    private static boolean sameName(String a, String b) {
        return Employer.normalizeName(a).equalsIgnoreCase(Employer.normalizeName(b));
    }

    private static Long currentUserIdOrNull() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof za.co.mafori.creditadvisory.auth.AppUserPrincipal p
                ? p.userId()
                : null;
    }
}
