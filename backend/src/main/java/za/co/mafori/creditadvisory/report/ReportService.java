package za.co.mafori.creditadvisory.report;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.client.ClientQueryService;
import za.co.mafori.creditadvisory.common.web.ApiException;

/**
 * Weekly reports (FR-20, docs/api-reports.md). Read-only: every figure comes
 * from the V15 report views, which already leave out transactions replaced by
 * a later import of the same week and compare against the targets.
 *
 * <p>
 * Scoping (SRS section 4, "View operational reports"): MANAGER and ADMIN see
 * the whole company (scope "ALL"). A CONSULTANT only ever sees the sales
 * attributed to their own consultant record (scope "OWN") - never another
 * consultant's figures, never the sales without a consultant, never the
 * company targets.
 */
@Service
public class ReportService {

    static final String ALL = "ALL";
    static final String OWN = "OWN";

    private static final int DAYS_IN_WEEK = 7;
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /**
     * Used as "my consultant id" for a consultant-role user without a consultant
     * record. No consultant has this id, so every query finds no sales.
     */
    private static final long NO_CONSULTANT = -1L;

    /**
     * A week shows in the reports once its spreadsheet is imported, or as soon as
     * a sale is typed in for it (V21: managers see the week as it happens). For
     * a reporting_weeks row called w. Same rule as the views v_week_totals and
     * v_week_daily_summary.
     */
    private static final String WEEK_HAS_SALES = """
            (w.current_import_job_id is not null
             or exists (select 1 from client_transactions x
                        where x.reporting_week_id = w.id and x.import_job_id is null
                          and x.superseded_by_import_job_id is null))
            """;

    private final JdbcTemplate jdbc;

    public ReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- GET /api/reports/weeks ----------

    @Transactional(readOnly = true)
    public WeekListResponse weeks(AppUserPrincipal caller) {
        if (seesEverything(caller)) {
            return new WeekListResponse(ALL, jdbc.query("""
                    select t.week_starting, t.transaction_count, t.quotation_total, t.banked_total
                    from v_week_totals t
                    order by t.week_starting desc
                    """, (rs, n) -> new WeekListResponse.Week(
                    rs.getObject("week_starting", LocalDate.class),
                    rs.getLong("transaction_count"),
                    rs.getBigDecimal("quotation_total"),
                    rs.getBigDecimal("banked_total"))));
        }
        return new WeekListResponse(OWN, jdbc.query("""
                select w.week_starting,
                       count(tt.transaction_id)                             as transaction_count,
                       coalesce(sum(tt.quotation_total), 0)::numeric(14, 2) as quotation_total,
                       coalesce(sum(tt.banked_total), 0)::numeric(14, 2)    as banked_total
                from reporting_weeks w
                         left join v_transaction_totals tt
                                   on tt.reporting_week_id = w.id and tt.consultant_id = ?
                where """ + WEEK_HAS_SALES + """
                group by w.week_starting
                order by w.week_starting desc
                """, (rs, n) -> new WeekListResponse.Week(
                rs.getObject("week_starting", LocalDate.class),
                rs.getLong("transaction_count"),
                rs.getBigDecimal("quotation_total"),
                rs.getBigDecimal("banked_total")), ownConsultantId(caller)));
    }

    // ---------- GET /api/reports/weeks/{weekStarting}/summary ----------

