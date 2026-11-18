package za.co.mafori.creditadvisory.imports.clientlist;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.common.idnumber.IdNumberProtector;
import za.co.mafori.creditadvisory.common.idnumber.ProtectedIdNumber;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.PageResponse;
import za.co.mafori.creditadvisory.consultant.ConsultantCodeGenerator;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListStore.ExistingClient;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListStore.Job;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.Additions;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.ClientListSummary;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.ColumnView;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.ConfirmRequest;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.ConfirmResult;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.Consultants;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.IssueCounts;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.IssueFile;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.IssueView;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.ListItem;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.Result;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.RowCounts;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.StagedRowView;
import za.co.mafori.creditadvisory.imports.excel.PoiWorkbookReader;
import za.co.mafori.creditadvisory.imports.excel.RawWorkbook;
import za.co.mafori.creditadvisory.imports.excel.WorkbookRejectedException;
import za.co.mafori.creditadvisory.imports.register.ParseIssue;
import za.co.mafori.creditadvisory.imports.weekly.ConsultantResolver;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportStore;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportStore.ConsultantRef;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportStore.SheetConsultant;

/**
 * The first load of the full client list (docs/api-client-list-import.md).
 * Same shape as the weekly register import: preview (nothing changes yet),
 * check, confirm (one transaction), with the staged personal data deleted when
 * the job ends.
 *
 * <ul>
 * <li>A new ID number becomes a client, with every field the list has, and is
 * assigned to the consultant in the list when that consultant is ACTIVE.</li>
 * <li>An ID number already stored under the same name only fills in what the
 * client does not have yet; nothing stored is ever overwritten, and a client
 * already assigned keeps their consultant. Under another name the row is not
 * imported (CONFLICT): a person fixes it.</li>
 * <li>Consultant names follow the weekly register's rules (ConsultantResolver):
 * matched by email, then by name or alias; new names are registered at
 * confirm; a name that cannot be matched safely blocks confirm.</li>
 * </ul>
 */
@Service
public class ClientListImportService {

    static final Duration PREVIEW_LIFETIME = Duration.ofHours(24);
    static final String HISTORY_REASON = "Client list import (first load)";
    static final String NAME_DIFFERS = "client:name-differs";
    static final String CONSULTANT_DIFFERS = "client:consultant-differs";
    static final String UNASSIGNED_BY_MANAGER = "client:unassigned-by-manager";
    static final String DUPLICATE_ID = "row:duplicate-id-number";
    static final String CONSULTANT_NOT_ACTIVE = "row:consultant-not-active";
    static final String NEW_EMPLOYERS = "file:new-employers";
    static final String NEW_PRODUCT_QUOTATIONS = "file:new-product-quotations";
    private static final int MAX_PAGE_SIZE = 200;

    private final PoiWorkbookReader reader;
    private final ClientListParser parser;
    private final IdNumberProtector protector;
    private final ClientListStore store;
    private final WeeklyImportStore shared;
    private final AuditLogWriter audit;
    private final ConsultantCodeGenerator codes;
    private final Clock clock = Clock.systemUTC();

    public ClientListImportService(PoiWorkbookReader reader, ClientListParser parser, IdNumberProtector protector,
            ClientListStore store, WeeklyImportStore shared, AuditLogWriter audit, ConsultantCodeGenerator codes) {
        this.reader = reader;
        this.parser = parser;
        this.protector = protector;
        this.store = store;
        this.shared = shared;
        this.audit = audit;
        this.codes = codes;
    }

    // ---------- preview ----------

    @Transactional
    public ClientListSummary preview(long userId, byte[] bytes, String fileName) {
        String name = fileName == null || fileName.isBlank() ? "client-list.xlsx" : fileName.strip();
        RawWorkbook workbook;
        try {
            workbook = reader.read(bytes, name);
        } catch (WorkbookRejectedException e) {
            HttpStatus status = "file:too-large".equals(e.code()) ? HttpStatus.CONTENT_TOO_LARGE
                    : HttpStatus.BAD_REQUEST;
            throw new ApiException(status, e.code(), "File not accepted", e.getMessage());
        }
        return preview(userId, workbook, sha256(bytes), name, bytes.length);
    }

