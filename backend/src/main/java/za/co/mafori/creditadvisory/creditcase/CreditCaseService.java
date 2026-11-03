package za.co.mafori.creditadvisory.creditcase;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.client.Client;
import za.co.mafori.creditadvisory.client.ClientQueryService;
import za.co.mafori.creditadvisory.client.ClientRepository;
import za.co.mafori.creditadvisory.client.ClientService;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.PageResponse;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * Credit cases and case notes (contract section 9, FR-19).
 *
 * <p>
 * Access follows the client: whoever may see a client may see and work on its
 * cases (MANAGER / ADMIN all clients, a CONSULTANT only their own). A case the
 * caller may not see is treated as if it did not exist (404).
 *
 * <p>
 * The audit log records THAT a case or note was created, viewed or changed -
 * never the title or the note text (SRS: no unredacted case details in audit
 * logs).
 */
@Service
public class CreditCaseService {

    private static final int MAX_PAGE_SIZE = 100;

    private final CreditCaseRepository cases;
    private final CaseNoteRepository notes;
    private final ClientRepository clients;
    private final UserAccountRepository users;
    private final AuditLogWriter audit;

    public CreditCaseService(CreditCaseRepository cases, CaseNoteRepository notes, ClientRepository clients,
            UserAccountRepository users, AuditLogWriter audit) {
        this.cases = cases;
        this.notes = notes;
        this.clients = clients;
        this.users = users;
        this.audit = audit;
    }

    // ---------- list ----------

    /**
     * MANAGER / ADMIN see all cases and may filter by consultant. A CONSULTANT only
     * sees cases of their own clients; their consultantId filter is ignored.
     */
    @Transactional(readOnly = true)
    public PageResponse<CaseListItem> search(AppUserPrincipal caller, Long clientId, Long consultantId,
            CaseStatus status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Long effectiveConsultantId;
        if (ClientQueryService.seesAllClients(caller)) {
            effectiveConsultantId = consultantId;
        } else if (caller.consultantId() == null) {
            // A consultant-role user without a consultant record has no clients, so no
            // cases.
            return new PageResponse<>(List.of(), safePage, safeSize, 0, 0);
        } else {
            effectiveConsultantId = caller.consultantId();
        }

        PageRequest pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "openedAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return PageResponse.of(cases.search(effectiveConsultantId, clientId, status, pageable),
                CaseListItem::from);
    }

    // ---------- create ----------

    /** Empty if the client does not exist or the caller may not see it. */
    @Transactional
    public Optional<CaseDetail> create(AppUserPrincipal caller, CreateCaseRequest request) {
        Optional<Client> client = clients.findWithCurrentConsultantById(request.clientId())
                .filter(c -> ClientService.isVisibleTo(c, caller));
        if (client.isEmpty()) {
            return Optional.empty();
        }
        CreditCase creditCase = new CreditCase(client.get(), request.title(),
                users.getReferenceById(caller.userId()));
        cases.saveAndFlush(creditCase);
        audit.record("CASE_CREATED", "CASE", creditCase.getId(), meta("clientId", client.get().getId()));
        return Optional.of(CaseDetail.from(creditCase, List.of()));
    }

    // ---------- view ----------

    /** Empty if the case does not exist or the caller may not see it. Audited. */
    @Transactional
    public Optional<CaseDetail> view(AppUserPrincipal caller, long id) {
        Optional<CreditCase> visible = findVisible(caller, id);
        visible.ifPresent(creditCase -> audit.record("CASE_VIEWED", "CASE", creditCase.getId(), meta()));
        return visible.map(creditCase -> CaseDetail.from(creditCase, notesOf(creditCase)));
    }

    // ---------- update ----------

    /** Empty if the case does not exist or the caller may not see it. */
    @Transactional
    public Optional<CaseDetail> update(AppUserPrincipal caller, long id, UpdateCaseRequest request) {
        Optional<CreditCase> visible = findVisible(caller, id);
        if (visible.isEmpty()) {
            return Optional.empty();
        }
        CreditCase creditCase = visible.get();
        if (creditCase.getVersion() != request.version()) {
            throw ApiException.staleVersion();
        }

        String title = request.title().strip();
        CaseStatus oldStatus = creditCase.getStatus();
        CaseStatus newStatus = request.status();

        // The names of the fields that changed - never the title itself.
        List<String> changed = new ArrayList<>();
        if (!title.equals(creditCase.getTitle())) {
            changed.add("title");
        }
        if (newStatus != oldStatus) {
            changed.add("status");
        }

        creditCase.retitle(title);
        creditCase.changeStatus(newStatus);
        cases.flush();

        if (!changed.isEmpty()) {
            Map<String, Object> details = meta("fields", changed);
            if (newStatus != oldStatus) {
                details.put("fromStatus", oldStatus.name());
                details.put("toStatus", newStatus.name());
            }
            audit.record("CASE_UPDATED", "CASE", creditCase.getId(), details);
        }
        return Optional.of(CaseDetail.from(creditCase, notesOf(creditCase)));
    }

    // ---------- notes ----------

    /** Empty if the case does not exist or the caller may not see it. */
    @Transactional
    public Optional<CaseNoteResponse> addNote(AppUserPrincipal caller, long caseId, AddCaseNoteRequest request) {
        Optional<CreditCase> visible = findVisible(caller, caseId);
        if (visible.isEmpty()) {
            return Optional.empty();
        }
        CreditCase creditCase = visible.get();
        if (creditCase.isClosed()) {
            throw ApiException.conflict("case:closed", "Case is closed",
                    "This case is closed. Reopen it before adding notes.");
        }
        CaseNote note = notes.saveAndFlush(
                new CaseNote(creditCase, users.getReferenceById(caller.userId()), request.note()));
        // Only THAT a note was added - never what it says.
        audit.record("CASE_NOTE_ADDED", "CASE", creditCase.getId(), meta("noteId", note.getId()));
        return Optional.of(CaseNoteResponse.from(note));
    }

    // ---------- rules ----------

    private Optional<CreditCase> findVisible(AppUserPrincipal caller, long id) {
        return cases.findWithClientById(id)
                .filter(creditCase -> ClientService.isVisibleTo(creditCase.getClient(), caller));
    }

    private List<CaseNote> notesOf(CreditCase creditCase) {
        return notes.findByCreditCaseIdOrderByCreatedAtDescIdDesc(creditCase.getId());
    }
}
