package za.co.mafori.creditadvisory.sale;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.client.ClientQueryService;
import za.co.mafori.creditadvisory.common.web.ApiException;

/**
 * The Sales history page (docs/api-sales.md section 3): every sale in a date
 * range, newest first,
 * a page at a time, with the totals of the whole range.
 *
 * <p>
 * The sales and the totals come from v_transaction_totals, the view the reports
 * use, so this page
 * and the Reports page always agree: typed-in and imported sales are both
 * listed, a typed-in sale
 * that a weekly import replaced is left out (it would count twice), and an
 * imported sale uses the
 * spreadsheet's own quotation and banked figures.
 *
 * <p>
 * A CONSULTANT only ever sees the sales recorded against their own consultant
 * record, whatever
 * consultantId they send. MANAGER and ADMIN see everything and may filter by
 * consultant.
 */
@Service
public class SaleHistoryService {

    static final int MAX_PAGE_SIZE = 100;

    /**
     * "My consultant id" for a consultant login without a consultant record:
     * matches nothing.
     */
    private static final long NO_CONSULTANT = -1L;

    /**
     * The sales in the range for the chosen consultant (or all), for a
     * v_transaction_totals tt.
     */
    private static final String IN_RANGE = """
            where tt.transaction_date between :from and :to
              and (cast(:consultantId as bigint) is null or tt.consultant_id = cast(:consultantId as bigint))
            """;

    private final NamedParameterJdbcTemplate named;

    public SaleHistoryService(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Transactional(readOnly = true)
    public SaleHistoryPage history(AppUserPrincipal caller, LocalDate from, LocalDate to, Long requestedConsultantId,
            int page, int size) {
        if (from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sale:invalid-date-range", "Invalid dates",
                    "'from' must be on or before 'to'.");
        }
        Long consultantId = chooseConsultant(caller, requestedConsultantId);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", from)
                .addValue("to", to)
                .addValue("consultantId", consultantId, Types.BIGINT)
                .addValue("limit", safeSize)
                .addValue("offset", (long) safePage * safeSize);

        Totals totals = named.queryForObject("""
                select count(*),
                       coalesce(sum(tt.quotation_total), 0)::numeric(14, 2),
                       coalesce(sum(tt.banked_total), 0)::numeric(14, 2)
                from v_transaction_totals tt
                """ + IN_RANGE, params,
                (rs, n) -> new Totals(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));

        List<Sale> sales = named.query("""
                select tt.transaction_id, tt.client_id, tt.consultant_id, tt.transaction_date, w.week_starting,
                       tt.day_of_week, tt.quotation_total, tt.banked_total, t.import_job_id is null,
                       c.first_name, c.last_name, k.full_name
                from v_transaction_totals tt
                         join client_transactions t on t.id = tt.transaction_id
                         join clients c on c.id = tt.client_id
                         left join consultants k on k.id = tt.consultant_id
                         left join reporting_weeks w on w.id = tt.reporting_week_id
                """ + IN_RANGE + """
                order by tt.transaction_date desc, tt.transaction_id desc
                limit :limit offset :offset
                """, params, (rs, n) -> new Sale(rs.getLong(1), rs.getLong(2), rs.getObject(3, Long.class),
                rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getObject(6, Integer.class),
                rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBoolean(9),
                rs.getString(10) + " " + rs.getString(11), rs.getString(12)));

        Map<Long, List<SavedSale.Line>> lines = linesOf(sales.stream().map(Sale::id).toList());
        List<SaleHistoryPage.Row> rows = sales.stream()
                .map(s -> new SaleHistoryPage.Row(s.id(), s.clientId(), s.consultantId(), s.transactionDate(),
                        s.weekStarting(), s.dayOfWeek(), lines.getOrDefault(s.id(), List.of()), s.quotationTotal(),
                        s.bankedTotal(), s.typedIn(), s.clientName(), s.consultantName()))
                .toList();
        int totalPages = (int) ((totals.count() + safeSize - 1) / safeSize);
        return new SaleHistoryPage(rows, safePage, safeSize, totals.count(), totalPages, totals.quotation(),
                totals.banked());
    }

    private record Totals(long count, BigDecimal quotation, BigDecimal banked) {
    }

    private record Sale(long id, long clientId, Long consultantId, LocalDate transactionDate, LocalDate weekStarting,
            Integer dayOfWeek, BigDecimal quotationTotal, BigDecimal bankedTotal, boolean typedIn, String clientName,
            String consultantName) {
    }

    /** Same scoping as the reports: a consultant gets their own sales only. */
    private Long chooseConsultant(AppUserPrincipal caller, Long requested) {
        if (!ClientQueryService.seesAllClients(caller)) {
            return caller.consultantId() == null ? NO_CONSULTANT : caller.consultantId();
        }
        if (requested != null) {
            Boolean exists = named.queryForObject("select exists (select 1 from consultants where id = :id)",
                    new MapSqlParameterSource("id", requested), Boolean.class);
            if (!Boolean.TRUE.equals(exists)) {
                throw ApiException.notFound("consultant:not-found", "Consultant " + requested + " does not exist.");
            }
        }
        return requested;
    }

    /** The product lines of these sales, in display order, in one query. */
    private Map<Long, List<SavedSale.Line>> linesOf(List<Long> saleIds) {
        Map<Long, List<SavedSale.Line>> bySale = new HashMap<>();
        if (saleIds.isEmpty()) {
            return bySale;
        }
        named.query("""
                select l.transaction_id, l.product_id, p.name, l.amount, l.counts_toward_quotation,
                       l.counts_toward_banked
                from client_transaction_lines l join products p on p.id = l.product_id
                where l.transaction_id in (:ids)
                order by p.display_order, p.id
                """, new MapSqlParameterSource("ids", saleIds), rs -> {
            bySale.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new SavedSale.Line(rs.getLong(2),
                    rs.getString(3), rs.getBigDecimal(4), rs.getBoolean(5), rs.getBoolean(6)));
        });
        return bySale;
    }
}
