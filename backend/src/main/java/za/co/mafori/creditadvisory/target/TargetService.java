package za.co.mafori.creditadvisory.target;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.common.web.ApiException;

/**
 * Reads and sets the daily and weekly quotation targets. Role checks live on
 * the controller.
 */
@Service
public class TargetService {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Johannesburg");

    private static final RowMapper<TargetResponse> ROW = (rs, n) -> new TargetResponse(
            rs.getLong("id"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getBigDecimal("daily_quotation_target"),
            rs.getBigDecimal("weekly_quotation_target"),
            rs.getString("email"),
            rs.getTimestamp("created_at").toInstant());

    private static final String SELECT = """
            select s.id, s.effective_from, s.daily_quotation_target, s.weekly_quotation_target,
                   u.email, s.created_at
            from sales_targets s
                     left join users u on u.id = s.created_by_user_id
            """;

    private final JdbcTemplate jdbc;
    private final AuditLogWriter audit;

    public TargetService(JdbcTemplate jdbc, AuditLogWriter audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public TargetsResponse list() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        List<TargetResponse> history = jdbc.query(SELECT + " order by s.effective_from desc", ROW);
        TargetResponse current = history.stream()
                .filter(t -> !t.effectiveFrom().isAfter(today))
                .findFirst()
                .orElse(null);
        return new TargetsResponse(today, current, history);
    }

    /**
     * Adds a target row, or replaces the one already set for that date (so a
     * mistake can be corrected the same day).
     *
     * <p>
     * There are no built-in targets (V25), so a period can have none. A past
     * date is allowed only while it has no target: before the first target
     * ever saved, which fills in a period that showed "Target not configured"
     * (useful after importing old registers). Once a date is covered by a
     * target, changes must start today or later, so reports already read keep
     * the target they had.
     */
    @Transactional
    public TargetResponse set(SetTargetRequest request) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (request.effectiveFrom().isBefore(today)) {
            LocalDate first = firstEffectiveFrom();
            if (first != null && !request.effectiveFrom().isBefore(first)) {
                throw ApiException.unprocessable("target:past-date", "Date in the past",
                        "A target already applies to that date. A change must start today or later, "
                                + "so past weeks keep the target they had. Past dates can only be given a "
                                + "target before " + first + ", when no target was set yet.");
            }
        }
        Long id = jdbc.queryForObject("""
                insert into sales_targets (effective_from, daily_quotation_target, weekly_quotation_target,
                                           created_by_user_id)
                values (?, ?, ?, ?)
                on conflict (effective_from) do update
                    set daily_quotation_target  = excluded.daily_quotation_target,
                        weekly_quotation_target = excluded.weekly_quotation_target,
                        created_by_user_id      = excluded.created_by_user_id,
                        created_at              = now()
                returning id
                """, Long.class,
                request.effectiveFrom(), request.dailyTarget(), request.weeklyTarget(), currentUserId());
        audit.record("SALES_TARGET_SET", "SALES_TARGET", id,
                meta("effectiveFrom", request.effectiveFrom().toString(),
                        "dailyTarget", request.dailyTarget(),
                        "weeklyTarget", request.weeklyTarget()));
        return jdbc.queryForObject(SELECT + " where s.id = ?", ROW, id);
    }

    /** The start of the earliest target ever saved, or null when none has been. */
    private LocalDate firstEffectiveFrom() {
        return jdbc.queryForObject("select min(effective_from) from sales_targets", LocalDate.class);
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AppUserPrincipal p ? p.userId() : null;
    }
}