    @Transactional(readOnly = true)
    public WeekSummaryResponse summary(AppUserPrincipal caller, String rawWeekStarting) {
        LocalDate monday = parseWeekStarting(rawWeekStarting);
        long weekId = findWeekWithSales(monday);

        if (seesEverything(caller)) {
            List<WeekSummaryResponse.Day> days = jdbc.query("""
                    select day_of_week, transaction_count, client_count, quotation_total, banked_total,
                           daily_quotation_target, target_met
                    from v_week_daily_summary
                    where reporting_week_id = ?
                    order by day_of_week
                    """, (rs, n) -> new WeekSummaryResponse.Day(
                    rs.getInt("day_of_week"),
                    monday.plusDays(rs.getInt("day_of_week") - 1L),
                    rs.getLong("transaction_count"),
                    rs.getLong("client_count"),
                    rs.getBigDecimal("quotation_total"),
                    rs.getBigDecimal("banked_total"),
                    rs.getBigDecimal("daily_quotation_target"),
                    rs.getObject("target_met", Boolean.class)), weekId);

            WeekSummaryResponse.Week week = jdbc.queryForObject("""
                    select transaction_count, client_count, unattributed_count, quotation_total, banked_total,
                           weekly_quotation_target, target_met
                    from v_week_totals
                    where reporting_week_id = ?
                    """, (rs, n) -> new WeekSummaryResponse.Week(
                    rs.getLong("transaction_count"),
                    rs.getLong("client_count"),
                    rs.getLong("unattributed_count"),
                    rs.getBigDecimal("quotation_total"),
                    rs.getBigDecimal("banked_total"),
                    rs.getBigDecimal("weekly_quotation_target"),
                    rs.getObject("target_met", Boolean.class)), weekId);

            return new WeekSummaryResponse(ALL, monday, monday.plusDays(6), days, week);
        }

        long own = ownConsultantId(caller);
        Map<Integer, WeekSummaryResponse.Day> found = new HashMap<>();
        jdbc.query("""
                select tt.day_of_week,
                       count(*)                                as transaction_count,
                       count(distinct tt.client_id)            as client_count,
                       sum(tt.quotation_total)::numeric(14, 2) as quotation_total,
                       sum(tt.banked_total)::numeric(14, 2)    as banked_total
                from v_transaction_totals tt
                where tt.reporting_week_id = ? and tt.consultant_id = ?
                group by tt.day_of_week
                """, (rs, n) -> new WeekSummaryResponse.Day(
                rs.getInt("day_of_week"),
                monday.plusDays(rs.getInt("day_of_week") - 1L),
                rs.getLong("transaction_count"),
                rs.getLong("client_count"),
                rs.getBigDecimal("quotation_total"),
                rs.getBigDecimal("banked_total"),
                null,
                null), weekId, own)
                .forEach(day -> found.put(day.dayOfWeek(), day));
        List<WeekSummaryResponse.Day> days = sevenDays(found,
                d -> new WeekSummaryResponse.Day(d, monday.plusDays(d - 1L), 0, 0, ZERO, ZERO, null, null));

        WeekSummaryResponse.Week week = jdbc.queryForObject("""
                select count(*)                                             as transaction_count,
                       count(distinct tt.client_id)                         as client_count,
                       coalesce(sum(tt.quotation_total), 0)::numeric(14, 2) as quotation_total,
                       coalesce(sum(tt.banked_total), 0)::numeric(14, 2)    as banked_total
                from v_transaction_totals tt
                where tt.reporting_week_id = ? and tt.consultant_id = ?
                """, (rs, n) -> new WeekSummaryResponse.Week(
                rs.getLong("transaction_count"),
                rs.getLong("client_count"),
                null,
                rs.getBigDecimal("quotation_total"),
                rs.getBigDecimal("banked_total"),
                null,
                null), weekId, own);

        return new WeekSummaryResponse(OWN, monday, monday.plusDays(6), days, week);
    }

    // ---------- GET /api/reports/weeks/{weekStarting}/consultants ----------

    /** One row of v_week_consultant_daily, before it is grouped per consultant. */
    private record ConsultantDayRow(Long consultantId, ConsultantReportResponse.Day day) {
    }

    @Transactional(readOnly = true)
    public ConsultantReportResponse consultants(AppUserPrincipal caller, String rawWeekStarting) {
        LocalDate monday = parseWeekStarting(rawWeekStarting);
        long weekId = findWeekWithSales(monday);

        boolean all = seesEverything(caller);
        // A consultant only gets their own row.
        String onlyOwn = all ? "" : " and tt.consultant_id = ?";
        Object[] args = all ? new Object[] { weekId } : new Object[] { weekId, ownConsultantId(caller) };

        Map<Long, Map<Integer, ConsultantReportResponse.Day>> daysByConsultant = new HashMap<>();
        List<ConsultantDayRow> dayRows = jdbc.query("""
                select tt.consultant_id, tt.day_of_week, tt.transaction_count, tt.quotation_total, tt.banked_total
                from v_week_consultant_daily tt
                where tt.reporting_week_id = ?""" + onlyOwn,
                (rs, n) -> new ConsultantDayRow(
                        rs.getObject("consultant_id", Long.class),
                        new ConsultantReportResponse.Day(
                                rs.getInt("day_of_week"),
                                rs.getLong("transaction_count"),
                                rs.getBigDecimal("quotation_total"),
                                rs.getBigDecimal("banked_total"))),
                args);
        for (ConsultantDayRow row : dayRows) {
            // HashMap accepts the null key: that is the "no consultant" row.
            daysByConsultant.computeIfAbsent(row.consultantId(), id -> new HashMap<>())
                    .put(row.day().dayOfWeek(), row.day());
        }

        List<ConsultantReportResponse.Row> rows = jdbc.query("""
                select tt.consultant_id, c.consultant_code, c.full_name,
                       count(*)                                as transaction_count,
                       count(distinct tt.client_id)            as client_count,
                       sum(tt.quotation_total)::numeric(14, 2) as quotation_total,
                       sum(tt.banked_total)::numeric(14, 2)    as banked_total
                from v_transaction_totals tt
                         left join consultants c on c.id = tt.consultant_id
                where tt.reporting_week_id = ?""" + onlyOwn + """

                group by tt.consultant_id, c.consultant_code, c.full_name
                order by tt.consultant_id is null, c.consultant_code
                """, (rs, n) -> {
            Long consultantId = rs.getObject("consultant_id", Long.class);
            ConsultantReportResponse.Consultant consultant = consultantId == null ? null
                    : new ConsultantReportResponse.Consultant(consultantId, rs.getString("consultant_code"),
                            rs.getString("full_name"));
            List<ConsultantReportResponse.Day> days = sevenDays(
                    daysByConsultant.getOrDefault(consultantId, Map.of()),
                    d -> new ConsultantReportResponse.Day(d, 0, ZERO, ZERO));
            ConsultantReportResponse.Week week = new ConsultantReportResponse.Week(
                    rs.getLong("transaction_count"),
                    rs.getLong("client_count"),
                    rs.getBigDecimal("quotation_total"),
                    rs.getBigDecimal("banked_total"));
            return new ConsultantReportResponse.Row(consultant, days, week);
        }, args);

        return new ConsultantReportResponse(all ? ALL : OWN, monday, rows);
    }

