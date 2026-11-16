package za.co.mafori.creditadvisory.imports.weekly;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.common.idnumber.IdNumberProtector;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.PageResponse;
import za.co.mafori.creditadvisory.imports.excel.PoiWorkbookReader;
import za.co.mafori.creditadvisory.imports.excel.RawWorkbook;
import za.co.mafori.creditadvisory.imports.excel.WorkbookRejectedException;
import za.co.mafori.creditadvisory.imports.register.ImportDictionary;
import za.co.mafori.creditadvisory.imports.register.ImportDictionaryLoader;
import za.co.mafori.creditadvisory.imports.register.ParseIssue;
import za.co.mafori.creditadvisory.imports.register.ParseResult;
import za.co.mafori.creditadvisory.imports.register.ParsedRow;
import za.co.mafori.creditadvisory.imports.register.WeekStarting;
import za.co.mafori.creditadvisory.imports.register.WeeklyRegisterParser;
import za.co.mafori.creditadvisory.imports.report.ReconciliationService;
import za.co.mafori.creditadvisory.imports.report.ReportReconciler;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportStore.ConsultantRef;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportStore.ExistingClient;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportStore.Job;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.AssignmentPreview;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ConfirmResult;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ConsultantConflict;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ConsultantEmailToAdd;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ConsultantFound;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ConsultantList;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ConsultantRegistration;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ConsultantToRegister;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.DaySummary;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ImportListItem;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ImportResult;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ImportSummary;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.IssueFile;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.IssueView;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.StagedRowView;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.SimilarConsultant;
import za.co.mafori.creditadvisory.consultant.ConsultantCodeGenerator;

@Service
public class WeeklyImportService {

    static final Duration PREVIEW_LIFETIME = Duration.ofHours(24);
    static final String POSSIBLE_DUPLICATE_SALE = "row:possible-duplicate-sale";
    private static final int MAX_PAGE_SIZE = 200;

    private final PoiWorkbookReader reader;
    private final WeeklyRegisterParser parser;
    private final ImportDictionaryLoader dictionaryLoader;
    private final IdNumberProtector protector;
    private final WeeklyImportStore store;
    private final AuditLogWriter audit;
    private final ReconciliationService reconciliation;
    private final ConsultantCodeGenerator codes;
    private final ProductDiscovery productDiscovery;
    private final Clock clock = Clock.systemUTC();

    public WeeklyImportService(PoiWorkbookReader reader, WeeklyRegisterParser parser,
            ImportDictionaryLoader dictionaryLoader, IdNumberProtector protector, WeeklyImportStore store,
            AuditLogWriter audit, ReconciliationService reconciliation, ConsultantCodeGenerator codes,
            ProductDiscovery productDiscovery) {
        this.reader = reader;
        this.parser = parser;
        this.dictionaryLoader = dictionaryLoader;
        this.protector = protector;
        this.store = store;
        this.audit = audit;
        this.reconciliation = reconciliation;
        this.codes = codes;
        this.productDiscovery = productDiscovery;
    }

