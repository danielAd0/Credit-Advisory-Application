package za.co.mafori.creditadvisory.sale;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.client.ClientQueryService;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.consultant.ConsultantRepository;

/**
 * Reading back, changing and deleting one typed-in sale (docs/api-sales.md).
 *
 * A typed-in sale only stands in for the spreadsheet until its week is
 * imported, so it can be changed or deleted until then, and never after: from
 * then on the spreadsheet is the record, and a mistake is fixed there.
 * Deleting removes the row; the audit log keeps what it was.
 *
 * A CONSULTANT can change and delete only their own sales; MANAGER and ADMIN
 * anyone's. Someone else's sale looks missing (404), as clients do.
 */
@Service
public class SaleEditService {

    private final JdbcTemplate jdbc;
    private final ConsultantRepository consultants;
    private final AuditLogWriter audit;

    public SaleEditService(JdbcTemplate jdbc, ConsultantRepository consultants, AuditLogWriter audit) {
        this.jdbc = jdbc;
        this.consultants = consultants;
        this.audit = audit;
    }

    // ---------- GET /api/transactions/{id} ----------

    @Transactional(readOnly = true)
    public SaleDetail detail(AppUserPrincipal caller, long id) {
        visible(caller, id, false);
        return load(id);
    }

    // ---------- PATCH /api/transactions/{id} ----------

    @Transactional
    public SaleDetail edit(AppUserPrincipal caller, long id, EditSaleRequest request) {
        Row first = visible(caller, id, false);
        checkChangeable(first);

        LocalDate today = LocalDate.now(SaleService.BUSINESS_ZONE);
        if (request.transactionDate().isAfter(today)) {
            throw ApiException.unprocessable("sale:date-in-future", "Date in the future",
                    "The sale date cannot be after today (" + today + ").");
        }

        // Lock the sale's week and, if the date moves, the new week too, so an
        // import confirm cannot cross this change.
        Map<LocalDate, Long> weeks = lockOpenWeeks(mondayOf(first.transactionDate()),
                mondayOf(request.transactionDate()));
        Row sale = visible(caller, id, true);
        checkChangeable(sale);
        if (sale.version() != request.version() || !sale.transactionDate().equals(first.transactionDate())) {
            throw ApiException.staleVersion();
        }

        SaleDetail before = load(id);
        Long consultantId = chooseConsultant(caller, sale, request.consultantId());
        Map<Long, Flags> flags = productFlags(id, request.lines());
        checkStillOffered(request.employerId(), sale.employerId(), "employers", "Employer", "employer");
        checkStillOffered(request.paymentTermId(), sale.paymentTermId(), "payment_terms", "Payment term",
                "payment-term");
        checkStillOffered(request.structureId(), sale.structureId(), "structures", "Structure", "structure");

        List<String> changed = changes(before, request, consultantId);
        if (changed.isEmpty()) {
            return before;
        }

        int updated = jdbc.update("""
                update client_transactions
                set consultant_id = ?, transaction_date = ?, reporting_week_id = ?, day_of_week = ?,
                    employer_id = ?, structure_id = ?, payment_term_id = ?, amount_to_client = ?,
                    third_party_settlement = ?, primary_structure_budget_list = ?, primary_structure_loan_amount = ?,
                    secondary_structure_budget_list = ?, secondary_structure_loan_amount = ?, version = version + 1
                where id = ? and version = ?
                """, consultantId, request.transactionDate(), weeks.get(mondayOf(request.transactionDate())),
                request.transactionDate().getDayOfWeek().getValue(), request.employerId(), request.structureId(),
                request.paymentTermId(), request.amountToClient(), request.thirdPartySettlement(),
                request.primaryStructureBudgetList(), request.primaryStructureLoanAmount(),
                request.secondaryStructureBudgetList(), request.secondaryStructureLoanAmount(), id,
                request.version());
        if (updated == 0) {
            throw ApiException.staleVersion();
        }

        jdbc.update("delete from client_transaction_lines where transaction_id = ?", id);
        for (CaptureSaleRequest.Line line : request.lines()) {
            Flags f = flags.get(line.productId());
            jdbc.update("""
                    insert into client_transaction_lines (transaction_id, product_id, amount, counts_toward_quotation,
                        counts_toward_banked)
                    values (?, ?, ?, ?, ?)
                    """, id, line.productId(), line.amount(), f.quotation(), f.banked());
        }

        SaleDetail after = load(id);
        audit.record("SALE_EDITED", "TRANSACTION", id, meta("clientId", after.clientId(), "consultantId",
                after.consultantId(), "transactionDate", after.transactionDate().toString(), "changed", changed,
                "quotationTotalBefore", before.quotationTotal(), "quotationTotalAfter", after.quotationTotal(),
                "bankedTotalBefore", before.bankedTotal(), "bankedTotalAfter", after.bankedTotal()));
        return after;
    }

