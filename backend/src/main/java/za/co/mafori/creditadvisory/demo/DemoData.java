package za.co.mafori.creditadvisory.demo;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.client.ClientService;
import za.co.mafori.creditadvisory.client.CreateClientRequest;
import za.co.mafori.creditadvisory.client.PaymentMethod;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;
import za.co.mafori.creditadvisory.creditcase.AddCaseNoteRequest;
import za.co.mafori.creditadvisory.creditcase.CaseDetail;
import za.co.mafori.creditadvisory.creditcase.CaseStatus;
import za.co.mafori.creditadvisory.creditcase.CreateCaseRequest;
import za.co.mafori.creditadvisory.creditcase.CreditCaseService;
import za.co.mafori.creditadvisory.creditcase.UpdateCaseRequest;
import za.co.mafori.creditadvisory.employer.EmployerRequest;
import za.co.mafori.creditadvisory.employer.EmployerService;
import za.co.mafori.creditadvisory.product.CreateProductRequest;
import za.co.mafori.creditadvisory.product.ProductService;
import za.co.mafori.creditadvisory.sale.CaptureSaleRequest;
import za.co.mafori.creditadvisory.sale.NamedOption;
import za.co.mafori.creditadvisory.sale.SaleService;
import za.co.mafori.creditadvisory.target.SetTargetRequest;
import za.co.mafori.creditadvisory.target.TargetService;
import za.co.mafori.creditadvisory.transfer.TransferRequest;
import za.co.mafori.creditadvisory.transfer.TransferService;
import za.co.mafori.creditadvisory.user.ConsultantAccountResponse;
import za.co.mafori.creditadvisory.user.ConsultantAccountService;
import za.co.mafori.creditadvisory.user.CreateConsultantAccountRequest;
import za.co.mafori.creditadvisory.user.CreateUserRequest;
import za.co.mafori.creditadvisory.user.RoleName;
import za.co.mafori.creditadvisory.user.UserAccount;
import za.co.mafori.creditadvisory.user.UserAccountRepository;
import za.co.mafori.creditadvisory.user.UserService;

/**
 * Made-up data for demos and walkthroughs on your own machine: three
 * consultants with logins, a manager, 30 clients, about three months of
 * sales, a few credit cases, one transfer and a sales target. Every person,
 * ID number, email and phone number here is invented.
 *
 * A new install has no products or employers until the first import (V27), so
 * the demo adds the few products it sells and three made-up employers when
 * they are missing. A database that has them already keeps its own.
 *
 * Goes through the real services, so the rules, totals and audit log are the
 * same as when people use the app, and a change to those rules shows up here
 * as a failing DemoDataTest instead of quietly wrong demo data.
 *
 * Only exists with the local and test profiles (never in production). Started
 * by DemoDataSeeder when app.dev.demo-data=true.
 */
@Service
@Profile({ "local", "test" })
public class DemoData {

    /**
     * The first demo consultant's code: if it exists, the demo data is already
     * there.
     */
    public static final String FIRST_CODE = "DEMO01";
    public static final String MANAGER_EMAIL = "manager.demo@example.com";
    public static final int CLIENTS_PER_CONSULTANT = 10;

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Johannesburg");

    /** Code, name and login of each demo consultant. */
    private static final String[][] CONSULTANTS = {
            { FIRST_CODE, "Lerato Demo", "lerato.demo@example.com" },
            { "DEMO02", "Sipho Demo", "sipho.demo@example.com" },
            { "DEMO03", "Anele Demo", "anele.demo@example.com" } };

    private static final String[] FIRST_NAMES = { "Thabo", "Naledi", "Kagiso", "Palesa", "Mpho", "Zanele",
            "Tumelo", "Refilwe", "Bongani", "Ayanda", "Kabelo", "Nomsa", "Lwazi", "Dineo", "Tshepo" };
    private static final String[] LAST_NAMES = { "Sample", "Example", "Testman", "Placeholder", "Mockford",
            "Fakeley", "Dummyson", "Pretend", "Madeup", "Notreal" };

