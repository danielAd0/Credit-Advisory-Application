package za.co.mafori.creditadvisory.imports.alias;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.PageResponse;

@Service
public class ConsultantAliasService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final String KEY = "lower(regexp_replace(btrim(%s), '\\s+', ' ', 'g'))";

    private final JdbcTemplate jdbc;
    private final AuditLogWriter audit;

    public ConsultantAliasService(JdbcTemplate jdbc, AuditLogWriter audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record AliasView(long id, String alias, long consultantId, String consultantCode, String consultantName,
            String consultantStatus, String createdBy, Instant createdAt, long transactions) {
    }

    public record CreateAliasRequest(Long consultantId, String alias) {
    }

    @Transactional(readOnly = true)
    public PageResponse<AliasView> list(Long consultantId, String search, int page, int size) {
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int p = Math.max(page, 0);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where true");
        if (consultantId != null) {
            where.append(" and a.consultant_id = ?");
            args.add(consultantId);
        }
        if (search != null && !search.isBlank()) {
            where.append(" and (").append(KEY.formatted("a.alias")).append(" like ? or lower(c.full_name) like ?)");
            String like = "%" + search.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ")
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        long total = jdbc
                .queryForObject("select count(*) from consultant_aliases a join consultants c on c.id = a.consultant_id"
                        + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<AliasView> items = jdbc.query("""
                select a.id, a.alias, c.id, c.consultant_code, c.full_name, c.status, u.email, a.created_at,
                       (select count(*) from client_transactions t
                        where %s = %s) as transactions
                from consultant_aliases a
                join consultants c on c.id = a.consultant_id
                left join users u on u.id = a.created_by_user_id
                """.formatted(KEY.formatted("t.source_consultant_name"), KEY.formatted("a.alias"))
                + where + " order by lower(c.full_name), lower(a.alias), a.id limit ? offset ?",
                (rs, n) -> new AliasView(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), instant(rs.getTimestamp(8)), rs.getLong(9)),
                pageArgs.toArray());
        return new PageResponse<>(items, p, s, total, (int) ((total + s - 1) / s));
    }

    @Transactional
    public AliasView create(long userId, CreateAliasRequest request) {
        if (request.consultantId() == null) {
            throw ApiException.unprocessable("consultant-alias:consultant-required", "Consultant required",
                    "Choose the consultant this spelling belongs to.");
        }
        String alias = request.alias() == null ? "" : request.alias().strip().replaceAll("\\s+", " ");
        if (alias.isEmpty() || alias.length() > 200) {
            throw ApiException.unprocessable("consultant-alias:invalid", "Invalid spelling",
                    "The spelling must be 1 to 200 characters.");
        }
        Long consultant = jdbc.query("select id from consultants where id = ? for update",
                (rs, n) -> rs.getLong(1), request.consultantId()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("consultant:not-found",
                        "Consultant " + request.consultantId() + " does not exist."));
        boolean taken = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from consultant_aliases where %s = %s)
                    or exists (select 1 from consultants where %s = %s)
                """.formatted(KEY.formatted("alias"), KEY.formatted("?"), KEY.formatted("full_name"),
                KEY.formatted("?")), Boolean.class, alias, alias));
        if (taken) {
            throw ApiException.conflict("consultant-alias:taken", "Spelling already used",
                    "That spelling already matches a consultant (their name or another alias).");
        }
        long id = jdbc.queryForObject("""
                insert into consultant_aliases (consultant_id, alias, created_by_user_id) values (?, ?, ?) returning id
                """, Long.class, consultant, alias, userId);
        audit.record("CONSULTANT_ALIAS_CREATED", "CONSULTANT", consultant, meta("alias", alias, "aliasId", id));
        return get(id);
    }

    @Transactional
    public void remove(long id) {
        AliasView alias = get(id);
        jdbc.update("delete from consultant_aliases where id = ?", id);
        audit.record("CONSULTANT_ALIAS_REMOVED", "CONSULTANT", alias.consultantId(),
                meta("alias", alias.alias(), "aliasId", id, "transactions", alias.transactions()));
    }

    private AliasView get(long id) {
        return jdbc.query("""
                select a.id, a.alias, c.id, c.consultant_code, c.full_name, c.status, u.email, a.created_at,
                       (select count(*) from client_transactions t where %s = %s)
                from consultant_aliases a
                join consultants c on c.id = a.consultant_id
                left join users u on u.id = a.created_by_user_id
                where a.id = ?
                """.formatted(KEY.formatted("t.source_consultant_name"), KEY.formatted("a.alias")),
                (rs, n) -> new AliasView(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), instant(rs.getTimestamp(8)), rs.getLong(9)),
                id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("consultant-alias:not-found",
                        "Consultant alias " + id + " does not exist."));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