    // ---------- DELETE /api/transactions/{id}?version= ----------

    @Transactional
    public void delete(AppUserPrincipal caller, long id, long version) {
        Row first = visible(caller, id, false);
        checkChangeable(first);

        lockOpenWeeks(mondayOf(first.transactionDate()), mondayOf(first.transactionDate()));
        Row sale = visible(caller, id, true);
        checkChangeable(sale);
        if (sale.version() != version || !sale.transactionDate().equals(first.transactionDate())) {
            throw ApiException.staleVersion();
        }

        SaleDetail gone = load(id);
        // The lines go with it (ON DELETE CASCADE, V6).
        jdbc.update("delete from client_transactions where id = ?", id);
        audit.record("SALE_DELETED", "TRANSACTION", id, meta("clientId", gone.clientId(), "consultantId",
                gone.consultantId(), "transactionDate", gone.transactionDate().toString(), "products",
                gone.lines().size(), "quotationTotal", gone.quotationTotal(), "bankedTotal", gone.bankedTotal()));
    }

    // ---------- who and what ----------

    /** What the rules need from the row. */
    private record Row(long id, Long consultantId, LocalDate transactionDate, boolean typedIn, boolean replaced,
            long version, Long employerId, Long paymentTermId, Long structureId) {
    }

    private Optional<Row> row(long id, boolean lock) {
        return jdbc.query("""
                select id, consultant_id, transaction_date, import_job_id is null,
                       superseded_by_import_job_id is not null, version, employer_id, payment_term_id, structure_id
                from client_transactions
                where id = ?
                """ + (lock ? "for update" : ""),
                (rs, n) -> new Row(rs.getLong(1), rs.getObject(2, Long.class), rs.getObject(3, LocalDate.class),
                        rs.getBoolean(4), rs.getBoolean(5), rs.getLong(6), rs.getObject(7, Long.class),
                        rs.getObject(8, Long.class), rs.getObject(9, Long.class)),
                id).stream().findFirst();
    }

    /**
     * The sale, if the caller may see it; otherwise 404, as if it did not exist.
     */
    private Row visible(AppUserPrincipal caller, long id, boolean lock) {
        return row(id, lock)
                .filter(r -> ClientQueryService.seesAllClients(caller)
                        || (caller.consultantId() != null && caller.consultantId().equals(r.consultantId())))
                .orElseThrow(() -> ApiException.notFound("sale:not-found",
                        "Sale " + id + " does not exist, or you do not have access to it."));
    }

    private static void checkChangeable(Row sale) {
        if (!sale.typedIn()) {
            throw ApiException.conflict("sale:imported", "Imported sale",
                    "This sale came from a weekly spreadsheet. Fix it in the spreadsheet and import that week again.");
        }
        if (sale.replaced()) {
            throw ApiException.conflict("sale:replaced", "Sale replaced",
                    "That week's spreadsheet has been imported and now counts instead of this sale. Fix it in the "
                            + "spreadsheet and import that week again.");
        }
    }

    private static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * Finds or creates each week and locks it until the end of the
     * transaction, earliest first so two changes never wait on each other. A
     * week whose spreadsheet is imported takes no typed-in sales (same rule as
     * POST).
     */
    private Map<LocalDate, Long> lockOpenWeeks(LocalDate one, LocalDate other) {
        Map<LocalDate, Long> ids = new HashMap<>();
        for (LocalDate monday : new TreeSet<>(List.of(one, other))) {
            jdbc.update("insert into reporting_weeks (week_starting) values (?) on conflict (week_starting) do nothing",
                    monday);
            Map<String, Object> week = jdbc.queryForMap(
                    "select id, current_import_job_id from reporting_weeks where week_starting = ? for update",
                    monday);
            if (week.get("current_import_job_id") != null) {
                throw ApiException.conflict("sale:week-already-imported", "Week already imported",
                        "The spreadsheet for the week starting " + monday + " has already been imported, so typed-in "
                                + "sales for that week can no longer change. Fix it in the spreadsheet instead.");
            }
            ids.put(monday, ((Number) week.get("id")).longValue());
        }
        return ids;
    }