    // ---------- GET /api/reports/weeks/{weekStarting}/products ----------

    /**
     * One product on one day, before grouping. day is null for a product with no
     * sales that week.
     */
    private record ProductDayRow(ProductReportResponse.Product product, Integer day, long lineCount,
            BigDecimal totalAmount) {
    }

    /**
     * @param requestedConsultantId the ?consultantId= filter. Used for MANAGER /
     *                              ADMIN; ignored for a CONSULTANT, who always
     *                              gets their own figures.
     */
    @Transactional(readOnly = true)
    public ProductReportResponse products(AppUserPrincipal caller, String rawWeekStarting,
            Long requestedConsultantId) {
        LocalDate monday = parseWeekStarting(rawWeekStarting);
        long weekId = findWeekWithSales(monday);

        boolean all = seesEverything(caller);
        Long shownConsultantId;
        Long filterConsultantId;
        if (all) {
            if (requestedConsultantId != null && !consultantExists(requestedConsultantId)) {
                throw ApiException.notFound("consultant:not-found",
                        "Consultant " + requestedConsultantId + " does not exist.");
            }
            shownConsultantId = requestedConsultantId;
            filterConsultantId = requestedConsultantId;
        } else {
            shownConsultantId = caller.consultantId();
            filterConsultantId = ownConsultantId(caller);
        }

        String filter = filterConsultantId == null ? "" : " and pd.consultant_id = ?";
        Object[] args = filterConsultantId == null ? new Object[] { weekId }
                : new Object[] { weekId, filterConsultantId };

        List<ProductDayRow> rows = jdbc.query("""
                select p.id, p.code, p.name, pd.day_of_week,
                       sum(pd.line_count)                     as line_count,
                       sum(pd.total_amount)::numeric(14, 2)   as total_amount
                from products p
                         left join v_week_product_daily pd
                                   on pd.product_id = p.id and pd.reporting_week_id = ?""" + filter + """

                where p.active or pd.product_id is not null
                group by p.id, p.code, p.name, p.display_order, pd.day_of_week
                order by p.display_order, p.code, pd.day_of_week
                """, (rs, n) -> new ProductDayRow(
                new ProductReportResponse.Product(rs.getLong("id"), rs.getString("code"), rs.getString("name")),
                rs.getObject("day_of_week", Integer.class),
                rs.getLong("line_count"),
                rs.getBigDecimal("total_amount")), args);

        // Keep the products in display order, and collect each one's days.
        Map<Long, ProductReportResponse.Product> products = new LinkedHashMap<>();
        Map<Long, Map<Integer, ProductReportResponse.Day>> daysByProduct = new HashMap<>();
        for (ProductDayRow row : rows) {
            long productId = row.product().id();
            products.putIfAbsent(productId, row.product());
            Map<Integer, ProductReportResponse.Day> days = daysByProduct.computeIfAbsent(productId,
                    id -> new HashMap<>());
            if (row.day() != null) {
                days.put(row.day(), new ProductReportResponse.Day(row.day(), row.lineCount(), row.totalAmount()));
            }
        }

        List<ProductReportResponse.Row> result = new ArrayList<>();
        for (ProductReportResponse.Product product : products.values()) {
            List<ProductReportResponse.Day> days = sevenDays(daysByProduct.get(product.id()),
                    d -> new ProductReportResponse.Day(d, 0, ZERO));
            long lineCount = days.stream().mapToLong(ProductReportResponse.Day::lineCount).sum();
            BigDecimal totalAmount = days.stream().map(ProductReportResponse.Day::totalAmount)
                    .reduce(ZERO, BigDecimal::add);
            result.add(new ProductReportResponse.Row(product, days,
                    new ProductReportResponse.Week(lineCount, totalAmount)));
        }
        return new ProductReportResponse(all ? ALL : OWN, monday, shownConsultantId, result);
    }