    /**
     * The preview of a workbook already read (the tests call this with a fake
     * workbook).
     */
    @Transactional
    public ClientListSummary preview(long userId, RawWorkbook workbook, String digest, String fileName, long size) {
        shared.committedJobWithDigest(digest).ifPresent(id -> {
            throw ApiException.conflict("import:duplicate-file", "Already imported",
                    "This exact file was already imported (import " + id + ").");
        });
        ClientListParser.Result parsed = parser.parse(workbook, store.aliases());

        List<SheetConsultant> sheetConsultants = sheetConsultantsOf(parsed.rows());
        Map<String, ConsultantRef> byName = shared.consultantsByName();
        ConsultantResolver.Plan plan = planFor(sheetConsultants, byName);
        Map<String, ConsultantRef> consultants = rowConsultants(withPlan(byName, plan), sheetConsultants);
        Map<String, Long> employers = shared.employersByName();
        Map<String, Long> quotations = store.productQuotationsByName();

        List<ParseIssue> issues = new ArrayList<>(parsed.issues());
        List<ClientListStore.NewRow> staged = new ArrayList<>();
        Map<String, Integer> firstRowOfId = new HashMap<>();
        Set<String> newEmployers = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> newQuotations = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        int[] counts = new int[6]; // new, update, unchanged, conflict, duplicate, invalid
        for (ClientListParser.Row row : parsed.rows()) {
            if (row.rejected()) {
                staged.add(stagedRow(0, row, "INVALID", null, null, false, false));
                counts[5]++;
                continue;
            }
            ProtectedIdNumber id = protector.protect(row.idNumber());
            String hashKey = HexFormat.of().formatHex(id.lookupHash());
            Integer earlier = firstRowOfId.putIfAbsent(hashKey, row.rowNumber());
            if (earlier != null) {
                issues.add(rowIssue(parsed, row, ParseIssue.Severity.WARNING, DUPLICATE_ID, "ID number",
                        "Same ID number as row " + earlier + "; only row " + earlier + " is imported."));
                staged.add(stagedRow(0, row, "DUPLICATE", id, null, false, false));
                counts[4]++;
                continue;
            }
            ConsultantRef consultant = row.consultantName() == null ? null
                    : consultants.get(WeeklyImportStore.key(row.consultantName()));
            boolean registers = row.consultantName() != null && consultant == null
                    && plan.registers(row.consultantName());
            if (row.employerName() != null && !employers.containsKey(WeeklyImportStore.key(row.employerName()))) {
                newEmployers.add(row.employerName());
            }
            if (row.productQuotation() != null
                    && !quotations.containsKey(WeeklyImportStore.key(row.productQuotation()))) {
                newQuotations.add(row.productQuotation());
            }

            var existing = store.findClientByHash(id.lookupHash());
            if (existing.isPresent()) {
                ExistingClient c = existing.get();
                if (!WeeklyImportStore.key(c.firstName()).equals(WeeklyImportStore.key(row.firstName()))
                        || !WeeklyImportStore.key(c.lastName()).equals(WeeklyImportStore.key(row.lastName()))) {
                    issues.add(rowIssue(parsed, row, ParseIssue.Severity.WARNING, NAME_DIFFERS, "Name",
                            "This ID number belongs to a client stored under a different name. The row is not "
                                    + "imported: check the client in the app, or fix the list and upload it again."));
                    staged.add(stagedRow(0, row, "CONFLICT", id, c, false, false));
                    counts[3]++;
                    continue;
                }
                if (consultant != null && c.currentConsultantId() != null
                        && c.currentConsultantId() != consultant.id()) {
                    issues.add(rowIssue(parsed, row, ParseIssue.Severity.WARNING, CONSULTANT_DIFFERS, "Consultant",
                            "The client is already assigned to another consultant. The assignment is not changed."));
                }
                if (consultant != null && c.currentConsultantId() == null
                        && "UNASSIGN".equals(shared.lastAssignmentChange(c.id()).orElse(null))) {
                    issues.add(rowIssue(parsed, row, ParseIssue.Severity.WARNING, UNASSIGNED_BY_MANAGER, "Consultant",
                            "A manager removed this client's consultant. The import does not assign the client again."));
                }
                boolean fills = ClientListStore.wouldFill(c, row.employerName() != null, row.email(),
                        row.cellphone(), row.workPhone(), row.homePhone(), row.productQuotation() != null,
                        row.fileNumber(), row.fileId(),
                        row.paymentMethod() == null ? null : row.paymentMethod().name());
                staged.add(stagedRow(0, row, fills ? "UPDATE" : "UNCHANGED", id, c, false, false));
                counts[fills ? 1 : 2]++;
            } else {
                boolean assign = (consultant != null && consultant.active()) || registers;
                if (consultant != null && !consultant.active()) {
                    issues.add(rowIssue(parsed, row, ParseIssue.Severity.WARNING, CONSULTANT_NOT_ACTIVE, "Consultant",
                            "The consultant is " + consultant.status() + ": the new client is left unassigned."));
                }
                staged.add(stagedRow(0, row, "NEW", id, null, true, assign));
                counts[0]++;
            }
        }
        if (!newEmployers.isEmpty()) {
            issues.add(new ParseIssue(ParseIssue.Severity.INFO, NEW_EMPLOYERS, parsed.sheetName(), null, "Employer",
                    newEmployers.size() + " employer name(s) match no employer. They are created at confirm, marked "
                            + "needs review, so an admin can merge them later."));
        }
        if (!newQuotations.isEmpty()) {
            issues.add(new ParseIssue(ParseIssue.Severity.INFO, NEW_PRODUCT_QUOTATIONS, parsed.sheetName(), null,
                    "Product quotation", newQuotations.size() + " product quotation(s) are not in the list yet. "
                            + "They are added at confirm."));
        }

        int warnings = (int) issues.stream().filter(i -> i.severity() == ParseIssue.Severity.WARNING).count();
        long jobId = store.insertJob(new ClientListStore.NewJob(cut(fileName, 255), digest, size, userId,
                parsed.sheetName(), staged.size(), counts[0], counts[1], counts[2], counts[5], counts[3], counts[4],
                warnings, clock.instant().plus(PREVIEW_LIFETIME), parsed.columns()));
        Map<Integer, Integer> warningsPerRow = new HashMap<>();
        for (ParseIssue i : issues) {
            if (i.row() != null && i.severity() == ParseIssue.Severity.WARNING) {
                warningsPerRow.merge(i.row(), 1, Integer::sum);
            }
        }
        for (ClientListStore.NewRow r : staged) {
            store.insertRow(withJob(r, jobId, warningsPerRow.getOrDefault(r.rowNumber(), 0)));
        }
        for (ParseIssue i : issues) {
            shared.insertIssue(jobId, i.sheet(), i.row(), i.field(), i.code(), i.severity().name(), i.message());
        }
        audit.record("IMPORT_PREVIEWED", "IMPORT_JOB", jobId, meta("importType", "CLIENT_LIST", "rows",
                staged.size(), "newClients", counts[0], "existingClients", counts[1] + counts[2], "conflicts",
                counts[3], "duplicates", counts[4], "rejected", counts[5], "fileDigest", digest));
        return summary(jobId);
    }