    /**
     * A CONSULTANT keeps the sale. A MANAGER or ADMIN may move it to another
     * ACTIVE consultant; null or the same consultant keeps it as it is, even if
     * that consultant has since left.
     */
    private Long chooseConsultant(AppUserPrincipal caller, Row sale, Long requested) {
        if (requested == null || requested.equals(sale.consultantId())) {
            return sale.consultantId();
        }
        if (!ClientQueryService.seesAllClients(caller)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "sale:cannot-capture-for-others", "Not allowed",
                    "Consultants can only capture their own sales.");
        }
        long id = requested;
        Consultant consultant = consultants.findById(id).orElseThrow(() -> ApiException.notFound(
                "consultant:not-found", "Consultant " + id + " does not exist."));
        if (!consultant.getStatus().isActive()) {
            throw ApiException.unprocessable("sale:consultant-not-active", "Consultant not active",
                    "Sales can only be moved to an ACTIVE consultant.");
        }
        return consultant.getId();
    }

    /** Whether a line counts toward the quotation and toward banked. */
    private record Flags(boolean quotation, boolean banked) {
    }

    private record Product(boolean active, Flags flags) {
    }

    /**
     * A product already on the sale keeps the flags it was saved with (V6), even
     * if it has since been changed or deactivated. A product added now must be
     * active and takes its flags as they are today, as in POST.
     */
    private Map<Long, Flags> productFlags(long saleId, List<CaptureSaleRequest.Line> lines) {
        Set<Long> seen = new HashSet<>();
        for (CaptureSaleRequest.Line line : lines) {
            if (!seen.add(line.productId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "sale:duplicate-product", "Product twice",
                        "Each product can be on a sale only once. Add the amounts together instead.");
            }
        }
        Map<Long, Flags> saved = new HashMap<>();
        jdbc.query("""
                select product_id, counts_toward_quotation, counts_toward_banked
                from client_transaction_lines where transaction_id = ?
                """, rs -> {
            saved.put(rs.getLong(1), new Flags(rs.getBoolean(2), rs.getBoolean(3)));
        }, saleId);

        Map<Long, Flags> flags = new HashMap<>();
        for (Long productId : seen) {
            Flags kept = saved.get(productId);
            if (kept != null) {
                flags.put(productId, kept);
                continue;
            }
            List<Product> rows = jdbc.query(
                    "select active, counts_toward_quotation, counts_toward_banked from products where id = ?",
                    (rs, n) -> new Product(rs.getBoolean(1), new Flags(rs.getBoolean(2), rs.getBoolean(3))),
                    productId);
            if (rows.isEmpty()) {
                throw ApiException.notFound("product:not-found", "Product " + productId + " does not exist.");
            }
            if (!rows.get(0).active()) {
                throw ApiException.unprocessable("sale:product-not-active", "Product not active",
                        "One of the products has been deactivated. Remove it from the sale.");
            }
            flags.put(productId, rows.get(0).flags());
        }
        return flags;
    }

    /**
     * An employer, payment term or structure must exist and be active, unless
     * it is the one the sale already has.
     *
     * @param table one of the three table names above, never user input
     */
    private void checkStillOffered(Long chosen, Long current, String table, String label, String code) {
        if (chosen == null || chosen.equals(current)) {
            return;
        }
        List<Boolean> active = jdbc.queryForList("select active from " + table + " where id = ?", Boolean.class,
                chosen);
        if (active.isEmpty()) {
            throw ApiException.notFound(code + ":not-found", label + " " + chosen + " does not exist.");
        }
        if (!Boolean.TRUE.equals(active.get(0))) {
            throw ApiException.unprocessable("sale:" + code + "-not-active", label + " not active",
                    "This " + label.toLowerCase() + " is no longer used. Choose another one.");
        }
    }

    /**
     * The names of the fields this edit changes, for the audit log; empty means
     * nothing to save.
     */
    private static List<String> changes(SaleDetail before, EditSaleRequest after, Long consultantId) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(before.consultantId(), consultantId)) {
            changed.add("consultantId");
        }
        if (!before.transactionDate().equals(after.transactionDate())) {
            changed.add("transactionDate");
        }
        if (!sameLines(before.lines(), after.lines())) {
            changed.add("lines");
        }
        if (!sameMoney(before.amountToClient(), after.amountToClient())) {
            changed.add("amountToClient");
        }
        if (!sameMoney(before.thirdPartySettlement(), after.thirdPartySettlement())) {
            changed.add("thirdPartySettlement");
        }
        if (!Objects.equals(before.paymentTermId(), after.paymentTermId())) {
            changed.add("paymentTermId");
        }
        if (!Objects.equals(before.employerId(), after.employerId())) {
            changed.add("employerId");
        }
        if (!Objects.equals(before.structureId(), after.structureId())) {
            changed.add("structureId");
        }
        if (!Objects.equals(before.primaryStructureBudgetList(), after.primaryStructureBudgetList())) {
            changed.add("primaryStructureBudgetList");
        }
        if (!sameMoney(before.primaryStructureLoanAmount(), after.primaryStructureLoanAmount())) {
            changed.add("primaryStructureLoanAmount");
        }
        if (!Objects.equals(before.secondaryStructureBudgetList(), after.secondaryStructureBudgetList())) {
            changed.add("secondaryStructureBudgetList");
        }
        if (!sameMoney(before.secondaryStructureLoanAmount(), after.secondaryStructureLoanAmount())) {
            changed.add("secondaryStructureLoanAmount");
        }
        return changed;
    }

    private static boolean sameMoney(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /** Same products with the same amounts, in any order. */
    private static boolean sameLines(List<SavedSale.Line> saved, List<CaptureSaleRequest.Line> wanted) {
        if (saved.size() != wanted.size()) {
            return false;
        }
        Map<Long, BigDecimal> amounts = new HashMap<>();
        for (SavedSale.Line line : saved) {
            amounts.put(line.productId(), line.amount());
        }
        for (CaptureSaleRequest.Line line : wanted) {
            if (!sameMoney(amounts.get(line.productId()), line.amount())) {
                return false;
            }
        }
        return true;
    }

    // ---------- reading it back ----------

    private SaleDetail load(long id) {
        List<SavedSale.Line> lines = jdbc.query("""
                select l.product_id, p.name, l.amount, l.counts_toward_quotation, l.counts_toward_banked
                from client_transaction_lines l join products p on p.id = l.product_id
                where l.transaction_id = ?
                order by p.display_order, p.id
                """, (rs, n) -> new SavedSale.Line(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                rs.getBoolean(4), rs.getBoolean(5)), id);
        BigDecimal quotation = BigDecimal.ZERO.setScale(2);
        BigDecimal banked = BigDecimal.ZERO.setScale(2);
        for (SavedSale.Line line : lines) {
            if (line.countsTowardQuotation()) {
                quotation = quotation.add(line.amount());
            }
            if (line.countsTowardBanked()) {
                banked = banked.add(line.amount());
            }
        }
        BigDecimal quotationTotal = quotation;
        BigDecimal bankedTotal = banked;
        return jdbc.queryForObject("""
                select t.id, t.client_id, t.consultant_id, t.transaction_date, w.week_starting, t.day_of_week,
                       t.amount_to_client, t.third_party_settlement, t.payment_term_id, t.employer_id, t.structure_id,
                       t.primary_structure_budget_list, t.primary_structure_loan_amount,
                       t.secondary_structure_budget_list, t.secondary_structure_loan_amount,
                       t.import_job_id is null, t.superseded_by_import_job_id is not null, t.version
                from client_transactions t left join reporting_weeks w on w.id = t.reporting_week_id
                where t.id = ?
                """, (rs, n) -> {
            boolean typedIn = rs.getBoolean(16);
            boolean replaced = rs.getBoolean(17);
            return new SaleDetail(rs.getLong(1), rs.getLong(2), rs.getObject(3, Long.class),
                    rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getObject(6, Integer.class),
                    lines, quotationTotal, bankedTotal, rs.getBigDecimal(7), rs.getBigDecimal(8),
                    rs.getObject(9, Long.class), rs.getObject(10, Long.class), rs.getObject(11, Long.class),
                    rs.getString(12), rs.getBigDecimal(13), rs.getString(14), rs.getBigDecimal(15), typedIn, replaced,
                    typedIn && !replaced, rs.getLong(18));
        }, id);
    }
}
