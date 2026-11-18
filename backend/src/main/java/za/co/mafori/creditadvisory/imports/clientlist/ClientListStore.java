package za.co.mafori.creditadvisory.imports.clientlist;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportStore;

/**
 * All SQL of the client list import that the weekly register's store does not
 * already have. Jobs here are always import_type 'CLIENT_LIST', so the two
 * imports never see each other's jobs. Shared SQL (consultants, employers,
 * assignment history, issues) stays in WeeklyImportStore.
 */
@Component
public class ClientListStore {

    private final JdbcTemplate jdbc;

    public ClientListStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Header spelling (normalised) -> field (V29). */
    public Map<String, ClientListField> aliases() {
        Map<String, ClientListField> out = new HashMap<>();
        jdbc.query("select alias, field from client_list_header_aliases", (rs, n) -> {
            try {
                out.put(ClientListParser.normalizeHeader(rs.getString(1)), ClientListField.valueOf(rs.getString(2)));
            } catch (IllegalArgumentException e) {
                // a field this version does not know: ignored
            }
            return null;
        });
        return out;
    }

    // ---------- jobs ----------

    public record Job(long id, String fileName, String fileDigest, long fileSizeBytes, String state,
            String sheetName, long uploadedByUserId, String uploadedByEmail, int totalRows, int newCount,
            int updateCount, int unchangedCount, int invalidCount, int conflictCount, int duplicateCount,
            int appliedCount, int staleCount, int newClientCount, int warningCount, Instant expiresAt,
            Instant committedAt, Instant createdAt, Integer consultantsRegistered, Integer clientsLinked) {

        public boolean expired(Instant now) {
            return "PREVIEWED".equals(state) && expiresAt.isBefore(now);
        }
    }

    private static final String JOB_SELECT = """
            select j.id, j.file_name, j.file_digest, j.file_size_bytes, j.state, j.sheet_name,
                   j.uploaded_by_user_id, u.email, j.total_rows, j.new_count, j.update_count, j.unchanged_count,
                   j.invalid_count, j.conflict_count, j.duplicate_count, j.applied_count, j.stale_count,
                   j.new_client_count, j.warning_count, j.expires_at, j.committed_at, j.created_at,
                   j.consultants_registered, j.clients_linked
            from import_jobs j join users u on u.id = j.uploaded_by_user_id
            where j.import_type = 'CLIENT_LIST'
            """;

    private static Job job(ResultSet rs, int n) throws SQLException {
        return new Job(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                rs.getString(6), rs.getLong(7), rs.getString(8), rs.getInt(9), rs.getInt(10), rs.getInt(11),
                rs.getInt(12), rs.getInt(13), rs.getInt(14), rs.getInt(15), rs.getInt(16), rs.getInt(17),
                rs.getInt(18), rs.getInt(19), instant(rs, 20), instant(rs, 21), instant(rs, 22),
                rs.getObject(23, Integer.class), rs.getObject(24, Integer.class));
    }

    public record NewJob(String fileName, String fileDigest, long fileSizeBytes, long uploadedByUserId,
            String sheetName, int totalRows, int newCount, int updateCount, int unchangedCount, int invalidCount,
            int conflictCount, int duplicateCount, int warningCount, Instant expiresAt,
            List<ClientListParser.Column> columns) {
    }

    public long insertJob(NewJob j) {
        return jdbc.queryForObject("""
                insert into import_jobs (file_name, file_digest, file_size_bytes, sheet_name, state,
                    uploaded_by_user_id, import_type, total_rows, new_count, update_count, unchanged_count,
                    invalid_count, conflict_count, duplicate_count, new_client_count, warning_count, expires_at,
                    column_mapping)
                values (?, ?, ?, ?, 'PREVIEWED', ?, 'CLIENT_LIST', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                returning id
                """, Long.class, j.fileName(), j.fileDigest(), j.fileSizeBytes(), Text.cut(
                j.sheetName(), 100), j.uploadedByUserId(), j.totalRows(), j.newCount(), j.updateCount(),
                j.unchangedCount(), j.invalidCount(), j.conflictCount(), j.duplicateCount(), j.newCount(),
                j.warningCount(), Timestamp.from(j.expiresAt()), columnsJson(j.columns()));
    }