    private static ClientListStore.NewRow stagedRow(long jobId, ClientListParser.Row r, String action,
            ProtectedIdNumber id, ExistingClient existing, boolean creates, boolean assigns) {
        // INVALID rows keep only the row number and what the screen needs to find the
        // row.
        boolean invalid = "INVALID".equals(action);
        return new ClientListStore.NewRow(jobId, r.rowNumber(), action, id == null ? null : id.lookupHash(),
                id == null ? null : id.encrypted(), id == null ? null : id.last4(), cut(r.firstName(), 100),
                cut(r.lastName(), 100), cut(r.consultantName(), 200), invalid ? null : r.consultantEmail(),
                invalid ? null : r.email(), invalid ? null : r.cellphone(), invalid ? null : r.workPhone(),
                invalid ? null : r.homePhone(), invalid ? null : r.employerName(),
                invalid || r.paymentMethod() == null ? null : r.paymentMethod().name(),
                invalid ? null : r.productQuotation(), invalid ? null : r.fileNumber(), invalid ? null : r.fileId(),
                existing == null ? null : existing.id(), existing == null ? null : existing.version(), creates,
                assigns, 0);
    }

    private static ClientListStore.NewRow withJob(ClientListStore.NewRow r, long jobId, int warnings) {
        return new ClientListStore.NewRow(jobId, r.rowNumber(), r.action(), r.idNumberHash(), r.idNumberEncrypted(),
                r.idNumberLast4(), r.firstName(), r.lastName(), r.consultantName(), r.consultantEmail(), r.email(),
                r.cellphone(), r.workPhone(), r.homePhone(), r.employerName(), r.paymentMethod(), r.productQuotation(),
                r.fileNumber(), r.fileId(), r.existingClientId(), r.existingClientVersion(), r.createsClient(),
                r.assignsConsultant(), warnings);
    }

    private static ParseIssue rowIssue(ClientListParser.Result parsed, ClientListParser.Row row,
            ParseIssue.Severity severity, String code, String field, String message) {
        return new ParseIssue(severity, code, parsed.sheetName(), row.rowNumber(), field, message);
    }

    // ---------- summary ----------

