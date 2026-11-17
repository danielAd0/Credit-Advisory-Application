package za.co.mafori.creditadvisory.client;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.client.ClientDataExport.Access;
import za.co.mafori.creditadvisory.client.ClientDataExport.Assignment;
import za.co.mafori.creditadvisory.client.ClientDataExport.Consultant;
import za.co.mafori.creditadvisory.client.ClientDataExport.CreditCase;
import za.co.mafori.creditadvisory.client.ClientDataExport.Details;
import za.co.mafori.creditadvisory.client.ClientDataExport.Extra;
import za.co.mafori.creditadvisory.client.ClientDataExport.ImportRow;
import za.co.mafori.creditadvisory.client.ClientDataExport.Line;
import za.co.mafori.creditadvisory.client.ClientDataExport.Note;
import za.co.mafori.creditadvisory.client.ClientDataExport.Sale;
import za.co.mafori.creditadvisory.client.ClientDataExport.Source;
import za.co.mafori.creditadvisory.common.idnumber.IdNumberProtector;

/**
 * Gathers everything held about one client for a POPIA access request (see
 * ClientDataExport), in one transaction so the parts agree with
 * each other. The export itself is audited as CLIENT_DATA_EXPORTED with counts
 * only.
 */
@Service
public class ClientDataExportService {

    private final JdbcTemplate jdbc;
    private final IdNumberProtector protector;
    private final AuditLogWriter audit;

    public ClientDataExportService(JdbcTemplate jdbc, IdNumberProtector protector, AuditLogWriter audit) {
        this.jdbc = jdbc;
        this.protector = protector;
        this.audit = audit;
    }

    /**
     * Not read-only: the export itself is written to the audit log.
     *
     * @param format json, pdf or csv: only recorded in the audit log here; the
     *               controller turns the export into that file
     */
    @Transactional
    public Optional<ClientDataExport> export(AppUserPrincipal caller, long clientId, String format) {
        Optional<Details> client = details(clientId);
        if (client.isEmpty()) {
            return Optional.empty();
        }
        ClientDataExport export = new ClientDataExport(OffsetDateTime.now(), caller.email(), client.get(),
                assignments(clientId), sales(clientId), cases(clientId), importRows(clientId),
                accessLog(clientId));
        // Written last, so the access log above shows what happened before this export.
        audit.record("CLIENT_DATA_EXPORTED", "CLIENT", clientId, meta("format", format,
                "sales", export.sales().size(), "creditCases", export.creditCases().size(),
                "assignments", export.assignmentHistory().size(), "accessLogEntries", export.accessLog().size()));
        return Optional.of(export);
    }

    // ---------- the client ----------

    private Optional<Details> details(long clientId) {
        return jdbc.query("""
                select c.id, c.first_name, c.last_name, c.id_number_encrypted, c.email, c.phone, c.work_phone,
                       c.home_phone, e.name, pq.name, c.file_number, c.file_id, c.payment_method,
                       k.consultant_code, k.full_name, c.created_at, c.updated_at,
                       c.created_by_import_job_id, j.file_name, j.week_starting
                from clients c
                left join employers e on e.id = c.employer_id
                left join product_quotations pq on pq.id = c.product_quotation_id
                left join consultants k on k.id = c.current_consultant_id
                left join import_jobs j on j.id = c.created_by_import_job_id
                where c.id = ?
                """, (rs, n) -> new Details(rs.getLong(1), rs.getString(2), rs.getString(3),
                protector.decrypt(rs.getBytes(4)).value(), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getString(13), consultant(rs, 14), rs.getObject(16, OffsetDateTime.class),
                rs.getObject(17, OffsetDateTime.class), source(rs, 18)), clientId).stream().findFirst();
    }

    private List<Assignment> assignments(long clientId) {
        return jdbc.query("""
                select h.changed_at, h.change_type, f.consultant_code, f.full_name, t.consultant_code, t.full_name,
                       h.reason, u.email
                from client_assignment_history h
                left join consultants f on f.id = h.from_consultant_id
                left join consultants t on t.id = h.to_consultant_id
                left join users u on u.id = h.changed_by_user_id
                where h.client_id = ?
                order by h.changed_at, h.id
                """, (rs, n) -> new Assignment(rs.getObject(1, OffsetDateTime.class), rs.getString(2),
                consultant(rs, 3), consultant(rs, 5), rs.getString(7), rs.getString(8)), clientId);
    }

    // ---------- sales ----------