    /**
     * Products a demo sale picks from: code, name and fixed price (blank: a
     * made-up amount each time). Added only when the code is missing.
     */
    private static final String[][] PRODUCTS = { { "COMBO", "Combo", "" }, { "IVC", "IVC", "" },
            { "INSURANCE", "Insurance", "225.00" }, { "MAFORI_CARD", "Mafori Card", "175.00" },
            { "CREDIT_RESCUE", "Credit Rescue", "" }, { "SPEED_SERVICE", "Speed Service", "" },
            { "MAFORI_WELLNESS", "Mafori Wellness", "" } };

    /** Added only when there are fewer active employers than this. */
    private static final String[] EMPLOYERS = { "DEMO EMPLOYER ONE", "DEMO EMPLOYER TWO", "DEMO EMPLOYER THREE" };

    private static final String[] CASE_TITLES = { "Debt review", "Credit report dispute", "Payment arrangement",
            "Judgment removal", "Budget plan" };
    private static final String[] CASE_NOTES = { "Called the client and explained the next steps.",
            "Client sent the latest payslip.", "Waiting for the creditor to reply.",
            "Client asked for a call back next week." };

    /** What was made, for the log and the test. */
    public record Summary(int consultants, int clients, int sales, int creditCases) {
    }

    private final JdbcTemplate jdbc;
    private final ConsultantAccountService consultantAccounts;
    private final UserService userService;
    private final UserAccountRepository users;
    private final ClientService clients;
    private final SaleService sales;
    private final CreditCaseService cases;
    private final TransferService transfers;
    private final TargetService targets;
    private final ProductService products;
    private final EmployerService employerService;

    public DemoData(JdbcTemplate jdbc, ConsultantAccountService consultantAccounts, UserService userService,
            UserAccountRepository users, ClientService clients, SaleService sales, CreditCaseService cases,
            TransferService transfers, TargetService targets, ProductService products,
            EmployerService employerService) {
        this.jdbc = jdbc;
        this.consultantAccounts = consultantAccounts;
        this.userService = userService;
        this.users = users;
        this.clients = clients;
        this.sales = sales;
        this.cases = cases;
        this.transfers = transfers;
        this.targets = targets;
        this.products = products;
        this.employerService = employerService;
    }

    public boolean alreadyThere() {
        Integer found = jdbc.queryForObject("select count(*) from consultants where consultant_code = ?",
                Integer.class, FIRST_CODE);
        return found != null && found > 0;
    }

