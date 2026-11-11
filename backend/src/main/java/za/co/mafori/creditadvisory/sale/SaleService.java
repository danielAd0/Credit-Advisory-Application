package za.co.mafori.creditadvisory.sale;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.client.Client;
import za.co.mafori.creditadvisory.client.ClientQueryService;
import za.co.mafori.creditadvisory.client.ClientRepository;
import za.co.mafori.creditadvisory.client.ClientService;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.consultant.ConsultantRepository;

/**
 * Typed-in sales (docs/api-sales.md). Written with SQL like the weekly import,
 * into the same client_transactions table, so the reports count both the same
 * way.
 */
@Service
public class SaleService {

    /** "Today" for the business: a sale cannot be dated after it. */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Johannesburg");

    private final JdbcTemplate jdbc;
    private final ClientRepository clients;
    private final ConsultantRepository consultants;
    private final AuditLogWriter audit;

    public SaleService(JdbcTemplate jdbc, ClientRepository clients, ConsultantRepository consultants,
            AuditLogWriter audit) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.consultants = consultants;
        this.audit = audit;
    }

    /**
     * The saved sale, and whether this call saved it (false: the key was used
     * before).
     */
    public record Outcome(SavedSale sale, boolean created) {
    }

    // ---------- the dropdowns ----------

    @Transactional(readOnly = true)
    public List<NamedOption> paymentTerms() {
        return jdbc.query("select id, name from payment_terms where active order by lower(name), id",
                (rs, n) -> new NamedOption(rs.getLong(1), rs.getString(2)));
    }

    @Transactional(readOnly = true)
    public List<NamedOption> structures() {
        return jdbc.query("select id, name from structures order by lower(name), id",
                (rs, n) -> new NamedOption(rs.getLong(1), rs.getString(2)));
    }

    // ---------- POST /api/transactions ----------

    @Transactional
    public Outcome capture(AppUserPrincipal caller, String rawKey, CaptureSaleRequest request) {
        UUID key = parseKey(rawKey);
        Optional<Long> earlier = saleWithKey(caller.userId(), key);
        if (earlier.isPresent()) {
            // A double click, or a retry after a lost answer: the sale is already saved.
            return new Outcome(load(earlier.get()), false);
        }

        Client client = clients.findWithCurrentConsultantById(request.clientId())
                .filter(c -> ClientService.isVisibleTo(c, caller))
                .orElseThrow(() -> ApiException.notFound("client:not-found",
                        "Client " + request.clientId() + " does not exist, or you do not have access to them."));
        Consultant consultant = chooseConsultant(caller, client, request.consultantId());

        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (request.transactionDate().isAfter(today)) {
            throw ApiException.unprocessable("sale:date-in-future", "Date in the future",
                    "The sale date cannot be after today (" + today + ").");
        }
        LocalDate monday = request.transactionDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long weekId = lockOpenWeek(monday);

        Map<Long, Product> products = activeProducts(request.lines());
        Long employerId = checkEmployer(request.employerId());
        Long paymentTermId = checkPaymentTerm(request.paymentTermId());
        Long structureId = checkStructure(request.structureId());

        List<Long> inserted = jdbc.queryForList("""
                insert into client_transactions (client_id, consultant_id, transaction_date, employer_id, structure_id,
                    payment_term_id, amount_to_client, third_party_settlement, primary_structure_budget_list,
                    primary_structure_loan_amount, secondary_structure_budget_list, secondary_structure_loan_amount,
                    created_by_user_id, reporting_week_id, day_of_week, capture_key)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (created_by_user_id, capture_key) where capture_key is not null do nothing
                returning id
                """, Long.class, client.getId(), consultant.getId(), request.transactionDate(), employerId,
                structureId, paymentTermId, request.amountToClient(), request.thirdPartySettlement(),
                request.primaryStructureBudgetList(), request.primaryStructureLoanAmount(),
                request.secondaryStructureBudgetList(), request.secondaryStructureLoanAmount(), caller.userId(), weekId,
                request.transactionDate().getDayOfWeek().getValue(), key);
        if (inserted.isEmpty()) {
            // The same key arrived twice at the same moment; the other request saved it.
            return new Outcome(load(saleWithKey(caller.userId(), key).orElseThrow()), false);
        }
        long saleId = inserted.get(0);

        for (CaptureSaleRequest.Line line : request.lines()) {
            Product p = products.get(line.productId());
            jdbc.update("""
                    insert into client_transaction_lines (transaction_id, product_id, amount, counts_toward_quotation,
                        counts_toward_banked)
                    values (?, ?, ?, ?, ?)
                    """, saleId, p.id(), line.amount(), p.countsTowardQuotation(), p.countsTowardBanked());
        }

        audit.record("SALE_CAPTURED", "TRANSACTION", saleId, meta("clientId", client.getId(), "consultantId",
                consultant.getId(), "transactionDate", request.transactionDate().toString(), "products",
                request.lines().size()));
        return new Outcome(load(saleId), true);
    }

    // ---------- rules ----------

    private static UUID parseKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sale:idempotency-key-required", "Key required",
                    "Send an Idempotency-Key header (a new UUID for each sale).");
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sale:invalid-idempotency-key", "Invalid key",
                    "The Idempotency-Key header must be a UUID.");
        }
    }

    private Optional<Long> saleWithKey(long userId, UUID key) {
        return jdbc.queryForList("select id from client_transactions where created_by_user_id = ? and capture_key = ?",
                Long.class, userId, key).stream().findFirst();
    }

    /**
     * A CONSULTANT's sales are always their own. A MANAGER or ADMIN chooses, or
     * the sale goes to the client's consultant.
     */
    private Consultant chooseConsultant(AppUserPrincipal caller, Client client, Long requestedId) {
        Long consultantId;
        if (ClientQueryService.seesAllClients(caller)) {
            consultantId = requestedId != null ? requestedId
                    : client.getCurrentConsultant() == null ? null : client.getCurrentConsultant().getId();
            if (consultantId == null) {
                throw ApiException.unprocessable("sale:consultant-required", "Consultant required",
                        "This client has no consultant. Choose the consultant the sale belongs to.");
            }
        } else {
            if (caller.consultantId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "sale:no-consultant-record", "Not allowed",
                        "Your login is not linked to a consultant record, so you cannot capture sales.");
            }
            if (requestedId != null && !requestedId.equals(caller.consultantId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "sale:cannot-capture-for-others", "Not allowed",
                        "Consultants can only capture their own sales.");
            }
            consultantId = caller.consultantId();
        }
        long id = consultantId;
        Consultant consultant = consultants.findById(id).orElseThrow(() -> ApiException.notFound(
                "consultant:not-found", "Consultant " + id + " does not exist."));
        if (!consultant.getStatus().isActive()) {
            throw ApiException.unprocessable("sale:consultant-not-active", "Consultant not active",
                    "Sales can only be captured for an ACTIVE consultant.");
        }
        return consultant;
    }

    /**
     * Finds or creates the week and locks it until the end of the transaction,
     * so a sale and that week's import confirm cannot cross. A week whose
     * spreadsheet is already imported takes no more typed-in sales: the
     * spreadsheet already holds them, so they would count twice.
     */
    private long lockOpenWeek(LocalDate monday) {
        jdbc.update("insert into reporting_weeks (week_starting) values (?) on conflict (week_starting) do nothing",
                monday);
        Map<String, Object> week = jdbc.queryForMap(
                "select id, current_import_job_id from reporting_weeks where week_starting = ? for update", monday);
        if (week.get("current_import_job_id") != null) {
            throw ApiException.conflict("sale:week-already-imported", "Week already imported",
                    "The spreadsheet for the week starting " + monday + " has already been imported, so this sale "
                            + "would be counted twice. Add it to that week's spreadsheet instead, and import it again.");
        }
        return ((Number) week.get("id")).longValue();
    }

    private record Product(long id, boolean active, boolean countsTowardQuotation, boolean countsTowardBanked) {
    }

    private Map<Long, Product> activeProducts(List<CaptureSaleRequest.Line> lines) {
        Set<Long> seen = new HashSet<>();
        for (CaptureSaleRequest.Line line : lines) {
            if (!seen.add(line.productId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "sale:duplicate-product", "Product twice",
                        "Each product can be on a sale only once. Add the amounts together instead.");
            }
        }
        Map<Long, Product> found = new HashMap<>();
        for (Long id : seen) {
            List<Product> rows = jdbc.query("""
                    select id, active, counts_toward_quotation, counts_toward_banked from products where id = ?
                    """, (rs, n) -> new Product(rs.getLong(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4)),
                    id);
            if (rows.isEmpty()) {
                throw ApiException.notFound("product:not-found", "Product " + id + " does not exist.");
            }
            if (!rows.get(0).active()) {
                throw ApiException.unprocessable("sale:product-not-active", "Product not active",
                        "One of the products has been deactivated. Remove it from the sale.");
            }
            found.put(id, rows.get(0));
        }
        return found;
    }

    private Long checkEmployer(Long id) {
        if (id == null) {
            return null;
        }
        List<Boolean> active = jdbc.queryForList("select active from employers where id = ?", Boolean.class, id);
        if (active.isEmpty()) {
            throw ApiException.notFound("employer:not-found", "Employer " + id + " does not exist.");
        }
        if (!Boolean.TRUE.equals(active.get(0))) {
            throw ApiException.unprocessable("sale:employer-not-active", "Employer not active",
                    "This employer has been deactivated. Choose another one.");
        }
        return id;
    }

    private Long checkPaymentTerm(Long id) {
        if (id == null) {
            return null;
        }
        List<Boolean> active = jdbc.queryForList("select active from payment_terms where id = ?", Boolean.class, id);
        if (active.isEmpty()) {
            throw ApiException.notFound("payment-term:not-found", "Payment term " + id + " does not exist.");
        }
        if (!Boolean.TRUE.equals(active.get(0))) {
            throw ApiException.unprocessable("sale:payment-term-not-active", "Payment term not active",
                    "This payment term is no longer used. Choose another one.");
        }
        return id;
    }

    private Long checkStructure(Long id) {
        if (id == null) {
            return null;
        }
        if (jdbc.queryForList("select id from structures where id = ?", Long.class, id).isEmpty()) {
            throw ApiException.notFound("structure:not-found", "Structure " + id + " does not exist.");
        }
        return id;
    }

    // ---------- reading a sale back ----------

    private SavedSale load(long saleId) {
        List<SavedSale.Line> lines = jdbc.query("""
                select l.product_id, p.name, l.amount, l.counts_toward_quotation, l.counts_toward_banked
                from client_transaction_lines l join products p on p.id = l.product_id
                where l.transaction_id = ?
                order by p.display_order, p.id
                """, (rs, n) -> new SavedSale.Line(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                rs.getBoolean(4), rs.getBoolean(5)), saleId);
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
                       t.superseded_by_import_job_id is not null
                from client_transactions t join reporting_weeks w on w.id = t.reporting_week_id
                where t.id = ?
                """, (rs, n) -> {
            long consultant = rs.getLong(3);
            Long consultantId = rs.wasNull() ? null : consultant;
            return new SavedSale(rs.getLong(1), rs.getLong(2), consultantId, rs.getObject(4, LocalDate.class),
                    rs.getObject(5, LocalDate.class), rs.getInt(6), lines, quotationTotal, bankedTotal,
                    rs.getBoolean(7));
        }, saleId);
    }
}
