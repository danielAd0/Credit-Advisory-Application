package za.co.mafori.creditadvisory.imports.weekly;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class WeeklyImportStore {

    private final JdbcTemplate jdbc;

    public WeeklyImportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String key(String name) {
        return name == null ? null : name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public record Job(long id, String fileName, String fileDigest, long fileSizeBytes, String state,
            LocalDate weekStarting, long uploadedByUserId, String uploadedByEmail, int totalRows, int newCount,
            int unchangedCount, int invalidCount, int duplicateCount, int appliedCount, int staleCount,
            int warningCount, Long reportingWeekId, Long replacesImportJobId, Instant expiresAt,
            Instant committedAt, Instant createdAt) {

        public boolean expired(Instant now) {
            return "PREVIEWED".equals(state) && expiresAt.isBefore(now);
        }
    }

    private static final String JOB_COLUMNS = """
            j.id, j.file_name, j.file_digest, j.file_size_bytes, j.state, j.week_starting, j.uploaded_by_user_id,
            u.email, j.total_rows, j.new_count, j.unchanged_count, j.invalid_count, j.duplicate_count,
            j.applied_count, j.stale_count, j.warning_count, j.reporting_week_id, j.replaces_import_job_id,
            j.expires_at, j.committed_at, j.created_at
            """;

    private static Job job(ResultSet rs, int n) throws SQLException {
        return new Job(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                localDate(rs, 6), rs.getLong(7), rs.getString(8), rs.getInt(9), rs.getInt(10), rs.getInt(11),
                rs.getInt(12), rs.getInt(13), rs.getInt(14), rs.getInt(15), rs.getInt(16), nullableLong(rs, 17),
                nullableLong(rs, 18), instant(rs, 19), instant(rs, 20), instant(rs, 21));
    }

    public record NewJob(String fileName, String fileDigest, long fileSizeBytes, long uploadedByUserId,
            LocalDate weekStarting, int totalRows, int newCount, int unchangedCount, int invalidCount,
            int duplicateCount, int warningCount, Instant expiresAt) {
    }

    public long insertJob(NewJob j) {
        return jdbc.queryForObject("""
                insert into import_jobs (file_name, file_digest, file_size_bytes, state, uploaded_by_user_id,
                    import_type, week_starting, total_rows, new_count, unchanged_count, invalid_count,
                    duplicate_count, new_client_count, warning_count, expires_at)
                values (?, ?, ?, 'PREVIEWED', ?, 'WEEKLY_REGISTER', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, j.fileName(), j.fileDigest(), j.fileSizeBytes(), j.uploadedByUserId(),
                j.weekStarting(), j.totalRows(), j.newCount(), j.unchangedCount(), j.invalidCount(),
                j.duplicateCount(), j.newCount(), j.warningCount(), Timestamp.from(j.expiresAt()));
    }

    public Optional<Job> findJob(long id) {
        return jdbc.query("select " + JOB_COLUMNS
                + " from import_jobs j join users u on u.id = j.uploaded_by_user_id"
                + " where j.id = ? and j.import_type = 'WEEKLY_REGISTER'", WeeklyImportStore::job, id)
                .stream().findFirst();
    }

    public Optional<Job> lockJob(long id) {
        jdbc.query("select id from import_jobs where id = ? for update", (rs, n) -> rs.getLong(1), id);
        return findJob(id);
    }

    public List<Job> listJobs(int limit, long offset) {
        return jdbc.query("select " + JOB_COLUMNS
                + " from import_jobs j join users u on u.id = j.uploaded_by_user_id"
                + " where j.import_type = 'WEEKLY_REGISTER' order by j.created_at desc, j.id desc limit ? offset ?",
                WeeklyImportStore::job, limit, offset);
    }

    public long countJobs() {
        return jdbc.queryForObject("select count(*) from import_jobs where import_type = 'WEEKLY_REGISTER'",
                Long.class);
    }

    public Optional<Long> committedJobWithDigest(String digest) {
        return jdbc
                .query("select id from import_jobs where file_digest = ? and state = 'COMMITTED' order by id limit 1",
                        (rs, n) -> rs.getLong(1), digest)
                .stream().findFirst();
    }

    public Optional<Long> currentJobForWeek(LocalDate week) {
        return jdbc.query("select current_import_job_id from reporting_weeks where week_starting = ?"
                + " and current_import_job_id is not null", (rs, n) -> rs.getLong(1), week).stream().findFirst();
    }

    /** Previews past their expiry time, oldest first (the clean-up job). */
    public List<Long> expiredPreviewIds(int limit) {
        return jdbc.query("""
                select id from import_jobs
                where import_type = 'WEEKLY_REGISTER' and state = 'PREVIEWED' and expires_at < now()
                order by expires_at, id limit ?
                """, (rs, n) -> rs.getLong(1), limit);
    }

    public void markFinished(long jobId, String state) {
        jdbc.update("update import_jobs set state = ?, personal_data_purged_at = now(), version = version + 1"
                + " where id = ? and state = 'PREVIEWED'", state, jobId);
        purgePersonalData(jobId);
    }

    public void purgePersonalData(long jobId) {
        jdbc.update("""
                update import_rows set id_number_hash = null, id_number_encrypted = null,
                    first_name = null, last_name = null
                where import_job_id = ?
                """, jobId);
    }

    public record Commit(long jobId, long reportingWeekId, Long replacesJobId, String replaceReason,
            int appliedCount, int staleCount, int newClientCount) {
    }

    public void markCommitted(Commit c) {
        jdbc.update("""
                update import_jobs set state = 'COMMITTED', committed_at = now(), reporting_week_id = ?,
                    replaces_import_job_id = ?, replace_reason = ?, applied_count = ?, stale_count = ?,
                    new_client_count = ?, personal_data_purged_at = now(), version = version + 1
                where id = ?
                """, c.reportingWeekId(), c.replacesJobId(), c.replaceReason(), c.appliedCount(), c.staleCount(),
                c.newClientCount(), c.jobId());
        purgePersonalData(c.jobId());
    }

    public record NewRow(long jobId, String sheetName, int rowNumber, int dayOfWeek, LocalDate transactionDate,
            String action, byte[] idNumberHash, byte[] idNumberEncrypted, String idNumberLast4, String firstName,
            String lastName, String consultantName, String employerName, String structureName,
            String paymentTermName, BigDecimal quotation, BigDecimal banked, BigDecimal amountToClient,
            BigDecimal thirdPartySettlement, String primaryBudgetList, BigDecimal primaryLoanAmount,
            String secondaryBudgetList, BigDecimal secondaryLoanAmount, String extrasJson, Long existingClientId,
            Long existingClientVersion, boolean createsClient, boolean assignsConsultant, int warningCount) {
    }

    public long insertRow(NewRow r) {
        return jdbc.queryForObject(
                """
                        insert into import_rows (import_job_id, sheet_name, row_number, day_of_week, transaction_date, action,
                            id_number_hash, id_number_encrypted, id_number_last4, first_name, last_name, consultant_name,
                            employer_name, structure_name, payment_term_name, quotation_total, banked_total,
                            amount_to_client, third_party_settlement, primary_budget_list, primary_loan_amount,
                            secondary_budget_list, secondary_loan_amount, extras, existing_client_id,
                            existing_client_version, creates_client, assigns_consultant, warning_count)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?)
                        returning id
                        """,
                Long.class, r.jobId(), r.sheetName(), r.rowNumber(), r.dayOfWeek(), r.transactionDate(),
                r.action(), r.idNumberHash(), r.idNumberEncrypted(), r.idNumberLast4(), r.firstName(), r.lastName(),
                r.consultantName(), r.employerName(), r.structureName(), r.paymentTermName(), r.quotation(),
                r.banked(), r.amountToClient(), r.thirdPartySettlement(), r.primaryBudgetList(),
                r.primaryLoanAmount(), r.secondaryBudgetList(), r.secondaryLoanAmount(), r.extrasJson(),
                r.existingClientId(), r.existingClientVersion(), r.createsClient(), r.assignsConsultant(),
                r.warningCount());
    }

    public void insertLine(long rowId, long productId, BigDecimal amount) {
        jdbc.update("insert into import_row_lines (import_row_id, product_id, amount) values (?, ?, ?)", rowId,
                productId, amount);
    }

    public void insertIssue(long jobId, String sheet, Integer row, String field, String code, String severity,
            String message) {
        jdbc.update("""
                insert into import_errors (import_job_id, sheet_name, row_number, field_name, error_code, severity,
                    safe_message)
                values (?, ?, ?, ?, ?, ?, ?)
                """, jobId, truncate(sheet, 32), row, truncate(field, 64), truncate(code, 64), severity,
                truncate(message, 500));
    }

    public record DayCount(String sheetName, int dayOfWeek, LocalDate date, int rows, int rejected,
            BigDecimal quotation, BigDecimal banked) {
    }

    public List<DayCount> dayCounts(long jobId) {
        return jdbc.query("""
                select sheet_name, day_of_week, transaction_date, count(*),
                       count(*) filter (where action = 'INVALID'),
                       coalesce(sum(quotation_total) filter (where action <> 'INVALID'), 0),
                       coalesce(sum(banked_total) filter (where action <> 'INVALID'), 0)
                from import_rows where import_job_id = ?
                group by sheet_name, day_of_week, transaction_date
                order by day_of_week
                """, (rs, n) -> new DayCount(rs.getString(1), rs.getInt(2), localDate(rs, 3), rs.getInt(4),
                rs.getInt(5), rs.getBigDecimal(6), rs.getBigDecimal(7)), jobId);
    }

    public Map<String, Long> issueCounts(long jobId) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("select severity, count(*) from import_errors where import_job_id = ? group by severity",
                (rs, n) -> counts.put(rs.getString(1), rs.getLong(2)), jobId);
        return counts;
    }

    public long blockingIssueCount(long jobId) {
        return jdbc.queryForObject("select count(*) from import_errors where import_job_id = ? and severity = 'ERROR'"
                + " and row_number is null", Long.class, jobId);
    }

    public Map<String, Integer> consultantNames(long jobId) {
        Map<String, Integer> names = new java.util.LinkedHashMap<>();
        jdbc.query("""
                select consultant_name, count(*) from import_rows
                where import_job_id = ? and action <> 'INVALID' and consultant_name is not null
                group by consultant_name order by consultant_name
                """, (rs, n) -> names.put(rs.getString(1), rs.getInt(2)), jobId);
        return names;
    }

    public record StagedRow(long id, String sheetName, int rowNumber, LocalDate transactionDate, String action,
            String outcome, String idNumberLast4, String firstName, String lastName, String consultantName,
            String employerName, BigDecimal quotation, BigDecimal banked, int warningCount, Long existingClientId) {
    }

    private static final String ROW_FILTER = """
            from import_rows where import_job_id = ?
              and (cast(? as varchar) is null or sheet_name = ?)
              and (cast(? as varchar) is null or action = ?)
            """;

    public List<StagedRow> rows(long jobId, String sheet, String action, int limit, long offset) {
        return jdbc.query("""
                select id, sheet_name, row_number, transaction_date, action, outcome, id_number_last4, first_name,
                       last_name, consultant_name, employer_name, quotation_total, banked_total, warning_count,
                       existing_client_id
                """ + ROW_FILTER + " order by day_of_week, row_number limit ? offset ?",
                (rs, n) -> new StagedRow(rs.getLong(1), rs.getString(2), rs.getInt(3), localDate(rs, 4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getInt(14),
                        nullableLong(rs, 15)),
                jobId, sheet, sheet, action, action, limit, offset);
    }

    public long countRows(long jobId, String sheet, String action) {
        return jdbc.queryForObject("select count(*) " + ROW_FILTER, Long.class, jobId, sheet, sheet, action, action);
    }

    public Map<Long, Map<String, BigDecimal>> productsOfRows(List<Long> rowIds) {
        Map<Long, Map<String, BigDecimal>> out = new HashMap<>();
        for (Long rowId : rowIds) {
            Map<String, BigDecimal> lines = new java.util.LinkedHashMap<>();
            jdbc.query("""
                    select p.code, l.amount from import_row_lines l join products p on p.id = l.product_id
                    where l.import_row_id = ? order by p.display_order, p.id
                    """, (rs, n) -> lines.put(rs.getString(1), rs.getBigDecimal(2)), rowId);
            out.put(rowId, lines);
        }
        return out;
    }

    public record Issue(String sheetName, Integer rowNumber, String field, String code, String severity,
            String message) {
    }

    public List<Issue> issues(long jobId, String severity, int limit, long offset) {
        return jdbc.query("""
                select sheet_name, row_number, field_name, error_code, severity, safe_message from import_errors
                where import_job_id = ? and (cast(? as varchar) is null or severity = ?)
                order by case severity when 'ERROR' then 0 when 'WARNING' then 1 else 2 end,
                         row_number is not null, sheet_name nulls first, row_number, id
                limit ? offset ?
                """, (rs, n) -> new Issue(rs.getString(1), nullableInt(rs, 2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6)), jobId, severity, severity, limit, offset);
    }

    public long countIssues(long jobId, String severity) {
        return jdbc.queryForObject("select count(*) from import_errors where import_job_id = ?"
                + " and (cast(? as varchar) is null or severity = ?)", Long.class, jobId, severity, severity);
    }

    public record RowToApply(long id, String sheetName, int rowNumber, int dayOfWeek, LocalDate transactionDate,
            String action, byte[] idNumberHash, byte[] idNumberEncrypted, String idNumberLast4, String firstName,
            String lastName, String consultantName, String employerName, String structureName,
            String paymentTermName, BigDecimal quotation, BigDecimal banked, BigDecimal amountToClient,
            BigDecimal thirdPartySettlement, String primaryBudgetList, BigDecimal primaryLoanAmount,
            String secondaryBudgetList, BigDecimal secondaryLoanAmount) {
    }

    public List<RowToApply> rowsToApply(long jobId) {
        return jdbc.query("""
                select id, sheet_name, row_number, day_of_week, transaction_date, action, id_number_hash,
                       id_number_encrypted, id_number_last4, first_name, last_name, consultant_name, employer_name,
                       structure_name, payment_term_name, quotation_total, banked_total, amount_to_client,
                       third_party_settlement, primary_budget_list, primary_loan_amount, secondary_budget_list,
                       secondary_loan_amount
                from import_rows where import_job_id = ? and action <> 'INVALID'
                order by day_of_week, row_number
                """, (rs, n) -> new RowToApply(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                localDate(rs, 5), rs.getString(6), rs.getBytes(7), rs.getBytes(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15),
                rs.getBigDecimal(16), rs.getBigDecimal(17), rs.getBigDecimal(18), rs.getBigDecimal(19),
                rs.getString(20), rs.getBigDecimal(21), rs.getString(22), rs.getBigDecimal(23)), jobId);
    }

    public void setRowOutcome(long rowId, String outcome) {
        jdbc.update("update import_rows set outcome = ? where id = ?", outcome, rowId);
    }

    public record ConsultantRef(long id, String code, String fullName, String status) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public Map<String, ConsultantRef> consultantsByName() {
        Map<String, ConsultantRef> byName = new HashMap<>();
        jdbc.query("""
                select a.alias, c.id, c.consultant_code, c.full_name, c.status
                from consultant_aliases a join consultants c on c.id = a.consultant_id
                """, (rs, n) -> byName.put(key(rs.getString(1)),
                new ConsultantRef(rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5))));
        jdbc.query("select full_name, id, consultant_code, full_name, status from consultants",
                (rs, n) -> byName.put(key(rs.getString(1)),
                        new ConsultantRef(rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5))));
        return byName;
    }

    public Optional<ConsultantRef> consultant(long id) {
        return jdbc.query("select id, consultant_code, full_name, status from consultants where id = ?",
                (rs, n) -> new ConsultantRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), id)
                .stream().findFirst();
    }

    public boolean aliasExists(String alias) {
        return jdbc.queryForObject("""
                select count(*) > 0 from consultant_aliases
                where lower(regexp_replace(btrim(alias), '\\s+', ' ', 'g')) = ?
                """, Boolean.class, key(alias));
    }

    /**
     * A consultant created from a sheet name
     * (WeeklyImportService.createConsultantsFromSheet).
     */
    public long createConsultant(String code, String fullName) {
        return jdbc.queryForObject(
                "insert into consultants (consultant_code, full_name) values (?, ?) returning id", Long.class,
                code, fullName);
    }

    public void insertConsultantAlias(long consultantId, String alias, long userId) {
        jdbc.update("insert into consultant_aliases (consultant_id, alias, created_by_user_id) values (?, ?, ?)",
                consultantId, alias.strip().replaceAll("\\s+", " "), userId);
    }

    public Map<String, Long> employersByName() {
        Map<String, Long> byName = new HashMap<>();
        jdbc.query("select alias, employer_id from employer_aliases",
                (rs, n) -> byName.put(key(rs.getString(1)), rs.getLong(2)));
        jdbc.query("select name, id from employers", (rs, n) -> byName.put(key(rs.getString(1)), rs.getLong(2)));
        return byName;
    }

    public Map<String, Long> structuresByName() {
        Map<String, Long> byName = new HashMap<>();
        jdbc.query("select name, id from structures", (rs, n) -> byName.put(key(rs.getString(1)), rs.getLong(2)));
        return byName;
    }

    public Map<String, Long> paymentTermsByName() {
        Map<String, Long> byName = new HashMap<>();
        jdbc.query("select code, name, id from payment_terms", (rs, n) -> {
            long id = rs.getLong(3);
            byName.put(key(rs.getString(1).replace('_', ' ')), id);
            byName.put(key(rs.getString(2)), id);
            byName.put(withoutPayment(rs.getString(2)), id);
            return null;
        });
        return byName;
    }

    public static String withoutPayment(String name) {
        return key(name.replaceAll("(?i)\\bpayment\\b", ""));
    }

    public long createEmployer(String name) {
        String clean = name.strip().replaceAll("\\s+", " ");
        jdbc.update(
                "insert into employers (name, needs_review) values (?, true) on conflict ((lower(btrim(name)))) do nothing",
                clean);
        return jdbc.queryForObject("select id from employers where lower(btrim(name)) = lower(btrim(?))", Long.class,
                clean);
    }

    public long createStructure(String name) {
        String clean = name.strip().replaceAll("\\s+", " ");
        jdbc.update("insert into structures (name) values (?) on conflict ((lower(btrim(name)))) do nothing", clean);
        return jdbc.queryForObject("select id from structures where lower(btrim(name)) = lower(btrim(?))", Long.class,
                clean);
    }

    public long createPaymentTerm(String name) {
        String clean = name.strip().replaceAll("\\s+", " ");
        String code = paymentTermCode(clean);
        jdbc.update("insert into payment_terms (code, name) values (?, ?) on conflict (code) do nothing", code,
                truncate(clean, 100));
        return jdbc.queryForObject("select id from payment_terms where code = ?", Long.class, code);
    }

    static String paymentTermCode(String name) {
        String code = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (code.isEmpty() || !Character.isLetter(code.charAt(0))) {
            code = "TERM_" + code;
        }
        if (code.length() < 2) {
            code = code + "_TERM";
        }
        return code.length() > 40 ? code.substring(0, 40).replaceAll("_+$", "") : code;
    }

    public record ExistingClient(long id, long version, Long currentConsultantId, Long employerId, String firstName,
            String lastName) {
    }

    public Optional<ExistingClient> findClientByHash(byte[] hash) {
        return jdbc.query("""
                select id, version, current_consultant_id, employer_id, first_name, last_name
                from clients where id_number_hash = ?
                """, (rs, n) -> new ExistingClient(rs.getLong(1), rs.getLong(2), nullableLong(rs, 3),
                nullableLong(rs, 4), rs.getString(5), rs.getString(6)), hash).stream().findFirst();
    }

    public Optional<ExistingClient> lockClientByHash(byte[] hash) {
        jdbc.query("select id from clients where id_number_hash = ? for update", (rs, n) -> rs.getLong(1), hash);
        return findClientByHash(hash);
    }

    public long insertClient(byte[] hash, byte[] encrypted, String last4, String firstName, String lastName,
            Long consultantId, Long employerId, long jobId) {
        return jdbc.queryForObject("""
                insert into clients (id_number_hash, id_number_encrypted, id_number_last4, first_name, last_name,
                    current_consultant_id, employer_id, created_by_import_job_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, hash, encrypted, last4, firstName, lastName, consultantId, employerId, jobId);
    }

    public Optional<String> lastAssignmentChange(long clientId) {
        return jdbc.query("""
                select change_type from client_assignment_history where client_id = ?
                order by changed_at desc, id desc limit 1
                """, (rs, n) -> rs.getString(1), clientId).stream().findFirst();
    }

    public long nameDifferenceCount(long jobId) {
        return jdbc.queryForObject("""
                select count(*) from import_errors where import_job_id = ? and error_code = 'client:name-differs'
                """, Long.class, jobId);
    }

    public record IssueLine(String sheetName, Integer rowNumber, String field, String severity, String code,
            String message, String idNumberLast4) {
    }

    public List<IssueLine> issueLines(long jobId, String severity) {
        return jdbc.query("""
                select e.sheet_name, e.row_number, e.field_name, e.severity, e.error_code, e.safe_message,
                       r.id_number_last4
                from import_errors e
                left join import_rows r on r.import_job_id = e.import_job_id and r.sheet_name = e.sheet_name
                                        and r.row_number = e.row_number
                where e.import_job_id = ? and (cast(? as varchar) is null or e.severity = ?)
                order by case e.severity when 'ERROR' then 0 when 'WARNING' then 1 else 2 end,
                         e.row_number is not null, e.sheet_name nulls first, e.row_number, e.id
                """, (rs, n) -> new IssueLine(rs.getString(1), nullableInt(rs, 2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7)), jobId, severity, severity);
    }

    public boolean assignIfUnassigned(long clientId, long consultantId) {
        return jdbc.update("""
                update clients set current_consultant_id = ?, version = version + 1
                where id = ? and current_consultant_id is null
                """, consultantId, clientId) == 1;
    }

    public void setEmployerIfMissing(long clientId, long employerId) {
        jdbc.update("update clients set employer_id = ?, version = version + 1 where id = ? and employer_id is null",
                employerId, clientId);
    }

    public void insertImportHistory(long clientId, long toConsultantId, String reason, long userId, long jobId) {
        jdbc.update("""
                insert into client_assignment_history (client_id, from_consultant_id, to_consultant_id, change_type,
                    reason, changed_by_user_id, import_job_id)
                values (?, null, ?, 'IMPORT', ?, ?, ?)
                """, clientId, toConsultantId, reason, userId, jobId);
    }

    public record NewTransaction(long clientId, Long consultantId, LocalDate date, Long employerId, Long structureId,
            Long paymentTermId, BigDecimal amountToClient, BigDecimal thirdPartySettlement, String primaryBudgetList,
            BigDecimal primaryLoanAmount, String secondaryBudgetList, BigDecimal secondaryLoanAmount, long jobId,
            String sheet, int rowNumber, long userId, long reportingWeekId, int dayOfWeek, BigDecimal quotation,
            BigDecimal banked, String sourceConsultantName, String sourceEmployerName) {
    }

    public long insertTransaction(NewTransaction t) {
        return jdbc.queryForObject("""
                insert into client_transactions (client_id, consultant_id, transaction_date, employer_id, structure_id,
                    payment_term_id, amount_to_client, third_party_settlement, primary_structure_budget_list,
                    primary_structure_loan_amount, secondary_structure_budget_list, secondary_structure_loan_amount,
                    import_job_id, source_sheet, source_row_number, created_by_user_id, reporting_week_id, day_of_week,
                    quotation_amount, banked_amount, source_consultant_name, source_employer_name)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, t.clientId(), t.consultantId(), t.date(), t.employerId(), t.structureId(),
                t.paymentTermId(), t.amountToClient(), t.thirdPartySettlement(), t.primaryBudgetList(),
                t.primaryLoanAmount(), t.secondaryBudgetList(), t.secondaryLoanAmount(), t.jobId(), t.sheet(),
                t.rowNumber(), t.userId(), t.reportingWeekId(), t.dayOfWeek(), t.quotation(), t.banked(),
                t.sourceConsultantName(), t.sourceEmployerName());
    }

    public int insertLines(long transactionId, long rowId) {
        return jdbc.update("""
                insert into client_transaction_lines (transaction_id, product_id, amount, counts_toward_quotation,
                    counts_toward_banked)
                select ?, l.product_id, l.amount, p.counts_toward_quotation, p.counts_toward_banked
                from import_row_lines l join products p on p.id = l.product_id
                where l.import_row_id = ?
                """, transactionId, rowId);
    }

    public int insertExtras(long transactionId, long rowId) {
        return jdbc.update("""
                insert into client_transaction_extras (transaction_id, sheet_header, value_text, value_number)
                select ?, e.key,
                       case when jsonb_typeof(e.value) = 'number' then null else left(e.value #>> '{}', 500) end,
                       case when jsonb_typeof(e.value) = 'number' then (e.value #>> '{}')::numeric end
                from import_rows r, jsonb_each(r.extras) e
                where r.id = ? and r.extras is not null
                """, transactionId, rowId);
    }

    public record Week(long id, Long currentImportJobId) {
    }

    public Week lockWeek(LocalDate weekStarting) {
        jdbc.update("insert into reporting_weeks (week_starting) values (?) on conflict (week_starting) do nothing",
                weekStarting);
        return jdbc.queryForObject(
                "select id, current_import_job_id from reporting_weeks where week_starting = ? for update",
                (rs, n) -> new Week(rs.getLong(1), nullableLong(rs, 2)), weekStarting);
    }

    public int supersedeTransactions(long oldJobId, long newJobId) {
        return jdbc.update("""
                update client_transactions set superseded_by_import_job_id = ?
                where import_job_id = ? and superseded_by_import_job_id is null
                """, newJobId, oldJobId);
    }

    public void setCurrentImport(long weekId, long jobId) {
        jdbc.update("update reporting_weeks set current_import_job_id = ? where id = ?", jobId, weekId);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, int column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static LocalDate localDate(ResultSet rs, int column) throws SQLException {
        java.sql.Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