    @Transactional
    public ImportSummary preview(long userId, byte[] bytes, String fileName, LocalDate weekStarting) {
        String name = fileName == null || fileName.isBlank() ? "upload.xlsx" : fileName.strip();
        LocalDate week = weekStarting != null ? weekStarting
                : WeekStarting.guessFromFileName(name).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "import:week-required", "Week required",
                        "Choose the Monday of the week this register is for (it could not be read from the file name)."));
        if (week.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "import:week-not-monday", "Week must start on a Monday",
                    week + " is not a Monday.");
        }

        RawWorkbook workbook;
        try {
            workbook = reader.read(bytes, name);
        } catch (WorkbookRejectedException e) {
            HttpStatus status = "file:too-large".equals(e.code()) ? HttpStatus.CONTENT_TOO_LARGE
                    : HttpStatus.BAD_REQUEST;
            throw new ApiException(status, e.code(), "File not accepted", e.getMessage());
        }
        String digest = sha256(bytes);
        store.committedJobWithDigest(digest).ifPresent(id -> {
            throw ApiException.conflict("import:duplicate-file", "Already imported",
                    "This exact file was already imported (import " + id + ").");
        });

        ImportDictionary dictionary = dictionaryLoader.load();
        // The product list comes from the sheet's own columns (V27).
        List<String> newProducts = productDiscovery.createMissing(workbook, dictionary);
        if (!newProducts.isEmpty()) {
            dictionary = dictionaryLoader.load();
        }
        ParseResult parsed = parser.parse(workbook, week, dictionary);
        Map<String, ConsultantRef> byName = store.consultantsByName();
        List<WeeklyImportStore.SheetConsultant> sheetConsultants = sheetConsultantsOf(parsed.rows());
        Map<String, ConsultantRef> consultants = rowConsultants(withPlan(byName,
                planFor(sheetConsultants, byName)), sheetConsultants);

        List<Staged> staged = new ArrayList<>();
        List<ParseIssue> extraIssues = new ArrayList<>();
        if (!newProducts.isEmpty()) {
            extraIssues.add(new ParseIssue(ParseIssue.Severity.INFO, "lookup:new-product", null, null, "PRODUCT",
                    newProducts.size() + " product(s) were added from the column headers: "
                            + String.join(", ", newProducts) + ". Check whether each counts toward quotation and"
                            + " banked on the Products page."));
        }
        Set<String> seenHashes = new HashSet<>();
        Map<String, ParsedRow> seenSales = new HashMap<>();
        int newCount = 0;
        int unchanged = 0;
        int duplicates = 0;
        int invalid = 0;
        for (ParsedRow row : parsed.rows()) {
            if (row.rejected()) {
                staged.add(new Staged(row, "INVALID", null, null, null, false, false));
                invalid++;
                continue;
            }
            byte[] hash = protector.lookupHash(row.idNumber());
            String hashKey = HexFormat.of().formatHex(hash);
            ConsultantRef consultant = row.consultantName() == null ? null
                    : consultants.get(WeeklyImportStore.key(row.consultantName()));
            ParsedRow sameSale = seenSales.putIfAbsent(saleKey(hashKey, row), row);
            if (sameSale != null) {
                extraIssues.add(rowIssue(row, "WARNING", POSSIBLE_DUPLICATE_SALE, "ID NUMBER",
                        "Same client, date, consultant and amounts as " + sameSale.sheetName() + " row "
                                + sameSale.rowNumber() + ". It is imported as a separate sale: check the workbook "
                                + "in case it was entered twice."));
            }
            if (!seenHashes.add(hashKey)) {
                staged.add(new Staged(row, "DUPLICATE", hash, null, null, false, false));
                duplicates++;
                continue;
            }
            var existing = store.findClientByHash(hash);
            if (existing.isPresent()) {
                ExistingClient c = existing.get();
                if (!WeeklyImportStore.key(c.firstName()).equals(WeeklyImportStore.key(row.firstName()))
                        || !WeeklyImportStore.key(c.lastName()).equals(WeeklyImportStore.key(row.lastName()))) {
                    extraIssues.add(rowIssue(row, "WARNING", "client:name-differs", "NAME",
                            "This ID number belongs to a client stored under a different name. The stored name is kept; "
                                    + "confirm needs a manager's explicit approval (or fix the workbook)."));
                }
                if (consultant != null && c.currentConsultantId() != null
                        && c.currentConsultantId() != consultant.id()) {
                    extraIssues.add(rowIssue(row, "WARNING", "client:consultant-differs", "CONSULTANT",
                            "The client is assigned to another consultant. The assignment is not changed; "
                                    + "the sale is recorded for the consultant in the sheet."));
                }
                if (consultant != null && c.currentConsultantId() == null
                        && "UNASSIGN".equals(store.lastAssignmentChange(c.id()).orElse(null))) {
                    extraIssues.add(rowIssue(row, "WARNING", "client:unassigned-by-manager", "CONSULTANT",
                            "A manager removed this client's consultant. The import does not assign the client "
                                    + "again; the sale is recorded for the consultant in the sheet."));
                }
                staged.add(new Staged(row, "UNCHANGED", hash, c.id(), c.version(), false, false));
                unchanged++;
            } else {
                boolean assign = consultant != null && consultant.active();
                if (consultant != null && !consultant.active()) {
                    extraIssues.add(rowIssue(row, "WARNING", "row:consultant-not-active", "CONSULTANT",
                            "The consultant is " + consultant.status() + ": the new client is left unassigned."));
                }
                staged.add(new Staged(row, "NEW", hash, null, null, true, assign));
                newCount++;
            }
        }
        lookupInfo(parsed, store.employersByName(), ParsedRow::employerName, "employer", extraIssues);
        lookupInfo(parsed, store.structuresByName(), ParsedRow::structureName, "structure", extraIssues);
        lookupInfo(parsed, store.paymentTermsByName(), ParsedRow::paymentTermName, "payment term", extraIssues);

        List<ParseIssue> allIssues = new ArrayList<>(parsed.issues());
        allIssues.addAll(extraIssues);
        int warnings = (int) allIssues.stream().filter(i -> i.severity() == ParseIssue.Severity.WARNING).count();

        long jobId = store.insertJob(new WeeklyImportStore.NewJob(WeeklyImportStore.truncate(name, 255), digest,
                bytes.length, userId, week, staged.size(), newCount, unchanged, invalid, duplicates, warnings,
                clock.instant().plus(PREVIEW_LIFETIME)));
        Map<String, Integer> warningsPerRow = new HashMap<>();
        for (ParseIssue i : allIssues) {
            if (i.row() != null && i.severity() == ParseIssue.Severity.WARNING) {
                warningsPerRow.merge(i.sheet() + "!" + i.row(), 1, Integer::sum);
            }
        }
        for (Staged s : staged) {
            storeRow(jobId, s, dictionary, warningsPerRow.getOrDefault(s.row.sheetName() + "!" + s.row.rowNumber(), 0));
        }
        for (ParseIssue i : allIssues) {
            store.insertIssue(jobId, i.sheet(), i.row(), i.field(), i.code(), i.severity().name(), i.message());
        }
        ReportReconciler.Reconciliation report = reconciliation.storeForPreview(jobId, week, workbook, parsed,
                dictionary);
        audit.record("IMPORT_PREVIEWED", "IMPORT_JOB", jobId, meta("weekStarting", week.toString(), "rows",
                staged.size(), "rejected", invalid, "newClients", newCount, "newProducts", newProducts.size(),
                "fileDigest", digest,
                "reportFigures", report.figures().size(), "reportDifferences", report.differences()));
        return summary(jobId);
    }

    /** Two rows with this key look like the same sale typed twice. */
    private static String saleKey(String hashKey, ParsedRow r) {
        StringBuilder sb = new StringBuilder(hashKey).append('|').append(r.transactionDate()).append('|')
                .append(WeeklyImportStore.key(r.consultantName())).append('|').append(plain(r.quotation()))
                .append('|').append(plain(r.banked())).append('|').append(plain(r.amountToClient())).append('|')
                .append(plain(r.thirdPartySettlement()));
        new java.util.TreeMap<>(r.productAmounts()).forEach((code, amount) -> sb.append('|').append(code)
                .append('=').append(plain(amount)));
        return sb.toString();
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
    }

    private record Staged(ParsedRow row, String action, byte[] hash, Long existingClientId, Long existingVersion,
            boolean createsClient, boolean assignsConsultant) {
    }

    private void storeRow(long jobId, Staged s, ImportDictionary dictionary, int warnings) {
        ParsedRow r = s.row;
        boolean keepId = s.hash != null;
        byte[] encrypted = keepId && "NEW".equals(s.action) ? protector.encrypt(r.idNumber()) : null;
        long rowId = store.insertRow(new WeeklyImportStore.NewRow(jobId, r.sheetName(), r.rowNumber(),
                r.day().getValue(), r.transactionDate(), s.action, s.hash, encrypted,
                r.idNumber() == null ? null : r.idNumber().last4(), r.firstName(), r.lastName(), r.consultantName(),
                r.consultantEmail(), r.employerName(), r.structureName(), r.paymentTermName(), r.quotation(), r.banked(),
                r.amountToClient(), r.thirdPartySettlement(), r.primaryBudgetList(), r.primaryLoanAmount(),
                r.secondaryBudgetList(), r.secondaryLoanAmount(), extrasJson(r.extras()), s.existingClientId,
                s.existingVersion, s.createsClient, s.assignsConsultant, warnings));
        if (!"INVALID".equals(s.action)) {
            for (var line : r.productAmounts().entrySet()) {
                long productId = dictionary.productByCode(line.getKey()).orElseThrow().id();
                store.insertLine(rowId, productId, line.getValue());
            }
        }
    }

    private static void lookupInfo(ParseResult parsed, Map<String, Long> known,
            java.util.function.Function<ParsedRow, String> name, String kind, List<ParseIssue> out) {
        Set<String> unknown = new java.util.TreeSet<>();
        for (ParsedRow r : parsed.validRows()) {
            String n = name.apply(r);
            if (n != null && !known.containsKey(WeeklyImportStore.key(n))
                    && !("payment term".equals(kind) && known.containsKey(WeeklyImportStore.withoutPayment(n)))) {
                unknown.add(WeeklyImportStore.key(n));
            }
        }
        if (!unknown.isEmpty()) {
            out.add(new ParseIssue(ParseIssue.Severity.INFO, "lookup:new-" + kind.replace(' ', '-'), null, null,
                    kind.toUpperCase(java.util.Locale.ROOT),
                    unknown.size() + " " + kind + " name(s) are new and will be created at confirm"
                            + ("employer".equals(kind) ? "; an admin can merge them with existing employers later."
                                    : ".")));
        }
    }

    private static ParseIssue rowIssue(ParsedRow row, String severity, String code, String field, String message) {
        return new ParseIssue(ParseIssue.Severity.valueOf(severity), code, row.sheetName(), row.rowNumber(), field,
                message);
    }

    @Transactional(readOnly = true)
    public ImportSummary summary(long jobId) {
        Job job = store.findJob(jobId).orElseThrow(() -> notFound(jobId));
        Instant now = clock.instant();
        String state = job.expired(now) ? "EXPIRED" : job.state();

        Map<Integer, WeeklyImportStore.DayCount> counted = new HashMap<>();
        store.dayCounts(jobId).forEach(d -> counted.put(d.dayOfWeek(), d));
        List<DaySummary> days = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            var d = counted.get(day);
            days.add(d != null
                    ? new DaySummary(d.sheetName(), day, d.date(), d.rows(), d.rejected(), d.quotation(), d.banked())
                    : new DaySummary(null, day, job.weekStarting().plusDays(day - 1L), 0, 0, BigDecimal.ZERO,
                            BigDecimal.ZERO));
        }
        BigDecimal quotation = days.stream().map(DaySummary::quotation).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal banked = days.stream().map(DaySummary::banked).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Long> issues = store.issueCounts(jobId);

        boolean previewed = "PREVIEWED".equals(state);
        Map<String, ConsultantRef> byName = previewed ? store.consultantsByName() : Map.of();
        ConsultantResolver.Plan plan = previewed ? planFor(store.sheetConsultants(jobId), byName) : null;
        ConsultantList consultantList = previewed ? consultantList(job, byName, plan)
                : ConsultantList.none(job.consultantsConfirmedAt(), job.consultantsConfirmedBy());
        Long weekImportedBy = store.currentJobForWeek(job.weekStarting()).filter(id -> id != jobId).orElse(null);

        List<String> blockers = new ArrayList<>();
        if (!"PREVIEWED".equals(state)) {
            blockers.add("EXPIRED".equals(state) ? "EXPIRED" : "NOT_PREVIEWED");
        }
        if (store.blockingIssueCount(jobId) > 0) {
            blockers.add("SHEET_ERRORS");
        }
        if (plan != null && !plan.conflicts().isEmpty()) {
            blockers.add("CONSULTANT_CONFLICTS");
        }
        if (previewed && !consultantList.confirmed()) {
            blockers.add("CONSULTANTS_NOT_CONFIRMED");
        }
        int nameDifferences = (int) store.nameDifferenceCount(jobId);
        if (nameDifferences > 0 && "PREVIEWED".equals(state)) {
            blockers.add("NAME_DIFFERENCES");
        }
        var report = reconciliation.jobStatus(jobId);
        // The spreadsheet wins: confirming replaces the sales typed in for this week.
        int typedInSales = "PREVIEWED".equals(state) ? store.typedInSalesForWeek(job.weekStarting()) : 0;
        return new ImportSummary(job.id(), job.fileName(), job.fileDigest(), state, job.weekStarting(),
                job.weekStarting().plusDays(6), job.uploadedByEmail(), job.createdAt(), job.expiresAt(),
                job.committedAt(), job.totalRows(), job.newCount(), job.unchangedCount(), job.duplicateCount(),
                job.invalidCount(), job.appliedCount(), issues.getOrDefault("ERROR", 0L),
                issues.getOrDefault("WARNING", 0L), issues.getOrDefault("INFO", 0L), quotation, banked, days,
                consultantList, weekImportedBy, weekImportedBy != null && "PREVIEWED".equals(state),
                blockers.isEmpty(), blockers, report.status(), report.differences(), nameDifferences, typedInSales,
                store.issueCount(jobId, POSSIBLE_DUPLICATE_SALE),
                plan == null ? ConsultantRegistration.NONE : registrationView(jobId, byName, plan),
                plan == null ? AssignmentPreview.NONE : assignmentPreview(jobId, byName, plan),
                job.result() == null ? null : resultView(job.result()));
    }

    private static ImportResult resultView(WeeklyImportStore.Result r) {
        return new ImportResult(r.consultantsIdentified(), r.consultantsRegistered(), r.consultantsMatched(),
                r.clientsLinked(), r.transactionsLinked(), r.transactionsUnattributed(), r.rowsNeedingAttention());
    }

    // ---------- consultants (docs/consultant-onboarding.md, docs/api-imports.md section 11) ----------

    private ConsultantResolver.Plan planFor(List<WeeklyImportStore.SheetConsultant> sheet,
            Map<String, ConsultantRef> byName) {
        return ConsultantResolver.plan(sheet, byName, store.consultantsByEmail(), store.consultantEmails(),
                store.ambiguousConsultantNames());
    }

    /**
     * The consultant of each sheet name, for the rows: byName without the names
     * that match more than one consultant, plus every name whose sheet email
     * belongs to a consultant (the email wins over the name). A name missing here
     * has no consultant yet: it is registered at confirm, or it is in conflict
     * (which blocks confirm).
     */
    private Map<String, ConsultantRef> rowConsultants(Map<String, ConsultantRef> byName,
            List<WeeklyImportStore.SheetConsultant> sheet) {
        Map<String, ConsultantRef> out = new HashMap<>(byName);
        store.ambiguousConsultantNames().keySet().forEach(out::remove);
        Map<String, ConsultantRef> byEmail = store.consultantsByEmail();
        for (WeeklyImportStore.SheetConsultant s : sheet) {
            if (s.email() != null && byEmail.containsKey(s.email())) {
                out.put(WeeklyImportStore.key(s.name()), byEmail.get(s.email()));
            }
        }
        return out;
    }

    /** One consultant of the list while it is being built. */
    private static final class FoundGroup {
        String outcome;
        String matchedBy;
        final Set<String> names = new java.util.LinkedHashSet<>();
        final Set<String> keys = new HashSet<>();
        final Set<String> sheetEmails = new java.util.TreeSet<>();
        final Set<String> reasons = new java.util.LinkedHashSet<>();
        int rows;
        int newClients;
        ConsultantRef consultant;
        ConsultantResolver.Registration registration;
        String email;
    }

    private static final List<String> OUTCOME_ORDER = List.of("CONFLICT", "NEW", "EMAIL_ADDED", "EXISTING");

    /**
     * Every consultant found in the import, one entry per consultant the names
     * become, with the fingerprint the admin confirms.
     */
    private ConsultantList consultantList(Job job, Map<String, ConsultantRef> byName, ConsultantResolver.Plan plan) {
        List<WeeklyImportStore.SheetConsultant> sheet = store.sheetConsultants(job.id());
        Map<String, ConsultantRef> rowConsultants = rowConsultants(withPlan(byName, plan), sheet);
        Map<Long, String> emailOf = store.consultantEmails();
        Map<String, Integer> newClientsOfKey = new HashMap<>();
        store.newClientRowsByConsultant(job.id()).forEach((name, n) -> {
            if (name != null) {
                newClientsOfKey.merge(WeeklyImportStore.key(name), n, Integer::sum);
            }
        });

        Map<String, FoundGroup> groups = new java.util.LinkedHashMap<>();
        for (WeeklyImportStore.SheetConsultant s : sheet) {
            String name = s.name().strip().replaceAll("\\s+", " ");
            String key = WeeklyImportStore.key(name);
            List<ConsultantResolver.Conflict> conflicts = plan.conflicts().stream()
                    .filter(c -> WeeklyImportStore.key(c.nameInSheet()).equals(key)).toList();
            ConsultantResolver.Registration registration = plan.registrations().stream()
                    .filter(r -> WeeklyImportStore.key(r.fullName()).equals(key)
                            || r.otherNames().stream().anyMatch(o -> WeeklyImportStore.key(o).equals(key)))
                    .findFirst().orElse(null);
            ConsultantRef ref = rowConsultants.get(key);

            FoundGroup g;
            if (!conflicts.isEmpty() || (registration == null && ref == null)) {
                g = groups.computeIfAbsent("X:" + key, k -> new FoundGroup());
                g.outcome = "CONFLICT";
                conflicts.forEach(c -> g.reasons.add(c.reason()));
                if (conflicts.isEmpty()) {
                    g.reasons.add("This name could not be linked to a consultant.");
                }
            } else if (registration != null) {
                g = groups.computeIfAbsent("N:" + WeeklyImportStore.key(registration.fullName()),
                        k -> new FoundGroup());
                g.outcome = "NEW";
                g.registration = registration;
                g.email = registration.email();
            } else {
                g = groups.computeIfAbsent("C:" + ref.id(), k -> new FoundGroup());
                var update = plan.emailUpdates().stream().filter(u -> u.consultant().id() == ref.id()).findFirst();
                g.outcome = update.isPresent() ? "EMAIL_ADDED" : "EXISTING";
                g.consultant = ref;
                g.email = update.map(ConsultantResolver.EmailUpdate::email).orElse(emailOf.get(ref.id()));
                g.matchedBy = "EMAIL".equals(g.matchedBy) || plan.matchedByEmail(name) ? "EMAIL" : "NAME";
            }
            g.names.add(name);
            if (s.email() != null) {
                g.sheetEmails.add(s.email());
            }
            g.rows += s.rows();
            if (g.keys.add(key)) {
                g.newClients += newClientsOfKey.getOrDefault(key, 0);
            }
        }

        List<FoundGroup> ordered = new ArrayList<>(groups.values());
        ordered.sort(java.util.Comparator.comparingInt((FoundGroup g) -> OUTCOME_ORDER.indexOf(g.outcome))
                .thenComparing(g -> WeeklyImportStore.key(g.names.iterator().next())));
        List<ConsultantFound> items = new ArrayList<>();
        List<String> fingerprintLines = new ArrayList<>();
        int existing = 0;
        int toRegister = 0;
        int conflicts = 0;
        int withoutEmail = 0;
        for (FoundGroup g : ordered) {
            List<String> attention = new ArrayList<>();
            List<SimilarConsultant> similar = List.of();
            String email = g.email;
            switch (g.outcome) {
                case "CONFLICT" -> {
                    conflicts++;
                    attention.add("CONFLICT");
                    email = g.sheetEmails.isEmpty() ? null : String.join(", ", g.sheetEmails);
                }
                case "NEW" -> {
                    toRegister++;
                    similar = similarConsultants(g.registration.fullName(), byName);
                    String mine = WeeklyImportStore.key(g.registration.fullName());
                    boolean looksLikeAnotherNew = ordered.stream()
                            .filter(o -> o != g && "NEW".equals(o.outcome))
                            .anyMatch(o -> similarity(mine, WeeklyImportStore.key(o.registration.fullName())) >= 0);
                    if (!similar.isEmpty() || looksLikeAnotherNew) {
                        attention.add("SIMILAR_NAME");
                    }
                }
                default -> {
                    existing++;
                    if (!g.consultant.active()) {
                        attention.add("NOT_ACTIVE");
                    }
                }
            }
            if (!"CONFLICT".equals(g.outcome) && email == null) {
                attention.add("NO_EMAIL");
                withoutEmail++;
            }
            ConsultantRef c = g.consultant;
            items.add(new ConsultantFound(g.outcome, g.matchedBy, List.copyOf(g.names), email, g.rows, g.newClients,
                    c == null ? null : c.id(), c == null ? null : c.code(), c == null ? null : c.fullName(),
                    c == null ? null : c.status(), List.copyOf(attention),
                    g.reasons.isEmpty() ? null : String.join(" ", g.reasons), similar));
            List<String> keys = new ArrayList<>(g.keys);
            java.util.Collections.sort(keys);
            fingerprintLines.add(g.outcome + "|" + (c == null ? "" : c.id()) + "|" + String.join(",", keys) + "|"
                    + (email == null ? "" : email) + "|" + g.rows + "|" + String.join(",", attention));
        }
        java.util.Collections.sort(fingerprintLines);
        String fingerprint = sha256(String.join("\n", fingerprintLines)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean confirmed = fingerprint.equals(job.consultantsFingerprint());
        return new ConsultantList(List.copyOf(items), items.size(), existing, toRegister, conflicts, withoutEmail,
                fingerprint, confirmed, confirmed ? job.consultantsConfirmedAt() : null,
                confirmed ? job.consultantsConfirmedBy() : null);
    }

    /**
     * The admin checked the consultants found in the workbook ("Confirm
     * Consultants and Proceed"). Nothing is registered yet: confirm does that, in
     * the same transaction as the clients and sales. The fingerprint proves the
     * admin confirmed the list as it is now.
     */
    @Transactional
    public ImportSummary confirmConsultants(long userId, long jobId, String fingerprint) {
        Job job = requireOpen(store.lockJob(jobId).orElseThrow(() -> notFound(jobId)));
        Map<String, ConsultantRef> byName = store.consultantsByName();
        ConsultantResolver.Plan plan = planFor(store.sheetConsultants(job.id()), byName);
        ConsultantList list = consultantList(job, byName, plan);
        if (list.conflicts() > 0) {
            throw ApiException.unprocessable("import:consultant-conflicts", "Consultant conflicts",
                    list.conflicts() + " consultant(s) cannot be linked safely. Fix them in the workbook (the "
                            + "consultants with outcome CONFLICT) and upload it again.");
        }
        if (!list.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("import:consultants-changed", "Consultants changed",
                    "The consultant list changed since it was shown. Check it again and confirm.");
        }
        store.markConsultantsConfirmed(job.id(), userId, fingerprint);
        audit.record("IMPORT_CONSULTANTS_CONFIRMED", "IMPORT_JOB", job.id(), meta("consultants", list.identified(),
                "existing", list.existing(), "toRegister", list.toRegister(), "withoutEmail", list.withoutEmail(),
                "fingerprint", fingerprint));
        return summary(job.id());
    }

    /** byName plus the sheet spellings the plan will add as aliases. */
    private static Map<String, ConsultantRef> withPlan(Map<String, ConsultantRef> byName,
            ConsultantResolver.Plan plan) {
        Map<String, ConsultantRef> all = new HashMap<>(byName);
        for (ConsultantResolver.AliasAdd a : plan.aliases()) {
            all.putIfAbsent(WeeklyImportStore.key(a.alias()), a.consultant());
        }
        return all;
    }

    /** The same grouping as WeeklyImportStore.sheetConsultants, from rows not stored yet. */
    private static List<WeeklyImportStore.SheetConsultant> sheetConsultantsOf(List<ParsedRow> rows) {
        Map<List<String>, Integer> counts = new java.util.LinkedHashMap<>();
        for (ParsedRow r : rows) {
            if (!r.rejected() && r.consultantName() != null) {
                // Arrays.asList: the email may be null (List.of refuses nulls).
                counts.merge(java.util.Arrays.asList(r.consultantName(), r.consultantEmail()), 1, Integer::sum);
            }
        }
        List<WeeklyImportStore.SheetConsultant> out = new ArrayList<>();
        counts.forEach((k, n) -> out.add(new WeeklyImportStore.SheetConsultant(k.get(0), k.get(1), n)));
        return out;
    }

    private ConsultantRegistration registrationView(long jobId, Map<String, ConsultantRef> byName,
            ConsultantResolver.Plan plan) {
        Set<String> names = new HashSet<>();
        int matchedByName = 0;
        for (String name : store.consultantNames(jobId).keySet()) {
            String key = WeeklyImportStore.key(name);
            if (names.add(key) && byName.containsKey(key) && !plan.decided(name)) {
                matchedByName++;
            }
        }
        List<ConsultantToRegister> toRegister = plan.registrations().stream()
                .map(r -> new ConsultantToRegister(r.fullName(), r.email(), r.otherNames(), r.rows(),
                        similarConsultants(r.fullName(), byName)))
                .toList();
        List<ConsultantEmailToAdd> emails = plan.emailUpdates().stream()
                .map(u -> new ConsultantEmailToAdd(u.consultant().id(), u.consultant().code(),
                        u.consultant().fullName(), u.email(), u.rows()))
                .toList();
        List<ConsultantConflict> conflicts = plan.conflicts().stream()
                .map(c -> new ConsultantConflict(c.nameInSheet(), c.email(), c.reason(), c.rows()))
                .toList();
        return new ConsultantRegistration(names.size(), matchedByName, plan.namesMatchedByEmail(), toRegister,
                emails, conflicts);
    }

    private AssignmentPreview assignmentPreview(long jobId, Map<String, ConsultantRef> byName,
            ConsultantResolver.Plan plan) {
        Map<String, ConsultantRef> effective = withPlan(byName, plan);
        int total = 0;
        int assigned = 0;
        int none = 0;
        int notActive = 0;
        int waiting = 0;
        for (var e : store.newClientRowsByConsultant(jobId).entrySet()) {
            String name = e.getKey();
            int rows = e.getValue();
            total += rows;
            if (name == null) {
                none += rows;
                continue;
            }
            ConsultantRef ref = effective.get(WeeklyImportStore.key(name));
            if (plan.inConflict(name)) {
                waiting += rows;
            } else if (ref != null) {
                if (ref.active()) {
                    assigned += rows;
                } else {
                    notActive += rows;
                }
            } else if (plan.registers(name)) {
                assigned += rows; // a registered consultant starts ACTIVE
            } else {
                waiting += rows;
            }
        }
        return new AssignmentPreview(total, assigned, none, notActive, waiting,
                store.issueCount(jobId, "client:consultant-differs"));
    }

    /**
     * Up to 3 existing consultants whose name (or alias) looks like {@code name}:
     * at most 2
     * letters different, or one is a word of the other ("AMBITIOUS" and "Ambitious
     * Mokoena").
     * Shown next to an unknown name so a typo is mapped instead of becoming a
     * second consultant.
     */
    static List<SimilarConsultant> similarConsultants(String name, Map<String, ConsultantRef> known) {
        String key = WeeklyImportStore.key(name);
        Map<Long, int[]> best = new java.util.HashMap<>(); // consultant id -> {score}
        Map<Long, ConsultantRef> refs = new java.util.HashMap<>();
        for (var e : known.entrySet()) {
            int score = similarity(key, e.getKey());
            if (score >= 0) {
                ConsultantRef c = e.getValue();
                refs.put(c.id(), c);
                best.merge(c.id(), new int[] { score }, (a, b) -> a[0] <= b[0] ? a : b);
            }
        }
        return best.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt((Map.Entry<Long, int[]> e) -> e.getValue()[0])
                        .thenComparing(e -> refs.get(e.getKey()).fullName()))
                .limit(3)
                .map(e -> refs.get(e.getKey()))
                .map(c -> new SimilarConsultant(c.id(), c.code(), c.fullName(), c.status()))
                .toList();
    }

    /**
     * 0-2 = letters different; 3 = one name is a word of the other; -1 = not
     * similar.
     */
    private static int similarity(String a, String b) {
        if (a.length() >= 4 && b.length() >= 4) {
            int d = distance(a, b, 2);
            if (d <= 2) {
                return d;
            }
        }
        List<String> wa = List.of(a.split(" "));
        List<String> wb = List.of(b.split(" "));
        boolean word = (wa.size() > 1 || wb.size() > 1)
                && (wa.stream().anyMatch(w -> w.length() >= 3 && wb.contains(w)));
        return word ? 3 : -1;
    }

    /**
     * Levenshtein distance, giving up (returning max + 1) once it is over
     * {@code max}.
     */
    private static int distance(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) {
            return max + 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > max) {
                return max + 1;
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    @Transactional(readOnly = true)
    public PageResponse<StagedRowView> rows(long jobId, String sheet, String action, int page, int size) {
        store.findJob(jobId).orElseThrow(() -> notFound(jobId));
        int s = clampSize(size);
        int p = Math.max(page, 0);
        var rows = store.rows(jobId, blankToNull(sheet), blankToNull(action), s, (long) p * s);
        var products = store.productsOfRows(rows.stream().map(WeeklyImportStore.StagedRow::id).toList());
        List<StagedRowView> items = rows.stream().map(r -> new StagedRowView(r.id(), r.sheetName(), r.rowNumber(),
                r.transactionDate(), r.action(), r.outcome(),
                r.idNumberLast4() == null ? null : "*********" + r.idNumberLast4(),
                r.firstName(), r.lastName(), r.consultantName(), r.employerName(), r.quotation(), r.banked(),
                products.getOrDefault(r.id(), Map.of()), r.warningCount())).toList();
        return page(items, p, s, store.countRows(jobId, blankToNull(sheet), blankToNull(action)));
    }

    @Transactional(readOnly = true)
    public PageResponse<IssueView> issues(long jobId, String severity, int page, int size) {
        store.findJob(jobId).orElseThrow(() -> notFound(jobId));
        int s = clampSize(size);
        int p = Math.max(page, 0);
        String sev = blankToNull(severity) == null ? null : severity.strip().toUpperCase(java.util.Locale.ROOT);
        List<IssueView> items = store.issues(jobId, sev, s, (long) p * s).stream()
                .map(i -> new IssueView(i.sheetName(), i.rowNumber(), i.field(), i.code(), i.severity(), i.message()))
                .toList();
        return page(items, p, s, store.countIssues(jobId, sev));
    }

    @Transactional
    public IssueFile exportIssues(long jobId, String severity) {
        Job job = store.findJob(jobId).orElseThrow(() -> notFound(jobId));
        String sev = blankToNull(severity) == null ? null : severity.strip().toUpperCase(java.util.Locale.ROOT);
        List<WeeklyImportStore.IssueLine> lines = store.issueLines(jobId, sev);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append(csvLine("Sheet", "Row", "Field", "Severity", "Code", "Message", "ID number (last 4)"));
        for (WeeklyImportStore.IssueLine l : lines) {
            csv.append(csvLine(l.sheetName(), l.rowNumber() == null ? "" : l.rowNumber().toString(), l.field(),
                    l.severity(), l.code(), l.message(),
                    l.idNumberLast4() == null ? "" : "*********" + l.idNumberLast4()));
        }
        audit.record("IMPORT_ISSUES_EXPORTED", "IMPORT_JOB", job.id(), meta("severity", sev, "lines", lines.size()));
        String fileName = "import-" + job.id() + "-" + job.weekStarting() + "-issues.csv";
        return new IssueFile(fileName, csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String csvLine(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            String v = values[i] == null ? "" : values[i];
            if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
                v = "'" + v;
            }
            sb.append(i == 0 ? "" : ",").append('"').append(v.replace("\"", "\"\"")).append('"');
        }
        return sb.append("\r\n").toString();
    }

    @Transactional(readOnly = true)
    public PageResponse<ImportListItem> list(int page, int size) {
        int s = clampSize(size);
        int p = Math.max(page, 0);
        Instant now = clock.instant();
        List<ImportListItem> items = store.listJobs(s, (long) p * s).stream()
                .map(j -> new ImportListItem(j.id(), j.fileName(), j.expired(now) ? "EXPIRED" : j.state(),
                        j.weekStarting(), j.uploadedByEmail(), j.createdAt(), j.committedAt(), j.totalRows(),
                        j.invalidCount(), j.appliedCount(), j.replacesImportJobId()))
                .toList();
        return page(items, p, s, store.countJobs());
    }

    @Transactional
    public ConfirmResult confirm(long userId, boolean admin, long jobId, String fileDigest, String replaceReason,
            boolean acceptNameDifferences) {
        Job job = store.lockJob(jobId).orElseThrow(() -> notFound(jobId));
        requireOpen(job);
        if (!job.fileDigest().equals(fileDigest)) {
            throw ApiException.conflict("import:digest-mismatch", "Different file",
                    "The confirmation does not match the uploaded file. Reload the preview and try again.");
        }
        if (store.blockingIssueCount(job.id()) > 0) {
            throw ApiException.unprocessable("import:blocking-errors", "Workbook has errors",
                    "Fix the sheet-level errors listed in the preview, then upload the file again.");
        }
        Map<String, ConsultantRef> known = store.consultantsByName();
        List<WeeklyImportStore.SheetConsultant> sheetConsultants = store.sheetConsultants(job.id());
        ConsultantResolver.Plan plan = planFor(sheetConsultants, known);
        if (!plan.conflicts().isEmpty()) {
            throw ApiException.unprocessable("import:consultant-conflicts", "Consultant conflicts",
                    plan.conflicts().size() + " consultant name(s) cannot be linked to one consultant safely. Fix "
                            + "them in the sheet (the consultants with outcome CONFLICT in the preview) and upload "
                            + "it again.");
        }
        ConsultantList consultantList = consultantList(job, known, plan);
        if (job.consultantsFingerprint() == null) {
            throw ApiException.unprocessable("import:consultants-not-confirmed", "Consultants not confirmed",
                    "Check the consultants found in the workbook and confirm them first "
                            + "(Confirm Consultants and Proceed).");
        }
        if (!consultantList.confirmed()) {
            throw ApiException.conflict("import:consultants-changed", "Consultants changed",
                    "The consultants changed since they were confirmed. Check the list and confirm it again.");
        }
        store.committedJobWithDigest(job.fileDigest()).ifPresent(id -> {
            throw ApiException.conflict("import:duplicate-file", "Already imported",
                    "This exact file was already imported (import " + id + ").");
        });

        WeeklyImportStore.Week week = store.lockWeek(job.weekStarting());
        Long replaced = week.currentImportJobId();
        String reason = replaceReason == null || replaceReason.isBlank() ? null : replaceReason.strip();
        int superseded = 0;
        if (replaced != null) {
            if (!admin) {
                throw new ApiException(HttpStatus.FORBIDDEN, "import:week-already-imported", "Week already imported",
                        "The week of " + job.weekStarting() + " was already imported (import " + replaced
                                + "). Only an administrator can replace it.");
            }
            if (reason == null) {
                throw ApiException.unprocessable("import:replace-reason-required", "Reason required",
                        "Give a reason for replacing the week that was already imported.");
            }
        }
        long nameDifferences = store.nameDifferenceCount(job.id());
        if (nameDifferences > 0 && !acceptNameDifferences) {
            throw ApiException.unprocessable("import:name-differences-unconfirmed", "Names differ",
                    nameDifferences + " row(s) have the ID number of a client stored under another name. Check them "
                            + "(issues with code client:name-differs) and confirm again with acceptNameDifferences, "
                            + "or fix the workbook and upload it again.");
        }
        if (replaced != null) {
            superseded = store.supersedeTransactions(replaced, job.id());
        }
        // The spreadsheet wins over sales typed into the app for this week (V21).
        int typedIn = store.supersedeTypedInSales(week.id(), job.id());

        // Consultants from the sheet, before any client is assigned.
        int[] consultantChanges = applyConsultantPlan(userId, job.id(), plan);
        Map<String, ConsultantRef> consultants = rowConsultants(store.consultantsByName(), sheetConsultants);

        Map<String, Long> employers = store.employersByName();
        Map<String, Long> structures = store.structuresByName();
        Map<String, Long> terms = store.paymentTermsByName();
        Map<String, Long> clientByHash = new HashMap<>();
        Counters n = new Counters();
        String historyReason = "Weekly register import, week of " + job.weekStarting();

        for (WeeklyImportStore.RowToApply r : store.rowsToApply(job.id())) {
            ConsultantRef consultant = r.consultantName() == null ? null
                    : consultants.get(WeeklyImportStore.key(r.consultantName()));
            Long employerId = resolve(employers, r.employerName(), store::createEmployer, () -> n.employers++);
            Long structureId = resolve(structures, r.structureName(), store::createStructure, () -> n.structures++);
            Long termId = r.paymentTermName() == null ? null
                    : terms.containsKey(WeeklyImportStore.withoutPayment(r.paymentTermName()))
                            ? terms.get(WeeklyImportStore.withoutPayment(r.paymentTermName()))
                            : resolve(terms, r.paymentTermName(), store::createPaymentTerm, () -> n.terms++);

            String hashKey = HexFormat.of().formatHex(r.idNumberHash());
            Long clientId = clientByHash.get(hashKey);
            if (clientId == null) {
                var existing = store.lockClientByHash(r.idNumberHash());
                if (existing.isPresent()) {
                    ExistingClient c = existing.get();
                    clientId = c.id();
                    if ("NEW".equals(r.action())) {
                        n.stale++;
                    }

                    if (c.currentConsultantId() == null && consultant != null && consultant.active()
                            && !"UNASSIGN".equals(store.lastAssignmentChange(c.id()).orElse(null))
                            && store.assignIfUnassigned(c.id(), consultant.id())) {
                        store.insertImportHistory(c.id(), consultant.id(), historyReason, userId, job.id());
                        n.assigned++;
                    }
                    if (c.employerId() == null && employerId != null) {
                        store.setEmployerIfMissing(c.id(), employerId);
                    }
                } else {
                    if (r.idNumberEncrypted() == null) {
                        throw ApiException.conflict("import:stale-preview", "Data changed",
                                "A client in this import was removed since the preview. Upload the file again.");
                    }
                    Long assignTo = consultant != null && consultant.active() ? consultant.id() : null;
                    if (assignTo == null) {
                        n.unassignedNew++;
                    }
                    clientId = store.insertClient(r.idNumberHash(), r.idNumberEncrypted(), r.idNumberLast4(),
                            r.firstName(), r.lastName(), assignTo, employerId, job.id());
                    n.newClients++;
                    audit.record("CLIENT_CREATED", "CLIENT", clientId,
                            meta("consultantId", assignTo, "importJobId", job.id()));
                    if (assignTo != null) {
                        store.insertImportHistory(clientId, assignTo, historyReason, userId, job.id());
                        n.assigned++;
                    }
                }
                clientByHash.put(hashKey, clientId);
            }

            long transactionId = store.insertTransaction(new WeeklyImportStore.NewTransaction(clientId,
                    consultant == null ? null : consultant.id(), r.transactionDate(), employerId, structureId, termId,
                    r.amountToClient(), r.thirdPartySettlement(), r.primaryBudgetList(), r.primaryLoanAmount(),
                    r.secondaryBudgetList(), r.secondaryLoanAmount(), job.id(), r.sheetName(), r.rowNumber(), userId,
                    week.id(), r.dayOfWeek(), r.quotation(), r.banked(), r.consultantName(), r.employerName()));
            n.transactions++;
            if (consultant == null) {
                n.unattributed++;
            }
            n.lines += store.insertLines(transactionId, r.id());
            n.extras += store.insertExtras(transactionId, r.id());
            store.setRowOutcome(r.id(), "APPLIED");
        }

        long possibleDuplicates = store.issueCount(job.id(), POSSIBLE_DUPLICATE_SALE);
        int needingAttention = job.invalidCount() + n.unattributed + (int) possibleDuplicates;
        WeeklyImportStore.Result result = new WeeklyImportStore.Result(consultantList.identified(),
                consultantChanges[0], consultantList.existing(), clientByHash.size(), n.transactions - n.unattributed,
                n.unattributed, needingAttention);
        store.saveResult(job.id(), result);
        store.setCurrentImport(week.id(), job.id());
        store.markCommitted(new WeeklyImportStore.Commit(job.id(), week.id(), replaced, reason, n.transactions, n.stale,
                n.newClients));
        audit.record("IMPORT_CONFIRMED", "IMPORT_JOB", job.id(), meta("weekStarting", job.weekStarting().toString(),
                "transactions", n.transactions, "newClients", n.newClients, "assigned", n.assigned,
                "replacesImportJobId", replaced, "nameDifferencesAccepted", nameDifferences,
                "replacedTypedInSales", typedIn, "consultantsRegistered", consultantChanges[0],
                "consultantEmailsAdded", consultantChanges[1], "consultantAliasesAdded", consultantChanges[2],
                "consultantsIdentified", result.consultantsIdentified(), "consultantsMatched",
                result.consultantsMatched(), "clientsLinked", result.clientsLinked(), "transactionsLinked",
                result.transactionsLinked(), "transactionsUnattributed", result.transactionsUnattributed(),
                "rowsNeedingAttention", needingAttention));
        if (replaced != null) {
            audit.record("WEEK_REPLACED", "IMPORT_JOB", replaced, meta("replacedBy", job.id(), "reason", reason,
                    "supersededTransactions", superseded));
        }
        return new ConfirmResult(job.id(), week.id(), job.weekStarting(), n.transactions, n.lines, n.extras,
                n.newClients, n.assigned, n.employers, n.structures, n.terms, n.stale, replaced, superseded, typedIn,
                consultantChanges[0], consultantChanges[1], consultantChanges[2], n.unassignedNew,
                result.consultantsIdentified(), result.consultantsMatched(), result.clientsLinked(),
                result.transactionsLinked(), result.transactionsUnattributed(), job.invalidCount(),
                possibleDuplicates, needingAttention);
    }

    private static final class Counters {
        int transactions;
        int lines;
        int extras;
        int newClients;
        int assigned;
        int employers;
        int structures;
        int terms;
        int stale;
        int unassignedNew;
        int unattributed;
    }

    /**
     * Registers the sheet's new consultants (with their email when the sheet has
     * one), fills in missing emails and adds the sheet's spellings as aliases
     * (ConsultantResolver). Runs inside confirm's
     * transaction, so a failed import leaves no consultant behind.
     *
     * @return {registered, emails added, aliases added}
     */
    private int[] applyConsultantPlan(long userId, long jobId, ConsultantResolver.Plan plan) {
        int registered = 0;
        int emails = 0;
        int aliases = 0;
        for (ConsultantResolver.Registration r : plan.registrations()) {
            String code = codes.next();
            long id = store.createConsultant(code, r.fullName(), r.email());
            store.insertConsultantAlias(id, r.fullName(), userId);
            for (String other : r.otherNames()) {
                store.insertConsultantAlias(id, other, userId);
                aliases++;
            }
            audit.record("CONSULTANT_CREATED", "CONSULTANT", id, meta("consultantCode", code, "fullName",
                    r.fullName(), "email", r.email(), "codeGenerated", true, "importJobId", jobId,
                    "source", r.email() == null ? "WEEKLY_REGISTER_NAME" : "WEEKLY_REGISTER_EMAIL"));
            registered++;
        }
        for (ConsultantResolver.EmailUpdate u : plan.emailUpdates()) {
            if (!store.setConsultantEmailIfMissing(u.consultant().id(), u.email())) {
                throw ApiException.conflict("import:stale-preview", "Data changed",
                        "Consultant " + u.consultant().code() + " was given an email since the preview. "
                                + "Upload the file again.");
            }
            audit.record("CONSULTANT_EMAIL_SET", "CONSULTANT", u.consultant().id(),
                    meta("email", u.email(), "importJobId", jobId));
            emails++;
        }
        for (ConsultantResolver.AliasAdd a : plan.aliases()) {
            store.insertConsultantAlias(a.consultant().id(), a.alias(), userId);
            audit.record("CONSULTANT_ALIAS_CREATED", "CONSULTANT", a.consultant().id(),
                    meta("alias", a.alias(), "importJobId", jobId, "source", "WEEKLY_REGISTER_EMAIL"));
            aliases++;
        }
        return new int[] { registered, emails, aliases };
    }

    private static Long resolve(Map<String, Long> known, String name,
            java.util.function.Function<String, Long> create, Runnable onCreate) {
        if (name == null) {
            return null;
        }
        String key = WeeklyImportStore.key(name);
        Long id = known.get(key);
        if (id == null) {
            id = create.apply(name);
            known.put(key, id);
            onCreate.run();
        }
        return id;
    }

    /**
     * The clean-up job: marks previews past their 24 hours as EXPIRED and deletes
     * their staged
     * personal data, as cancel does. Locks each job first, so a confirm running at
     * the same time
     * either finishes first or finds the preview expired. Audited as
     * IMPORT_EXPIRED.
     *
     * @return how many previews were expired
     */
    @Transactional
    public int expirePreviews() {
        int expired = 0;
        for (long id : store.expiredPreviewIds(500)) {
            Job job = store.lockJob(id).orElse(null);
            if (job == null || !"PREVIEWED".equals(job.state()) || job.expiresAt().isAfter(clock.instant())) {
                continue; // confirmed or cancelled meanwhile
            }
            store.markFinished(id, "EXPIRED");
            audit.record("IMPORT_EXPIRED", "IMPORT_JOB", id, meta("expiresAt", job.expiresAt().toString(),
                    "weekStarting", String.valueOf(job.weekStarting())));
            expired++;
        }
        return expired;
    }

    @Transactional
    public ImportSummary cancel(long jobId) {
        Job job = requireOpen(store.lockJob(jobId).orElseThrow(() -> notFound(jobId)));
        store.markFinished(job.id(), "CANCELLED");
        audit.record("IMPORT_CANCELLED", "IMPORT_JOB", job.id(), meta("weekStarting", job.weekStarting().toString()));
        return summary(job.id());
    }

    private Job requireOpen(Job job) {
        if (!"PREVIEWED".equals(job.state())) {
            throw ApiException.conflict("import:not-previewed", "Import already finished",
                    "This import is " + job.state() + ".");
        }
        if (job.expired(clock.instant())) {
            throw ApiException.conflict("import:expired", "Preview expired",
                    "This preview is older than 24 hours. Upload the file again.");
        }
        return job;
    }

    private static ApiException notFound(long jobId) {
        return ApiException.notFound("import:not-found", "Import " + jobId + " does not exist.");
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String extrasJson(Map<String, Object> extras) {
        if (extras == null || extras.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : new java.util.TreeMap<>(extras).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            jsonString(sb, e.getKey());
            sb.append(':');
            if (e.getValue() instanceof BigDecimal number) {
                sb.append(number.toPlainString());
            } else {
                jsonString(sb, String.valueOf(e.getValue()));
            }
        }
        return sb.append('}').toString();
    }

    private static void jsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private static <T> PageResponse<T> page(List<T> items, int page, int size, long total) {
        return new PageResponse<>(items, page, size, total, (int) ((total + size - 1) / size));
    }
}