    @Transactional(readOnly = true)
    public ClientListSummary summary(long jobId) {
        Job job = store.findJob(jobId).orElseThrow(() -> notFound(jobId));
        Map<String, Long> issueCounts = shared.issueCounts(jobId);
        boolean open = "PREVIEWED".equals(job.state()) && !job.expired(clock.instant());

        Consultants consultants = null;
        List<String> newEmployers = List.of();
        List<String> newQuotations = List.of();
        Additions additions = null;
        List<String> blockers = new ArrayList<>();
        if (open) {
            Map<String, ConsultantRef> byName = shared.consultantsByName();
            List<SheetConsultant> sheet = shared.sheetConsultants(jobId);
            ConsultantResolver.Plan plan = planFor(sheet, byName);
            consultants = consultantsView(plan, rowConsultants(withPlan(byName, plan), sheet), sheet);
            Map<String, Long> employers = shared.employersByName();
            newEmployers = store.distinctStaged(jobId, "employer_name").stream()
                    .filter(e -> !employers.containsKey(WeeklyImportStore.key(e))).toList();
            Map<String, Long> quotations = store.productQuotationsByName();
            newQuotations = store.distinctStaged(jobId, "product_quotation_name").stream()
                    .filter(q -> !quotations.containsKey(WeeklyImportStore.key(q))).toList();
            ClientListStore.Additions a = store.additions(jobId);
            additions = new Additions(a.debitOrders(), a.withCellphone(), a.withWorkPhone(), a.withHomePhone(),
                    a.withEmail(), a.withFileNumber(), a.withFileId(), a.withEmployer(), a.withProductQuotation(),
                    a.withoutConsultant());
            if (shared.blockingIssueCount(jobId) > 0) {
                blockers.add("FILE_ERRORS");
            }
            if (!plan.conflicts().isEmpty()) {
                blockers.add("CONSULTANT_CONFLICTS");
            }
            if (job.newCount() + job.updateCount() + job.unchangedCount() == 0) {
                blockers.add("NOTHING_TO_IMPORT");
            }
        } else if ("PREVIEWED".equals(job.state())) {
            blockers.add("EXPIRED");
        } else {
            blockers.add("FINISHED");
        }

        List<ColumnView> columns = store.columns(jobId).stream()
                .map(c -> new ColumnView(c.header(), c.field())).toList();
        Result result = "COMMITTED".equals(job.state()) ? new Result(job.newClientCount(), job.appliedCount(),
                job.staleCount(), job.consultantsRegistered(), job.clientsLinked()) : null;
        return new ClientListSummary(job.id(), job.expired(clock.instant()) ? "EXPIRED" : job.state(),
                job.fileName(), job.fileDigest(), job.sheetName(), job.uploadedByEmail(), job.createdAt(),
                job.expiresAt(), job.committedAt(),
                new RowCounts(job.totalRows(), job.newCount(), job.updateCount(), job.unchangedCount(),
                        job.conflictCount(), job.duplicateCount(), job.invalidCount()),
                new IssueCounts(issueCounts.getOrDefault("ERROR", 0L), issueCounts.getOrDefault("WARNING", 0L),
                        issueCounts.getOrDefault("INFO", 0L)),
                columns, consultants, additions, newEmployers, newQuotations, List.copyOf(blockers),
                open && blockers.isEmpty(), result);
    }

    // ---------- consultants (the weekly register's rules) ----------