    /** [{"header": "...", "field": "NAME" | null}, ...] - headers only. */
    static String columnsJson(List<ClientListParser.Column> columns) {
        StringBuilder sb = new StringBuilder("[");
        for (ClientListParser.Column c : columns) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append("{\"header\":").append(jsonString(ClientListParser.safe(c.header()))).append(",\"field\":")
                    .append(c.field() == null ? "null" : jsonString(c.field().name())).append('}');
        }
        return sb.append(']').toString();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    public record ColumnMapping(String header, String field) {
    }

    public List<ColumnMapping> columns(long jobId) {
        return jdbc.query("""
                select e ->> 'header', e ->> 'field'
                from import_jobs j cross join lateral jsonb_array_elements(coalesce(j.column_mapping, '[]'::jsonb))
                     with ordinality as x(e, n)
                where j.id = ?
                order by x.n
                """, (rs, n) -> new ColumnMapping(rs.getString(1), rs.getString(2)), jobId);
    }

    public Optional<Job> findJob(long id) {
        return jdbc.query(JOB_SELECT + " and j.id = ?", ClientListStore::job, id).stream().findFirst();
    }

    public Optional<Job> lockJob(long id) {
        jdbc.query("select id from import_jobs where id = ? and import_type = 'CLIENT_LIST' for update",
                (rs, n) -> rs.getLong(1), id);
        return findJob(id);
    }

    public List<Job> listJobs(int limit, long offset) {
        return jdbc.query(JOB_SELECT + " order by j.created_at desc, j.id desc limit ? offset ?",
                ClientListStore::job, limit, offset);
    }

    public long countJobs() {
        return jdbc.queryForObject("select count(*) from import_jobs where import_type = 'CLIENT_LIST'", Long.class);
    }

    /** Previews past their expiry time, oldest first (ClientListCleanupJob). */
    public List<Long> expiredPreviewIds(int limit) {
        return jdbc.query("""
                select id from import_jobs
                where import_type = 'CLIENT_LIST' and state = 'PREVIEWED' and expires_at < now()
                order by expires_at, id limit ?
                """, (rs, n) -> rs.getLong(1), limit);
    }

    /** CANCELLED or EXPIRED: the staged personal data goes at once. */
    public void markFinished(long jobId, String state) {
        jdbc.update("update import_jobs set state = ?, personal_data_purged_at = now(), version = version + 1"
                + " where id = ? and state = 'PREVIEWED'", state, jobId);
        purgePersonalData(jobId);
    }

    /**
     * Everything personal in the staged rows. Kept: row number, action, outcome,
     * the last four ID digits and the consultant name (so the report stays
     * readable).
     */
    public void purgePersonalData(long jobId) {
        jdbc.update("""
                update import_rows set id_number_hash = null, id_number_encrypted = null, first_name = null,
                    last_name = null, email = null, phone = null, work_phone = null, home_phone = null,
                    file_number = null, file_id = null
                where import_job_id = ?
                """, jobId);
    }

    public void markCommitted(long jobId, int applied, int stale, int newClients, int consultantsIdentified,
            int consultantsRegistered, int consultantsMatched, int clientsLinked, int rowsNeedingAttention) {
        jdbc.update("""
                update import_jobs set state = 'COMMITTED', committed_at = now(), personal_data_purged_at = now(),
                    applied_count = ?, stale_count = ?, new_client_count = ?, consultants_identified = ?,
                    consultants_registered = ?, consultants_matched = ?, clients_linked = ?,
                    rows_needing_attention = ?, version = version + 1
                where id = ? and state = 'PREVIEWED'
                """, applied, stale, newClients, consultantsIdentified, consultantsRegistered, consultantsMatched,
                clientsLinked, rowsNeedingAttention, jobId);
        purgePersonalData(jobId);
    }

    // ---------- staged rows ----------