    private List<Sale> sales(long clientId) {
        Map<Long, List<Line>> lines = new HashMap<>();
        jdbc.query("""
                select l.transaction_id, p.name, l.amount
                from client_transaction_lines l
                join client_transactions t on t.id = l.transaction_id
                join products p on p.id = l.product_id
                where t.client_id = ?
                order by p.display_order, p.id
                """, rs -> {
            lines.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                    .add(new Line(rs.getString(2), rs.getBigDecimal(3)));
        }, clientId);
        Map<Long, List<Extra>> extras = new HashMap<>();
        jdbc.query("""
                select x.transaction_id, x.sheet_header, x.value_text, x.value_number
                from client_transaction_extras x
                join client_transactions t on t.id = x.transaction_id
                where t.client_id = ?
                order by x.id
                """, rs -> {
            extras.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                    .add(new Extra(rs.getString(2), rs.getString(3), rs.getBigDecimal(4)));
        }, clientId);

        return jdbc.query("""
                select t.id, t.transaction_date, k.consultant_code, k.full_name, t.import_job_id is null,
                       t.superseded_by_import_job_id is not null, t.import_job_id, j.file_name, j.week_starting,
                       t.quotation_amount, t.banked_amount, e.name, s.name, pt.name, t.amount_to_client,
                       t.third_party_settlement, t.primary_structure_budget_list, t.primary_structure_loan_amount,
                       t.secondary_structure_budget_list, t.secondary_structure_loan_amount, t.created_at
                from client_transactions t
                left join consultants k on k.id = t.consultant_id
                left join import_jobs j on j.id = t.import_job_id
                left join employers e on e.id = t.employer_id
                left join structures s on s.id = t.structure_id
                left join payment_terms pt on pt.id = t.payment_term_id
                where t.client_id = ?
                order by t.transaction_date, t.id
                """, (rs, n) -> {
            long id = rs.getLong(1);
            return new Sale(id, rs.getObject(2, LocalDate.class), consultant(rs, 3), rs.getBoolean(5),
                    rs.getBoolean(6), source(rs, 7), lines.getOrDefault(id, List.of()), rs.getBigDecimal(10),
                    rs.getBigDecimal(11), rs.getString(12), rs.getString(13), rs.getString(14),
                    rs.getBigDecimal(15), rs.getBigDecimal(16), rs.getString(17), rs.getBigDecimal(18),
                    rs.getString(19), rs.getBigDecimal(20), extras.getOrDefault(id, List.of()),
                    rs.getObject(21, OffsetDateTime.class));
        }, clientId);
    }

    // ---------- credit cases ----------

    private List<CreditCase> cases(long clientId) {
        Map<Long, List<Note>> notes = new HashMap<>();
        jdbc.query("""
                select n.case_id, n.created_at, u.email, n.note
                from case_notes n
                join credit_cases c on c.id = n.case_id
                left join users u on u.id = n.author_user_id
                where c.client_id = ?
                order by n.created_at, n.id
                """, rs -> {
            notes.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                    .add(new Note(rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getString(4)));
        }, clientId);
        return jdbc.query("""
                select c.id, c.title, c.status, c.opened_at, c.closed_at, u.email
                from credit_cases c
                left join users u on u.id = c.created_by_user_id
                where c.client_id = ?
                order by c.opened_at, c.id
                """, (rs, n) -> new CreditCase(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class), rs.getString(6),
                notes.getOrDefault(rs.getLong(1), List.of())), clientId);
    }

    // ---------- where it came from, and who looked ----------

    private List<ImportRow> importRows(long clientId) {
        return jdbc.query("""
                select r.import_job_id, j.file_name, j.week_starting, r.sheet_name, r.row_number, r.action,
                       r.outcome
                from import_rows r
                join import_jobs j on j.id = r.import_job_id
                where r.existing_client_id = ?
                order by j.created_at, r.row_number
                """, (rs, n) -> new ImportRow(source(rs, 1), rs.getString(4), rs.getInt(5), rs.getString(6),
                rs.getString(7)), clientId);
    }

    private List<Access> accessLog(long clientId) {
        return jdbc.query("""
                select a.occurred_at, a.action, u.email
                from audit_logs a
                left join users u on u.id = a.actor_user_id
                where a.target_type = 'CLIENT' and a.target_id = ?
                order by a.occurred_at, a.id
                """, (rs, n) -> new Access(rs.getObject(1, OffsetDateTime.class), rs.getString(2),
                rs.getString(3)), String.valueOf(clientId));
    }

    // ---------- helpers ----------

    /**
     * Code and name in columns i and i + 1, or null when there is no consultant.
     */
    private static Consultant consultant(ResultSet rs, int i) throws SQLException {
        String code = rs.getString(i);
        return code == null ? null : new Consultant(code, rs.getString(i + 1));
    }

    /**
     * Import id, file name and week in columns i to i + 2, or null when it was not
     * imported.
     */
    private static Source source(ResultSet rs, int i) throws SQLException {
        long id = rs.getLong(i);
        return rs.wasNull() ? null : new Source(id, rs.getString(i + 1), rs.getObject(i + 2, LocalDate.class));
    }
}
