package za.co.mafori.creditadvisory.report;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.client.ClientQueryService;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.report.PeriodReportViews.BudgetResponse;
import za.co.mafori.creditadvisory.report.PeriodReportViews.BudgetRow;
import za.co.mafori.creditadvisory.report.PeriodReportViews.ConsultantRow;
import za.co.mafori.creditadvisory.report.PeriodReportViews.ConsultantsResponse;
import za.co.mafori.creditadvisory.report.PeriodReportViews.Day;
import za.co.mafori.creditadvisory.report.PeriodReportViews.Month;
import za.co.mafori.creditadvisory.report.PeriodReportViews.MonthsResponse;
import za.co.mafori.creditadvisory.report.PeriodReportViews.ProductRow;
import za.co.mafori.creditadvisory.report.PeriodReportViews.ProductsResponse;
import za.co.mafori.creditadvisory.report.PeriodReportViews.SummaryResponse;
import za.co.mafori.creditadvisory.report.PeriodReportViews.Totals;
import za.co.mafori.creditadvisory.report.PeriodReportViews.WeekPart;

/**
 * Reports for any period (docs/api-reports.md section 9): a calendar month, or
 * any range of up to {@link #MAX_DAYS} days.
 *
 * <p>
 * Same data and rules as ReportService: every figure comes from
 * v_transaction_totals and the sales lines, so sales replaced by a later import
 * of their week are left out, and imported and typed-in sales both count. The
 * only difference is how sales are chosen: by their own transaction date. A
 * month therefore holds exactly its own days, also when a week runs across
 * month-end.
 *
 * <p>
 * Scoping is the weekly reports' (SRS section 4): MANAGER and ADMIN see the
 * whole company and the targets; a CONSULTANT only their own sales, without
 * targets.
 */
@Service
public class PeriodReportService {

    /** A year, plus a leap day: enough for "this year" or the last 12 months. */
    static final int MAX_DAYS = 366;

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /** See ReportService.NO_CONSULTANT: no consultant has this id. */
    private static final long NO_CONSULTANT = -1L;

    private final JdbcTemplate jdbc;

    public PeriodReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A checked period. */
    record Period(LocalDate from, LocalDate to) {
    }

    // ---------- GET /api/reports/months ----------

    @Transactional(readOnly = true)
    public MonthsResponse months(AppUserPrincipal caller) {
        boolean all = seesEverything(caller);
        String own = all ? "" : " where tt.consultant_id = ?";
        Object[] args = all ? new Object[0] : new Object[] { ownConsultantId(caller) };
        List<Month> months = jdbc.query("""
                select to_char(date_trunc('month', tt.transaction_date), 'YYYY-MM') as month,
                       count(*)                                             as transaction_count,
                       coalesce(sum(tt.quotation_total), 0)::numeric(14, 2) as quotation_total,
                       coalesce(sum(tt.banked_total), 0)::numeric(14, 2)    as banked_total
                from v_transaction_totals tt""" + own + """

                group by 1
                order by 1 desc
                """, (rs, n) -> {
            YearMonth month = YearMonth.parse(rs.getString("month"));
            return new Month(month.toString(), month.atDay(1), month.atEndOfMonth(),
                    rs.getLong("transaction_count"), rs.getBigDecimal("quotation_total"),
                    rs.getBigDecimal("banked_total"));
        }, args);
        return new MonthsResponse(all ? ReportService.ALL : ReportService.OWN, months);
    }

    // ---------- GET /api/reports/range/summary ----------