    // ---------- GET /api/reports/weeks/{weekStarting}/budget-structures ----------

    /** One structure on one day, before grouping. */
    private record StructureDayRow(String budgetStructure, BudgetStructureReportResponse.Day day) {
    }

    /** MANAGER and ADMIN only - checked in the controller. */
    @Transactional(readOnly = true)
    public BudgetStructureReportResponse budgetStructures(String rawWeekStarting) {
        LocalDate monday = parseWeekStarting(rawWeekStarting);
        long weekId = findWeekWithSales(monday);

        List<StructureDayRow> rows = jdbc.query("""
                select budget_structure, day_of_week, entry_count, loan_amount_total
                from v_week_budget_by_structure
                where reporting_week_id = ?
                order by budget_structure, day_of_week
                """, (rs, n) -> new StructureDayRow(
                rs.getString("budget_structure"),
                new BudgetStructureReportResponse.Day(
                        rs.getInt("day_of_week"),
                        rs.getLong("entry_count"),
                        rs.getBigDecimal("loan_amount_total"))),
                weekId);

        // Alphabetical, as the query sorted them.
        Map<String, Map<Integer, BudgetStructureReportResponse.Day>> daysByStructure = new LinkedHashMap<>();
        for (StructureDayRow row : rows) {
            daysByStructure.computeIfAbsent(row.budgetStructure(), s -> new HashMap<>())
                    .put(row.day().dayOfWeek(), row.day());
        }

        List<BudgetStructureReportResponse.Row> result = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, BudgetStructureReportResponse.Day>> entry : daysByStructure
                .entrySet()) {
            List<BudgetStructureReportResponse.Day> days = sevenDays(entry.getValue(),
                    d -> new BudgetStructureReportResponse.Day(d, 0, ZERO));
            long entryCount = days.stream().mapToLong(BudgetStructureReportResponse.Day::entryCount).sum();
            BigDecimal loanAmountTotal = days.stream().map(BudgetStructureReportResponse.Day::loanAmountTotal)
                    .reduce(ZERO, BigDecimal::add);
            result.add(new BudgetStructureReportResponse.Row(entry.getKey(), days,
                    new BudgetStructureReportResponse.Week(entryCount, loanAmountTotal)));
        }
        return new BudgetStructureReportResponse(monday, result);
    }

    // ---------- rules ----------

    /** MANAGER / ADMIN see the whole company, exactly like the client list. */
    private static boolean seesEverything(AppUserPrincipal caller) {
        return ClientQueryService.seesAllClients(caller);
    }

    private static long ownConsultantId(AppUserPrincipal caller) {
        return caller.consultantId() == null ? NO_CONSULTANT : caller.consultantId();
    }

    /** "2026-09-14" -> that Monday. Anything else is a 400. */
    static LocalDate parseWeekStarting(String raw) {
        LocalDate date;
        try {
            date = LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "report:invalid-week", "Invalid week",
                    "weekStarting must be a date written as yyyy-mm-dd, for example 2026-09-14.");
        }
        if (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "report:invalid-week", "Invalid week",
                    "weekStarting must be a Monday; " + date + " is a "
                            + date.getDayOfWeek().toString().toLowerCase(Locale.ROOT) + ".");
        }
        return date;
    }

    /**
     * The reporting week's id, if it has imported or typed-in sales
     * (WEEK_HAS_SALES).
     */
    private long findWeekWithSales(LocalDate monday) {
        List<Long> ids = jdbc.queryForList("""
                select w.id from reporting_weeks w
                where w.week_starting = ? and """ + WEEK_HAS_SALES, Long.class, monday);
        if (ids.isEmpty()) {
            throw ApiException.notFound("report:week-not-found",
                    "No sales have been imported or typed in for the week starting " + monday + ".");
        }
        return ids.get(0);
    }

    private boolean consultantExists(long consultantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from consultants where id = ?)", Boolean.class, consultantId));
    }

    /**
     * Always seven entries, Monday (1) to Sunday (7); a missing day gets
     * empty(day).
     */
    private static <D> List<D> sevenDays(Map<Integer, D> found, IntFunction<D> empty) {
        List<D> days = new ArrayList<>(DAYS_IN_WEEK);
        for (int day = 1; day <= DAYS_IN_WEEK; day++) {
            D value = found == null ? null : found.get(day);
            days.add(value != null ? value : empty.apply(day));
        }
        return days;
    }
}