    public record NewRow(long jobId, int rowNumber, String action, byte[] idNumberHash, byte[] idNumberEncrypted,
            String idNumberLast4, String firstName, String lastName, String consultantName, String consultantEmail,
            String email, String cellphone, String workPhone, String homePhone, String employerName,
            String paymentMethod, String productQuotation, String fileNumber, String fileId, Long existingClientId,
            Long existingClientVersion, boolean createsClient, boolean assignsConsultant, int warningCount) {
    }

    public void insertRow(NewRow r) {
        jdbc.update("""
                insert into import_rows (import_job_id, row_number, action, id_number_hash, id_number_encrypted,
                    id_number_last4, first_name, last_name, consultant_name, consultant_email, email, phone,
                    work_phone, home_phone, employer_name, payment_method, product_quotation_name, file_number,
                    file_id, existing_client_id, existing_client_version, creates_client, assigns_consultant,
                    warning_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, r.jobId(), r.rowNumber(), r.action(), r.idNumberHash(), r.idNumberEncrypted(),
                r.idNumberLast4(), r.firstName(), r.lastName(), r.consultantName(), r.consultantEmail(), r.email(),
                r.cellphone(), r.workPhone(), r.homePhone(), r.employerName(), r.paymentMethod(),
                r.productQuotation(), r.fileNumber(), r.fileId(), r.existingClientId(), r.existingClientVersion(),
                r.createsClient(), r.assignsConsultant(), r.warningCount());
    }

    /**
     * A staged row as confirm needs it (personal data still there: the job is
     * PREVIEWED).
     */
    public record RowToApply(long id, int rowNumber, String action, byte[] idNumberHash, byte[] idNumberEncrypted,
            String idNumberLast4, String firstName, String lastName, String consultantName, String email,
            String cellphone, String workPhone, String homePhone, String employerName, String paymentMethod,
            String productQuotation, String fileNumber, String fileId) {
    }

    /**
     * NEW and UPDATE/UNCHANGED rows; INVALID, DUPLICATE and CONFLICT rows are never
     * applied.
     */
    public List<RowToApply> rowsToApply(long jobId) {
        return jdbc.query("""
                select id, row_number, action, id_number_hash, id_number_encrypted, id_number_last4, first_name,
                       last_name, consultant_name, email, phone, work_phone, home_phone, employer_name,
                       payment_method, product_quotation_name, file_number, file_id
                from import_rows
                where import_job_id = ? and action in ('NEW', 'UPDATE', 'UNCHANGED')
                order by row_number
                """, (rs, n) -> new RowToApply(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getBytes(4),
                rs.getBytes(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15),
                rs.getString(16), rs.getString(17), rs.getString(18)), jobId);
    }

    public void setRowOutcome(long rowId, String outcome) {
        jdbc.update("update import_rows set outcome = ? where id = ?", outcome, rowId);
    }

    /** Rows that were skipped at confirm: never applied. */
    public void markNotApplied(long jobId) {
        jdbc.update("update import_rows set outcome = 'SKIPPED' where import_job_id = ? and outcome is null",
                jobId);
    }

    public record StagedRow(int rowNumber, String action, String firstName, String lastName, String idNumberLast4,
            String consultantName, boolean createsClient, boolean assignsConsultant, int warningCount,
            String outcome) {
    }

    public List<StagedRow> rows(long jobId, String action, int limit, long offset) {
        return jdbc.query("""
                select row_number, action, first_name, last_name, id_number_last4, consultant_name, creates_client,
                       assigns_consultant, warning_count, outcome
                from import_rows
                where import_job_id = ? and (cast(? as varchar) is null or action = ?)
                order by row_number limit ? offset ?
                """, (rs, n) -> new StagedRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getBoolean(8), rs.getInt(9),
                rs.getString(10)), jobId, action, action, limit, offset);
    }

    public long countRows(long jobId, String action) {
        return jdbc.queryForObject("select count(*) from import_rows where import_job_id = ?"
                + " and (cast(? as varchar) is null or action = ?)", Long.class, jobId, action, action);
    }

    /** What the staged rows will add, for the summary. */
    public record Additions(int debitOrders, int withCellphone, int withWorkPhone, int withHomePhone,
            int withEmail, int withFileNumber, int withFileId, int withEmployer, int withProductQuotation,
            int withoutConsultant) {
    }

    public Additions additions(long jobId) {
        return jdbc.queryForObject("""
                select count(*) filter (where payment_method = 'DEBIT_ORDER'),
                       count(phone), count(work_phone), count(home_phone), count(email), count(file_number),
                       count(file_id), count(employer_name), count(product_quotation_name),
                       count(*) filter (where consultant_name is null)
                from import_rows where import_job_id = ? and action in ('NEW', 'UPDATE', 'UNCHANGED')
                """, (rs, n) -> new Additions(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10)), jobId);
    }

    /**
     * Distinct values of a staged text column (employer or product quotation names)
     * that no record matches yet.
     */
    public List<String> distinctStaged(long jobId, String column) {
        if (!column.equals("employer_name") && !column.equals("product_quotation_name")) {
            throw new IllegalArgumentException(column);
        }
        return jdbc.query("select distinct " + column + " from import_rows where import_job_id = ? and " + column
                + " is not null and action in ('NEW', 'UPDATE', 'UNCHANGED') order by 1", (rs, n) -> rs.getString(1),
                jobId);
    }

    public record IssueLine(Integer rowNumber, String field, String severity, String code, String message,
            String idNumberLast4) {
    }

    /** One sheet per job, so issues join their row by row number alone. */
    public List<IssueLine> issueLines(long jobId, String severity) {
        return jdbc.query("""
                select e.row_number, e.field_name, e.severity, e.error_code, e.safe_message, r.id_number_last4
                from import_errors e
                left join import_rows r on r.import_job_id = e.import_job_id and r.row_number = e.row_number
                where e.import_job_id = ? and (cast(? as varchar) is null or e.severity = ?)
                order by case e.severity when 'ERROR' then 0 when 'WARNING' then 1 else 2 end,
                         e.row_number is not null, e.row_number, e.id
                """, (rs, n) -> new IssueLine(rs.getObject(1, Integer.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6)), jobId, severity, severity);
    }

    // ---------- clients ----------

    public record ExistingClient(long id, long version, Long currentConsultantId, Long employerId, String firstName,
            String lastName, String email, String phone, String workPhone, String homePhone,
            Long productQuotationId, String fileNumber, String fileId, String paymentMethod) {
    }

    private static final String CLIENT_SELECT = """
            select id, version, current_consultant_id, employer_id, first_name, last_name, email, phone, work_phone,
                   home_phone, product_quotation_id, file_number, file_id, payment_method
            from clients where id_number_hash = ?
            """;

    private static ExistingClient client(ResultSet rs, int n) throws SQLException {
        return new ExistingClient(rs.getLong(1), rs.getLong(2), rs.getObject(3, Long.class),
                rs.getObject(4, Long.class), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getObject(11, Long.class),
                rs.getString(12), rs.getString(13), rs.getString(14));
    }

    public Optional<ExistingClient> findClientByHash(byte[] hash) {
        return jdbc.query(CLIENT_SELECT, ClientListStore::client, (Object) hash).stream().findFirst();
    }

    public Optional<ExistingClient> lockClientByHash(byte[] hash) {
        jdbc.query("select id from clients where id_number_hash = ? for update", (rs, n) -> rs.getLong(1),
                (Object) hash);
        return findClientByHash(hash);
    }

    public record NewClient(byte[] hash, byte[] encrypted, String last4, String firstName, String lastName,
            Long consultantId, Long employerId, String email, String phone, String workPhone, String homePhone,
            Long productQuotationId, String fileNumber, String fileId, String paymentMethod, long jobId) {
    }

    public long insertClient(NewClient c) {
        return jdbc.queryForObject("""
                insert into clients (id_number_hash, id_number_encrypted, id_number_last4, first_name, last_name,
                    current_consultant_id, employer_id, email, phone, work_phone, home_phone, product_quotation_id,
                    file_number, file_id, payment_method, created_by_import_job_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, c.hash(), c.encrypted(), c.last4(), c.firstName(), c.lastName(), c.consultantId(),
                c.employerId(), c.email(), c.phone(), c.workPhone(), c.homePhone(), c.productQuotationId(),
                c.fileNumber(), c.fileId(), c.paymentMethod(), c.jobId());
    }

    /**
     * Fills in the fields an existing client does not have yet (the caller holds
     * the row lock from lockClientByHash). Never overwrites
     * a value, so nothing a person typed in is lost. The employer is only filled
     * in when the client has neither an employer nor a payment method.
     *
     * @return the names of the fields filled in (empty: nothing changed)
     */
    public List<String> fillMissing(ExistingClient c, Long employerId, String email, String phone, String workPhone,
            String homePhone, Long productQuotationId, String fileNumber, String fileId, String paymentMethod) {
        List<String> filled = new java.util.ArrayList<>();
        boolean noPayment = c.employerId() == null && c.paymentMethod() == null;
        Long newEmployer = noPayment && paymentMethod == null ? employerId : null;
        String newPayment = noPayment ? paymentMethod : null;
        note(filled, "employer", null, newEmployer);
        note(filled, "paymentMethod", null, newPayment);
        note(filled, "email", c.email(), email);
        note(filled, "phone", c.phone(), phone);
        note(filled, "workPhone", c.workPhone(), workPhone);
        note(filled, "homePhone", c.homePhone(), homePhone);
        note(filled, "productQuotation", c.productQuotationId(), productQuotationId);
        note(filled, "fileNumber", c.fileNumber(), fileNumber);
        note(filled, "fileId", c.fileId(), fileId);
        if (filled.isEmpty()) {
            return filled;
        }
        int updated = jdbc.update("""
                update clients set employer_id = coalesce(employer_id, ?), payment_method = coalesce(payment_method, ?),
                    email = coalesce(email, ?), phone = coalesce(phone, ?), work_phone = coalesce(work_phone, ?),
                    home_phone = coalesce(home_phone, ?), product_quotation_id = coalesce(product_quotation_id, ?),
                    file_number = coalesce(file_number, ?), file_id = coalesce(file_id, ?), version = version + 1
                where id = ?
                """, newEmployer, newPayment, email, phone, workPhone, homePhone, productQuotationId, fileNumber,
                fileId,
                c.id());
        return updated == 1 ? filled : List.of();
    }

    private static void note(List<String> filled, String name, Object current, Object incoming) {
        if (current == null && incoming != null) {
            filled.add(name);
        }
    }

    /**
     * True when confirm would fill in at least one field (the preview's UPDATE).
     */
    public static boolean wouldFill(ExistingClient c, boolean hasEmployer, String email, String phone,
            String workPhone, String homePhone, boolean hasProductQuotation, String fileNumber, String fileId,
            String paymentMethod) {
        boolean noPayment = c.employerId() == null && c.paymentMethod() == null;
        return (noPayment && (paymentMethod != null || hasEmployer))
                || (c.email() == null && email != null) || (c.phone() == null && phone != null)
                || (c.workPhone() == null && workPhone != null) || (c.homePhone() == null && homePhone != null)
                || (c.productQuotationId() == null && hasProductQuotation)
                || (c.fileNumber() == null && fileNumber != null) || (c.fileId() == null && fileId != null);
    }

    // ---------- product quotations (the C_BRANCHNAME list, V20) ----------

    public Map<String, Long> productQuotationsByName() {
        Map<String, Long> byName = new HashMap<>();
        jdbc.query("select name, id from product_quotations",
                (rs, n) -> byName.put(WeeklyImportStore.key(rs.getString(1)), rs.getLong(2)));
        return byName;
    }

    public long createProductQuotation(String name) {
        String clean = name.strip().replaceAll("\\s+", " ");
        jdbc.update("insert into product_quotations (name) values (?) on conflict ((lower(btrim(name)))) do nothing",
                clean);
        return jdbc.queryForObject("select id from product_quotations where lower(btrim(name)) = lower(btrim(?))",
                Long.class, clean);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    /** Text helpers shared with the service. */
    static final class Text {
        private Text() {
        }

        static String cut(String s, int max) {
            return s == null || s.length() <= max ? s : s.substring(0, max);
        }
    }
}