    @Transactional(readOnly = true)
    public SummaryResponse summary(AppUserPrincipal caller, String rawFrom, String rawTo) {
        Period p = period(rawFrom, rawTo);
        boolean all = seesEverything(caller);
        String own = all ? "" : " and tt.consultant_id = ?";

        List<Object> dayArgs = new ArrayList<>(List.of(p.from(), p.to()));
        if (!all) {
            dayArgs.add(ownConsultantId(caller));
        }
        List<Day> days = jdbc.query("""
                select d::date                                              as day,
                       count(tt.transaction_id)                             as transaction_count,
                       count(distinct tt.client_id)                         as client_count,
                       coalesce(sum(tt.quotation_total), 0)::numeric(14, 2) as quotation_total,
                       coalesce(sum(tt.banked_total), 0)::numeric(14, 2)    as banked_total,
                       (select s.daily_quotation_target from sales_targets s
                        where s.effective_from <= d::date
                        order by s.effective_from desc limit 1)            as daily_target
                from generate_series(cast(? as date), cast(? as date), interval '1 day') d
                         left join v_transaction_totals tt on tt.transaction_date = d::date""" + own + """

                group by d
                order by d
                """, (rs, n) -> {
            LocalDate date = rs.getObject("day", LocalDate.class);
            BigDecimal quotation = rs.getBigDecimal("quotation_total");
            BigDecimal target = all ? rs.getBigDecimal("daily_target") : null;
            return new Day(date, date.getDayOfWeek().getValue(), rs.getLong("transaction_count"),
                    rs.getLong("client_count"), quotation, rs.getBigDecimal("banked_total"), target,
                    target == null ? null : quotation.compareTo(target) >= 0);
        }, dayArgs.toArray());

        // Distinct clients are counted over the whole period, not added up per day.
        List<Object> totalArgs = new ArrayList<>(List.of(p.from(), p.to()));
        if (!all) {
            totalArgs.add(ownConsultantId(caller));
        }
        Object[] totalsRow = jdbc.queryForObject("""
                select count(*), count(distinct tt.client_id),
                       count(*) filter (where tt.consultant_id is null),
                       coalesce(sum(tt.quotation_total), 0)::numeric(14, 2),
                       coalesce(sum(tt.banked_total), 0)::numeric(14, 2)
                from v_transaction_totals tt
                where tt.transaction_date between ? and ?""" + own,
                (rs, n) -> new Object[] { rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5) },
                totalArgs.toArray());

        // Only days with sales and a configured target are judged: with no target
        // there is nothing to measure against, so nothing is "met" or "missed".
        int daysWithSales = 0;
        int daysJudged = 0;
        int daysMet = 0;
        for (Day d : days) {
            if (d.transactionCount() > 0) {
                daysWithSales++;
                if (d.targetMet() != null) {
                    daysJudged++;
                    if (d.targetMet()) {
                        daysMet++;
                    }
                }
            }
        }
        Totals totals = new Totals((Long) totalsRow[0], (Long) totalsRow[1], all ? (Long) totalsRow[2] : null,
                (BigDecimal) totalsRow[3], (BigDecimal) totalsRow[4], daysWithSales, all ? daysJudged : null,
                all && daysJudged > 0 ? daysMet : null);