    /**
     * Makes the demo data, all or nothing. Empty if it is already there.
     *
     * @param password the password of every demo login (from
     *                 application-local.properties, never from the code)
     */
    @Transactional
    public Optional<Summary> seed(String password) {
        if (alreadyThere()) {
            return Optional.empty();
        }
        Random random = new Random(2026);

        // ---------- people ----------
        List<AppUserPrincipal> consultants = new ArrayList<>();
        for (String[] c : CONSULTANTS) {
            ConsultantAccountResponse made = consultantAccounts.create(
                    new CreateConsultantAccountRequest(c[0], c[1], c[2], password));
            consultants.add(readyToSignIn(made.user().id()));
        }
        long managerId = userService.create(
                new CreateUserRequest(MANAGER_EMAIL, password, Set.of(RoleName.MANAGER), null)).id();
        AppUserPrincipal manager = readyToSignIn(managerId);

        // ---------- what the forms offer (a new install has none: V27) ----------
        as(manager, () -> {
            addMissingCatalogue();
            return null;
        });
        Map<String, Long> productIds = new LinkedHashMap<>();
        Map<String, BigDecimal> fixedPrices = new LinkedHashMap<>();
        jdbc.query("select code, id, standard_price from products where active", rs -> {
            productIds.put(rs.getString(1), rs.getLong(2));
            fixedPrices.put(rs.getString(1), rs.getBigDecimal(3));
        });
        List<Long> employers = jdbc.queryForList(
                "select id from employers where active order by name limit 8", Long.class);
        List<Long> paymentTerms = sales.paymentTerms().stream().map(NamedOption::id).toList();
        List<Long> structures = sales.structures().stream().map(NamedOption::id).toList();
        Set<LocalDate> importedWeeks = new HashSet<>(jdbc.queryForList(
                "select week_starting from reporting_weeks where current_import_job_id is not null",
                LocalDate.class));

        // ---------- clients, their sales and cases ----------
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        int clientCount = 0;
        int saleCount = 0;
        int caseCount = 0;
        List<List<Long>> clientsOf = new ArrayList<>();
        for (AppUserPrincipal consultant : consultants) {
            List<Long> theirs = new ArrayList<>();
            clientsOf.add(theirs);
            for (int i = 0; i < CLIENTS_PER_CONSULTANT; i++) {
                int n = clientCount++;
                String first = FIRST_NAMES[n % FIRST_NAMES.length];
                String last = LAST_NAMES[(n * 7) % LAST_NAMES.length];
                boolean debitOrder = n % 5 == 4;
                Long employer = debitOrder || employers.isEmpty() ? null : employers.get(n % employers.size());
                CreateClientRequest request = new CreateClientRequest(fakeIdNumber(n), first, last,
                        (first + "." + last + n + "@example.com").toLowerCase(), "082555%04d".formatted(n),
                        null, null, employer, null, String.valueOf(10_000 + n), null,
                        debitOrder ? PaymentMethod.DEBIT_ORDER : null, null);
                long clientId = as(consultant, () -> clients.create(consultant, request)).id();
                theirs.add(clientId);

                int salesForClient = 1 + random.nextInt(4);
                for (int s = 0; s < salesForClient; s++) {
                    LocalDate date = weekday(today.minusDays(random.nextInt(90)), today);
                    if (importedWeeks.contains(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))) {
                        continue; // that week came from a spreadsheet: a typed-in sale would be refused
                    }
                    CaptureSaleRequest sale = new CaptureSaleRequest(clientId, null, date,
                            lines(random, productIds, fixedPrices), null, null, pick(random, paymentTerms),
                            employer, pick(random, structures), null, null, null, null);
                    as(consultant, () -> sales.capture(consultant, UUID.randomUUID().toString(), sale));
                    saleCount++;
                }

                if (i < 2) {
                    caseCount++;
                    String title = CASE_TITLES[n % CASE_TITLES.length];
                    CaseDetail opened = as(consultant,
                            () -> cases.create(consultant, new CreateCaseRequest(clientId, title)).orElseThrow());
                    for (int k = 0; k <= i; k++) {
                        String note = CASE_NOTES[(n + k) % CASE_NOTES.length];
                        as(consultant, () -> cases.addNote(consultant, opened.id(), new AddCaseNoteRequest(note)));
                    }
                    CaseStatus status = i == 0 ? CaseStatus.CLOSED : CaseStatus.IN_PROGRESS;
                    long version = as(consultant, () -> cases.view(consultant, opened.id()).orElseThrow()).version();
                    as(consultant, () -> cases.update(consultant, opened.id(),
                            new UpdateCaseRequest(title, status, version)));
                }
            }
        }

        // ---------- a handover, so assignment history is not empty ----------
        List<Long> first = clientsOf.get(0);
        TransferRequest handover = new TransferRequest(consultants.get(0).consultantId(),
                consultants.get(1).consultantId(), List.of(first.get(first.size() - 1)),
                "Demo data: consultant handover");
        as(manager, () -> transfers.transfer(manager.userId(), handover));

        // ---------- a target, only if nobody set one yet ----------
        Integer targetsSet = jdbc.queryForObject("select count(*) from sales_targets", Integer.class);
        if (targetsSet != null && targetsSet == 0) {
            LocalDate from = today.minusDays(91).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            as(manager, () -> targets.set(new SetTargetRequest(from, new BigDecimal("8000.00"),
                    new BigDecimal("40000.00"))));
        }