    private ConsultantResolver.Plan planFor(List<SheetConsultant> sheet, Map<String, ConsultantRef> byName) {
        return ConsultantResolver.plan(sheet, byName, shared.consultantsByEmail(), shared.consultantEmails(),
                shared.ambiguousConsultantNames());
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

    /**
     * The consultant of each sheet name: byName without names that match more
     * than one consultant, plus every name whose sheet email belongs to a
     * consultant (the email wins). Missing = registered at confirm, or a conflict.
     */
    private Map<String, ConsultantRef> rowConsultants(Map<String, ConsultantRef> byName, List<SheetConsultant> sheet) {
        Map<String, ConsultantRef> out = new HashMap<>(byName);
        shared.ambiguousConsultantNames().keySet().forEach(out::remove);
        Map<String, ConsultantRef> byEmail = shared.consultantsByEmail();
        for (SheetConsultant s : sheet) {
            if (s.email() != null && byEmail.containsKey(s.email())) {
                out.put(WeeklyImportStore.key(s.name()), byEmail.get(s.email()));
            }
        }
        return out;
    }

    private static List<SheetConsultant> sheetConsultantsOf(List<ClientListParser.Row> rows) {
        Map<List<String>, Integer> counts = new LinkedHashMap<>();
        for (ClientListParser.Row r : rows) {
            if (!r.rejected() && r.consultantName() != null) {
                counts.merge(java.util.Arrays.asList(r.consultantName(), r.consultantEmail()), 1, Integer::sum);
            }
        }
        List<SheetConsultant> out = new ArrayList<>();
        counts.forEach((k, n) -> out.add(new SheetConsultant(k.get(0), k.get(1), n)));
        return out;
    }

    /**
     * The consultant list the admin checks, and the fingerprint confirm must send
     * back.
     */
    private static Consultants consultantsView(ConsultantResolver.Plan plan, Map<String, ConsultantRef> rowConsultants,
            List<SheetConsultant> sheet) {
        List<String> lines = new ArrayList<>();
        List<ClientListViews.ToRegister> toRegister = new ArrayList<>();
        for (ConsultantResolver.Registration r : plan.registrations()) {
            toRegister.add(new ClientListViews.ToRegister(r.fullName(), r.email(), r.otherNames(), r.rows()));
            lines.add("R|" + WeeklyImportStore.key(r.fullName()) + "|" + nz(r.email()) + "|"
                    + String.join(",", r.otherNames().stream().map(WeeklyImportStore::key).sorted().toList()) + "|"
                    + r.rows());
        }
        List<ClientListViews.EmailToAdd> emails = new ArrayList<>();
        for (ConsultantResolver.EmailUpdate u : plan.emailUpdates()) {
            emails.add(new ClientListViews.EmailToAdd(u.consultant().id(), u.consultant().code(),
                    u.consultant().fullName(), u.email()));
            lines.add("E|" + u.consultant().id() + "|" + u.email());
        }
        for (ConsultantResolver.AliasAdd a : plan.aliases()) {
            lines.add("A|" + a.consultant().id() + "|" + WeeklyImportStore.key(a.alias()));
        }
        List<ClientListViews.Conflict> conflicts = new ArrayList<>();
        for (ConsultantResolver.Conflict c : plan.conflicts()) {
            conflicts.add(new ClientListViews.Conflict(c.nameInSheet(), c.email(), c.reason(), c.rows()));
            lines.add("C|" + WeeklyImportStore.key(c.nameInSheet()) + "|" + nz(c.email()));
        }
        Map<Long, ConsultantRef> refs = new LinkedHashMap<>();
        Map<Long, Set<String>> names = new LinkedHashMap<>();
        Map<Long, Integer> rows = new HashMap<>();
        for (SheetConsultant s : sheet) {
            ConsultantRef ref = rowConsultants.get(WeeklyImportStore.key(s.name()));
            if (ref == null || plan.registers(s.name()) || plan.inConflict(s.name())) {
                continue;
            }
            refs.putIfAbsent(ref.id(), ref);
            names.computeIfAbsent(ref.id(), k -> new LinkedHashSet<>()).add(s.name().strip().replaceAll("\\s+", " "));
            rows.merge(ref.id(), s.rows(), Integer::sum);
        }
        List<ClientListViews.Matched> matched = new ArrayList<>();
        refs.forEach((id, ref) -> {
            matched.add(new ClientListViews.Matched(id, ref.code(), ref.fullName(), ref.status(),
                    List.copyOf(names.get(id)), rows.get(id)));
            lines.add("M|" + id + "|" + ref.status() + "|"
                    + String.join(",", names.get(id).stream().map(WeeklyImportStore::key).sorted().toList()));
        });
        matched.sort(java.util.Comparator.comparing(ClientListViews.Matched::consultantCode));
        Collections.sort(lines);
        String fingerprint = sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return new Consultants(fingerprint, List.copyOf(matched), List.copyOf(toRegister), List.copyOf(emails),
                List.copyOf(conflicts));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * Registers the list's new consultants, fills in missing emails and adds the
     * list's spellings as aliases. Inside confirm's transaction.
     *
     * @return {registered, emails added, aliases added}
     */
    private int[] applyConsultantPlan(long userId, long jobId, ConsultantResolver.Plan plan) {
        int registered = 0;
        int emails = 0;
        int aliases = 0;
        for (ConsultantResolver.Registration r : plan.registrations()) {
            String code = codes.next();
            long id = shared.createConsultant(code, r.fullName(), r.email());
            shared.insertConsultantAlias(id, r.fullName(), userId);
            for (String other : r.otherNames()) {
                shared.insertConsultantAlias(id, other, userId);
                aliases++;
            }
            audit.record("CONSULTANT_CREATED", "CONSULTANT", id, meta("consultantCode", code, "fullName",
                    r.fullName(), "email", r.email(), "codeGenerated", true, "importJobId", jobId,
                    "source", r.email() == null ? "CLIENT_LIST_NAME" : "CLIENT_LIST_EMAIL"));
            registered++;
        }
        for (ConsultantResolver.EmailUpdate u : plan.emailUpdates()) {
            if (!shared.setConsultantEmailIfMissing(u.consultant().id(), u.email())) {
                throw ApiException.conflict("import:stale-preview", "Data changed",
                        "Consultant " + u.consultant().code() + " was given an email since the preview. "
                                + "Upload the file again.");
            }
            audit.record("CONSULTANT_EMAIL_SET", "CONSULTANT", u.consultant().id(),
                    meta("email", u.email(), "importJobId", jobId));
            emails++;
        }
        for (ConsultantResolver.AliasAdd a : plan.aliases()) {
            shared.insertConsultantAlias(a.consultant().id(), a.alias(), userId);
            audit.record("CONSULTANT_ALIAS_CREATED", "CONSULTANT", a.consultant().id(),
                    meta("alias", a.alias(), "importJobId", jobId, "source", "CLIENT_LIST_EMAIL"));
            aliases++;
        }
        return new int[] { registered, emails, aliases };
    }

    // ---------- confirm ----------

    @Transactional
    public ConfirmResult confirm(long userId, long jobId, ConfirmRequest request) {
        Job job = requireOpen(store.lockJob(jobId).orElseThrow(() -> notFound(jobId)));
        if (!job.fileDigest().equals(request.fileDigest())) {
            throw ApiException.conflict("import:digest-mismatch", "Different file",
                    "The confirmation does not match the uploaded file. Reload the preview and try again.");
        }
        if (shared.blockingIssueCount(job.id()) > 0) {
            throw ApiException.unprocessable("import:blocking-errors", "File has errors",
                    "Fix the errors listed in the preview, then upload the file again.");
        }
        Map<String, ConsultantRef> known = shared.consultantsByName();
        List<SheetConsultant> sheet = shared.sheetConsultants(job.id());
        ConsultantResolver.Plan plan = planFor(sheet, known);
        if (!plan.conflicts().isEmpty()) {
            throw ApiException.unprocessable("import:consultant-conflicts", "Consultant conflicts",
                    plan.conflicts().size() + " consultant name(s) cannot be linked to one consultant safely. Fix "
                            + "them in the list (consultants.conflicts in the preview) and upload it again.");
        }
        Consultants consultants = consultantsView(plan, rowConsultants(withPlan(known, plan), sheet), sheet);
        if (!consultants.fingerprint().equals(request.consultantsFingerprint())) {
            throw ApiException.conflict("import:consultants-changed", "Consultants changed",
                    "The consultants changed since the preview was shown. Reload it, check them and confirm again.");
        }
        if (job.newCount() + job.updateCount() + job.unchangedCount() == 0) {
            throw ApiException.unprocessable("import:nothing-to-import", "Nothing to import",
                    "No row of this list can be imported. See the issues.");
        }
        shared.committedJobWithDigest(job.fileDigest()).ifPresent(id -> {
            throw ApiException.conflict("import:duplicate-file", "Already imported",
                    "This exact file was already imported (import " + id + ").");
        });

        int[] consultantChanges = applyConsultantPlan(userId, job.id(), plan);
        Map<String, ConsultantRef> rowConsultants = rowConsultants(shared.consultantsByName(), sheet);
        Map<String, Long> employers = shared.employersByName();
        Map<String, Long> quotations = store.productQuotationsByName();
        int newClients = 0;
        int updated = 0;
        int unchanged = 0;
        int assigned = 0;
        int withoutConsultant = 0;
        int employersCreated = 0;
        int quotationsCreated = 0;
        int stale = 0;
        int applied = 0;

        for (ClientListStore.RowToApply r : store.rowsToApply(job.id())) {
            ConsultantRef consultant = r.consultantName() == null ? null
                    : rowConsultants.get(WeeklyImportStore.key(r.consultantName()));
            Long employerId = null;
            if (r.employerName() != null) {
                String key = WeeklyImportStore.key(r.employerName());
                employerId = employers.get(key);
                if (employerId == null) {
                    employerId = shared.createEmployer(r.employerName());
                    employers.put(key, employerId);
                    employersCreated++;
                }
            }
            Long quotationId = null;
            if (r.productQuotation() != null) {
                String key = WeeklyImportStore.key(r.productQuotation());
                quotationId = quotations.get(key);
                if (quotationId == null) {
                    quotationId = store.createProductQuotation(r.productQuotation());
                    quotations.put(key, quotationId);
                    quotationsCreated++;
                }
            }

            var existing = store.lockClientByHash(r.idNumberHash());
            if (existing.isPresent()) {
                ExistingClient c = existing.get();
                if (!WeeklyImportStore.key(c.firstName()).equals(WeeklyImportStore.key(r.firstName()))
                        || !WeeklyImportStore.key(c.lastName()).equals(WeeklyImportStore.key(r.lastName()))) {
                    // Added or renamed under another name since the preview: never overwritten.
                    store.setRowOutcome(r.id(), "STALE");
                    stale++;
                    continue;
                }
                if ("NEW".equals(r.action())) {
                    stale++; // someone added the client meanwhile; filled in like an existing one
                }
                List<String> filled = store.fillMissing(c, employerId, r.email(), r.cellphone(), r.workPhone(),
                        r.homePhone(), quotationId, r.fileNumber(), r.fileId(), r.paymentMethod());
                if (filled.isEmpty()) {
                    unchanged++;
                } else {
                    updated++;
                    audit.record("CLIENT_UPDATED", "CLIENT", c.id(), meta("fields", String.join(",", filled),
                            "importJobId", job.id(), "source", "CLIENT_LIST"));
                }
                if (c.currentConsultantId() == null && consultant != null && consultant.active()
                        && !"UNASSIGN".equals(shared.lastAssignmentChange(c.id()).orElse(null))
                        && shared.assignIfUnassigned(c.id(), consultant.id())) {
                    shared.insertImportHistory(c.id(), consultant.id(), HISTORY_REASON, userId, job.id());
                    assigned++;
                }
            } else {
                if (r.idNumberEncrypted() == null) {
                    throw ApiException.conflict("import:stale-preview", "Data changed",
                            "A client in this import was removed since the preview. Upload the file again.");
                }
                if (!"NEW".equals(r.action())) {
                    throw ApiException.conflict("import:stale-preview", "Data changed",
                            "A client in this list was removed since the preview. Upload the file again.");
                }
                Long assignTo = consultant != null && consultant.active() ? consultant.id() : null;
                long clientId = store.insertClient(new ClientListStore.NewClient(r.idNumberHash(),
                        r.idNumberEncrypted(), r.idNumberLast4(), r.firstName(), r.lastName(), assignTo,
                        r.paymentMethod() == null ? employerId : null, r.email(), r.cellphone(), r.workPhone(),
                        r.homePhone(), quotationId, r.fileNumber(), r.fileId(), r.paymentMethod(), job.id()));
                audit.record("CLIENT_CREATED", "CLIENT", clientId,
                        meta("consultantId", assignTo, "importJobId", job.id(), "source", "CLIENT_LIST"));
                newClients++;
                if (assignTo != null) {
                    shared.insertImportHistory(clientId, assignTo, HISTORY_REASON, userId, job.id());
                    assigned++;
                } else {
                    withoutConsultant++;
                }
            }
            store.setRowOutcome(r.id(), "APPLIED");
            applied++;
        }
        store.markNotApplied(job.id());
        int skipped = job.invalidCount() + job.duplicateCount() + job.conflictCount();
        int consultantsMatched = consultants.matched().size();
        store.markCommitted(job.id(), applied, stale, newClients, consultantsMatched + consultantChanges[0],
                consultantChanges[0], consultantsMatched, newClients + updated + unchanged, skipped + stale);
        audit.record("IMPORT_CONFIRMED", "IMPORT_JOB", job.id(), meta("importType", "CLIENT_LIST", "newClients",
                newClients, "updatedClients", updated, "unchangedClients", unchanged, "assigned", assigned,
                "consultantsRegistered", consultantChanges[0], "consultantEmailsAdded", consultantChanges[1],
                "consultantAliasesAdded", consultantChanges[2], "employersCreated", employersCreated,
                "productQuotationsCreated", quotationsCreated, "staleRows", stale, "skippedRows", skipped));
        return new ConfirmResult(job.id(), newClients, updated, unchanged, assigned, withoutConsultant,
                consultantChanges[0], consultantChanges[1], consultantChanges[2], employersCreated, quotationsCreated,
                stale, skipped);
    }

    // ---------- lists, rows, issues ----------

    @Transactional(readOnly = true)
    public PageResponse<ListItem> list(int page, int size) {
        int s = clampSize(size);
        int p = Math.max(page, 0);
        List<ListItem> items = store.listJobs(s, (long) p * s).stream()
                .map(j -> new ListItem(j.id(), j.expired(clock.instant()) ? "EXPIRED" : j.state(), j.fileName(),
                        j.sheetName(), j.uploadedByEmail(), j.totalRows(), j.newCount(), j.createdAt(),
                        j.committedAt()))
                .toList();
        return page(items, p, s, store.countJobs());
    }

    @Transactional(readOnly = true)
    public PageResponse<StagedRowView> rows(long jobId, String action, int page, int size) {
        store.findJob(jobId).orElseThrow(() -> notFound(jobId));
        int s = clampSize(size);
        int p = Math.max(page, 0);
        String a = blankToNull(action) == null ? null : action.strip().toUpperCase(Locale.ROOT);
        List<StagedRowView> items = store.rows(jobId, a, s, (long) p * s).stream()
                .map(r -> new StagedRowView(r.rowNumber(), r.action(), r.firstName(), r.lastName(),
                        r.idNumberLast4() == null ? null : SaIdNumber.mask(r.idNumberLast4()), r.consultantName(),
                        r.createsClient(), r.assignsConsultant(), r.warningCount(), r.outcome()))
                .toList();
        return page(items, p, s, store.countRows(jobId, a));
    }

    @Transactional(readOnly = true)
    public PageResponse<IssueView> issues(long jobId, String severity, int page, int size) {
        store.findJob(jobId).orElseThrow(() -> notFound(jobId));
        int s = clampSize(size);
        int p = Math.max(page, 0);
        String sev = blankToNull(severity) == null ? null : severity.strip().toUpperCase(Locale.ROOT);
        List<IssueView> items = shared.issues(jobId, sev, s, (long) p * s).stream()
                .map(i -> new IssueView(i.rowNumber(), i.field(), i.code(), i.severity(), i.message())).toList();
        return page(items, p, s, shared.countIssues(jobId, sev));
    }

    /**
     * The issues as a CSV file Excel opens; no names, only the ID number's last
     * four digits.
     */
    @Transactional
    public IssueFile exportIssues(long jobId, String severity) {
        Job job = store.findJob(jobId).orElseThrow(() -> notFound(jobId));
        String sev = blankToNull(severity) == null ? null : severity.strip().toUpperCase(Locale.ROOT);
        List<ClientListStore.IssueLine> lines = store.issueLines(jobId, sev);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append(csvLine("Row", "Field", "Severity", "Code", "Message", "ID number (last 4)"));
        for (ClientListStore.IssueLine l : lines) {
            csv.append(csvLine(l.rowNumber() == null ? "" : l.rowNumber().toString(), l.field(), l.severity(),
                    l.code(), l.message(), l.idNumberLast4() == null ? "" : "*********" + l.idNumberLast4()));
        }
        audit.record("IMPORT_ISSUES_EXPORTED", "IMPORT_JOB", job.id(), meta("importType", "CLIENT_LIST",
                "severity", sev, "lines", lines.size()));
        return new IssueFile("client-list-import-" + job.id() + "-issues.csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
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

    // ---------- cancel, expiry ----------

    @Transactional
    public ClientListSummary cancel(long jobId) {
        Job job = requireOpen(store.lockJob(jobId).orElseThrow(() -> notFound(jobId)));
        store.markFinished(job.id(), "CANCELLED");
        audit.record("IMPORT_CANCELLED", "IMPORT_JOB", job.id(), meta("importType", "CLIENT_LIST"));
        return summary(job.id());
    }

    /**
     * ClientListCleanupJob: previews past their 24 hours become EXPIRED and lose
     * their personal data.
     */
    @Transactional
    public int expirePreviews() {
        int expired = 0;
        for (long id : store.expiredPreviewIds(500)) {
            Job job = store.lockJob(id).orElse(null);
            if (job == null || !"PREVIEWED".equals(job.state()) || job.expiresAt().isAfter(clock.instant())) {
                continue;
            }
            store.markFinished(id, "EXPIRED");
            audit.record("IMPORT_EXPIRED", "IMPORT_JOB", id, meta("importType", "CLIENT_LIST", "expiresAt",
                    job.expiresAt().toString()));
            expired++;
        }
        return expired;
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
        return ApiException.notFound("import:not-found", "Client list import " + jobId + " does not exist.");
    }

    // ---------- helpers ----------

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static int clampSize(int size) {
        return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    }

    private static <T> PageResponse<T> page(List<T> items, int page, int size, long total) {
        return new PageResponse<>(items, page, size, total, (int) ((total + size - 1) / size));
    }
}