        return new SummaryResponse(all ? ReportService.ALL : ReportService.OWN, p.from(), p.to(), totals, days,
                weekParts(p, days, all));
    }

    /** The days grouped by their Monday-to-Sunday week, with the week's target. */
    private List<WeekPart> weekParts(Period p, List<Day> days, boolean all) {
        Map<LocalDate, List<Day>> byMonday = new LinkedHashMap<>();
        for (Day d : days) {
            byMonday.computeIfAbsent(d.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    m -> new ArrayList<>()).add(d);
        }
        Map<LocalDate, BigDecimal> targets = all ? weeklyTargets(byMonday.keySet().iterator().next(),
                p.to()) : Map.of();

        List<WeekPart> weeks = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Day>> e : byMonday.entrySet()) {
            LocalDate monday = e.getKey();
            List<Day> inside = e.getValue();
            boolean whole = inside.size() == 7;
            long count = inside.stream().mapToLong(Day::transactionCount).sum();
            BigDecimal quotation = inside.stream().map(Day::quotationTotal).reduce(ZERO, BigDecimal::add);
            BigDecimal banked = inside.stream().map(Day::bankedTotal).reduce(ZERO, BigDecimal::add);
            BigDecimal target = all ? targets.get(monday) : null;
            Boolean met = target != null && whole ? quotation.compareTo(target) >= 0 : null;
            weeks.add(new WeekPart(monday, inside.get(0).date(), inside.get(inside.size() - 1).date(), whole, count,
                    quotation, banked, target, met));
        }
        return weeks;
    }

    /** Monday -> the weekly target in force that Monday (as v_week_totals). */
    private Map<LocalDate, BigDecimal> weeklyTargets(LocalDate firstMonday, LocalDate last) {
        Map<LocalDate, BigDecimal> targets = new HashMap<>();
        jdbc.query("""
                select m::date as monday,
                       (select s.weekly_quotation_target from sales_targets s
                        where s.effective_from <= m::date
                        order by s.effective_from desc limit 1) as weekly_target
                from generate_series(cast(? as date), cast(? as date), interval '7 days') m
                """, (rs, n) -> targets.put(rs.getObject("monday", LocalDate.class),
                rs.getBigDecimal("weekly_target")), firstMonday, last);
        return targets;
    }

    // ---------- GET /api/reports/range/consultants ----------

    @Transactional(readOnly = true)
    public ConsultantsResponse consultants(AppUserPrincipal caller, String rawFrom, String rawTo) {
        Period p = period(rawFrom, rawTo);
        boolean all = seesEverything(caller);
        String own = all ? "" : " and tt.consultant_id = ?";
        List<Object> args = new ArrayList<>(List.of(p.from(), p.to()));
        if (!all) {
            args.add(ownConsultantId(caller));
        }
        List<ConsultantRow> rows = jdbc.query("""
                select tt.consultant_id, c.consultant_code, c.full_name,
                       count(*)                                as transaction_count,
                       count(distinct tt.client_id)            as client_count,
                       sum(tt.quotation_total)::numeric(14, 2) as quotation_total,
                       sum(tt.banked_total)::numeric(14, 2)    as banked_total
                from v_transaction_totals tt
                         left join consultants c on c.id = tt.consultant_id
                where tt.transaction_date between ? and ?""" + own + """

                group by tt.consultant_id, c.consultant_code, c.full_name
                order by tt.consultant_id is null, sum(tt.quotation_total) desc, c.consultant_code
                """, (rs, n) -> {
            Long id = rs.getObject("consultant_id", Long.class);
            return new ConsultantRow(id == null ? null
                    : new ConsultantReportResponse.Consultant(id, rs.getString("consultant_code"),
                            rs.getString("full_name")),
                    rs.getLong("transaction_count"), rs.getLong("client_count"),
                    rs.getBigDecimal("quotation_total"), rs.getBigDecimal("banked_total"));
        }, args.toArray());
        return new ConsultantsResponse(all ? ReportService.ALL : ReportService.OWN, p.from(), p.to(), rows);
    }

    // ---------- GET /api/reports/range/products ----------

    /**
     * requestedConsultantId: as in ReportService.products (ignored for a
     * CONSULTANT).
     */
    @Transactional(readOnly = true)
    public ProductsResponse products(AppUserPrincipal caller, String rawFrom, String rawTo,
            Long requestedConsultantId) {
        Period p = period(rawFrom, rawTo);
        boolean all = seesEverything(caller);
        Long shown;
        Long filter;
        if (all) {
            if (requestedConsultantId != null && !consultantExists(requestedConsultantId)) {
                throw ApiException.notFound("consultant:not-found",
                        "Consultant " + requestedConsultantId + " does not exist.");
            }
            shown = requestedConsultantId;
            filter = requestedConsultantId;
        } else {
            shown = caller.consultantId();
            filter = ownConsultantId(caller);
        }
        List<Object> args = new ArrayList<>(List.of(p.from(), p.to()));
        if (filter != null) {
            args.add(filter);
        }
        List<ProductRow> rows = jdbc.query("""
                select p.id, p.code, p.name,
                       count(x.product_id)                     as line_count,
                       coalesce(sum(x.amount), 0)::numeric(14, 2) as total_amount
                from products p
                         left join (select l.product_id, l.amount
                                    from client_transactions t
                                             join client_transaction_lines l on l.transaction_id = t.id
                                    where t.superseded_by_import_job_id is null
                                      and t.transaction_date between ? and ?""" + (filter == null ? ""
                : " and t.consultant_id = ?") + """
                        ) x on x.product_id = p.id
                        where p.active or x.product_id is not null
                        group by p.id, p.code, p.name, p.display_order
                        order by p.display_order, p.code
                        """, (rs, n) -> new ProductRow(
                        new ProductReportResponse.Product(rs.getLong("id"), rs.getString("code"), rs.getString("name")),
                        rs.getLong("line_count"), rs.getBigDecimal("total_amount")),
                args.toArray());
        return new ProductsResponse(all ? ReportService.ALL : ReportService.OWN, p.from(), p.to(), shown, rows);
    }

    // ---------- GET /api/reports/range/budget-structures ----------

    /**
     * MANAGER and ADMIN only - checked in the controller. Same grouping as
     * v_week_budget_by_structure.
     */
    @Transactional(readOnly = true)
    public BudgetResponse budgetStructures(String rawFrom, String rawTo) {
        Period p = period(rawFrom, rawTo);
        List<BudgetRow> rows = jdbc.query(
                """
                        select b.budget_structure, count(*) as entry_count,
                               sum(b.loan_amount)::numeric(14, 2) as loan_amount_total
                        from (select regexp_replace(btrim(t.primary_structure_budget_list), '\\s+', ' ', 'g') as budget_structure,
                                     coalesce(t.primary_structure_loan_amount, 0) as loan_amount
                              from client_transactions t
                              where t.superseded_by_import_job_id is null and t.transaction_date between ? and ?
                                and t.primary_structure_budget_list is not null and btrim(t.primary_structure_budget_list) <> ''
                              union all
                              select regexp_replace(btrim(t.secondary_structure_budget_list), '\\s+', ' ', 'g'),
                                     coalesce(t.secondary_structure_loan_amount, 0)
                              from client_transactions t
                              where t.superseded_by_import_job_id is null and t.transaction_date between ? and ?
                                and t.secondary_structure_budget_list is not null
                                and btrim(t.secondary_structure_budget_list) <> '') b
                        group by b.budget_structure
                        order by b.budget_structure
                        """,
                (rs, n) -> new BudgetRow(rs.getString("budget_structure"), rs.getLong("entry_count"),
                        rs.getBigDecimal("loan_amount_total")),
                p.from(), p.to(), p.from(), p.to());
        return new BudgetResponse(p.from(), p.to(), rows);
    }

    // ---------- rules ----------

    /**
     * from and to as yyyy-mm-dd, from not after to, at most {@link #MAX_DAYS}
     * days. Anything else is a 400 report:invalid-period.
     */
    static Period period(String rawFrom, String rawTo) {
        LocalDate from = date(rawFrom, "from");
        LocalDate to = date(rawTo, "to");
        if (to.isBefore(from)) {
            throw invalid("to (" + to + ") is before from (" + from + ").");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw invalid("A period can be at most " + MAX_DAYS + " days; this one is " + days + ".");
        }
        return new Period(from, to);
    }

    private static LocalDate date(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            throw invalid(name + " is required, written as yyyy-mm-dd, for example 2026-09-01.");
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw invalid(name + " must be a date written as yyyy-mm-dd, for example 2026-09-01.");
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "report:invalid-period", "Invalid period", detail);
    }

    private static boolean seesEverything(AppUserPrincipal caller) {
        return ClientQueryService.seesAllClients(caller);
    }

    private static long ownConsultantId(AppUserPrincipal caller) {
        return caller.consultantId() == null ? NO_CONSULTANT : caller.consultantId();
    }

    private boolean consultantExists(long consultantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from consultants where id = ?)", Boolean.class, consultantId));
    }
}