        return Optional.of(new Summary(consultants.size(), clientCount, saleCount, caseCount));
    }

    // ---------- helpers ----------

    /**
     * The demo products whose code is missing, and three employers when there are
     * fewer.
     */
    private void addMissingCatalogue() {
        for (int i = 0; i < PRODUCTS.length; i++) {
            String[] p = PRODUCTS[i];
            Integer found = jdbc.queryForObject("select count(*) from products where code = ?", Integer.class,
                    p[0]);
            if (found != null && found == 0) {
                products.create(new CreateProductRequest(p[0], p[1], p[2].isEmpty() ? null : new BigDecimal(p[2]),
                        true, true, 10 * (i + 1)));
            }
        }
        Integer active = jdbc.queryForObject("select count(*) from employers where active", Integer.class);
        if (active != null && active < EMPLOYERS.length) {
            for (String name : EMPLOYERS) {
                Integer found = jdbc.queryForObject("select count(*) from employers where upper(name) = ?",
                        Integer.class, name);
                if (found != null && found == 0) {
                    employerService.create(new EmployerRequest(name, "Demo", "Payroll"));
                }
            }
        }
    }

    /**
     * Demo logins skip "choose your own password": the password is a demo one
     * everybody on the team knows anyway.
     */
    private AppUserPrincipal readyToSignIn(long userId) {
        UserAccount user = users.findWithRolesAndConsultantById(userId).orElseThrow();
        user.passwordChangedByUser();
        return AppUserPrincipal.from(users.saveAndFlush(user));
    }

    /** Runs the action as this user, so the audit log shows who did it. */
    private static <T> T as(AppUserPrincipal who, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(who, null, who.authorities()));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /**
     * One or two different demo products that are active, at their fixed price
     * or a made-up amount.
     */
    private static List<CaptureSaleRequest.Line> lines(Random random, Map<String, Long> productIds,
            Map<String, BigDecimal> fixedPrices) {
        List<String> available = new ArrayList<>();
        for (String[] p : PRODUCTS) {
            if (productIds.containsKey(p[0])) {
                available.add(p[0]);
            }
        }
        if (available.isEmpty()) {
            throw new IllegalStateException("None of the demo products is active - activate one on the Products page");
        }
        List<CaptureSaleRequest.Line> lines = new ArrayList<>();
        int count = Math.min(1 + random.nextInt(2), available.size());
        while (lines.size() < count) {
            String code = available.remove(random.nextInt(available.size()));
            BigDecimal amount = fixedPrices.get(code) != null ? fixedPrices.get(code)
                    : BigDecimal.valueOf(500 + 50L * random.nextInt(110)).setScale(2);
            lines.add(new CaptureSaleRequest.Line(productIds.get(code), amount));
        }
        return lines;
    }

    /** A weekend date moves back to the Friday; never after today. */
    private static LocalDate weekday(LocalDate date, LocalDate today) {
        LocalDate d = date.isAfter(today) ? today : date;
        if (d.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return d.minusDays(1);
        }
        if (d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return d.minusDays(2);
        }
        return d;
    }

    private static Long pick(Random random, List<Long> ids) {
        return ids.isEmpty() ? null : ids.get(random.nextInt(ids.size()));
    }

    /**
     * A made-up SA ID number that passes the checksum: a birth date between 1965
     * and 1999 and a sequence from 7000, so it never matches the test numbers
     * (born 1 Jan 1990, sequence from 0). Never a real person's number on
     * purpose, and never used outside your own machine.
     */
    static String fakeIdNumber(int n) {
        LocalDate born = LocalDate.of(1965 + (n % 35), 1 + (n % 12), 1 + (n % 28));
        String withoutCheckDigit = "%02d%02d%02d%04d08".formatted(born.getYear() % 100, born.getMonthValue(),
                born.getDayOfMonth(), 7000 + n);
        for (int checkDigit = 0; checkDigit <= 9; checkDigit++) {
            String candidate = withoutCheckDigit + checkDigit;
            if (SaIdNumber.isValid(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No check digit found");
    }
}
