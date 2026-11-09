package za.co.mafori.creditadvisory.transfer;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.client.Client;
import za.co.mafori.creditadvisory.client.ClientRepository;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.consultant.ConsultantRepository;
import za.co.mafori.creditadvisory.creditcase.CaseStatus;
import za.co.mafori.creditadvisory.creditcase.CreditCaseRepository;
import za.co.mafori.creditadvisory.user.UserAccount;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * First assignment of unassigned clients to a consultant (FR-13, FR-16, FR-17),
 * and the reverse: unassigning one client. Both lock the client rows and write
 * one history row per client plus an audit entry, in one transaction.
 */
@Service
public class ClientAssignmentService {

    private final ClientRepository clients;
    private final ConsultantRepository consultants;
    private final UserAccountRepository users;
    private final ClientAssignmentHistoryRepository history;
    private final CreditCaseRepository cases;
    private final AuditLogWriter audit;

    public ClientAssignmentService(ClientRepository clients, ConsultantRepository consultants,
            UserAccountRepository users, ClientAssignmentHistoryRepository history, CreditCaseRepository cases,
            AuditLogWriter audit) {
        this.clients = clients;
        this.consultants = consultants;
        this.users = users;
        this.history = history;
        this.cases = cases;
        this.audit = audit;
    }

    @Transactional
    public AssignClientsResponse assign(long actorUserId, AssignClientsRequest request) {
        Set<Long> ids = new LinkedHashSet<>(request.clientIds());
        if (ids.size() != request.clientIds().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "assignment:duplicate-clients", "Invalid assignment",
                    "The same client is listed more than once.");
        }

        Consultant to = consultants.findById(request.consultantId())
                .orElseThrow(() -> ApiException.notFound("consultant:not-found",
                        "Consultant " + request.consultantId() + " does not exist."));
        if (!to.getStatus().isActive()) {
            throw ApiException.unprocessable("assignment:consultant-not-active", "Consultant not active",
                    "Clients can only be assigned to an ACTIVE consultant.");
        }

        List<Client> locked = clients.findAllByIdForUpdate(ids);
        if (locked.size() != ids.size()) {
            Set<Long> missing = new LinkedHashSet<>(ids);
            locked.forEach(c -> missing.remove(c.getId()));
            throw ApiException.notFound("assignment:client-not-found", "These clients do not exist: " + missing);
        }

        List<Long> alreadyAssigned = locked.stream()
                .filter(c -> c.getCurrentConsultant() != null)
                .map(Client::getId)
                .toList();
        if (!alreadyAssigned.isEmpty()) {
            throw ApiException.conflict("assignment:already-assigned", "Clients already assigned",
                    "These clients already have a consultant; use a transfer to move them: " + alreadyAssigned);
        }

        UUID batchId = ids.size() > 1 ? UUID.randomUUID() : null;
        UserAccount actor = users.getReferenceById(actorUserId);
        for (Client client : locked) {
            client.assignTo(to);
            history.save(ClientAssignmentHistory.assigned(client, to, request.reason(), batchId, actor));
        }
        clients.flush();

        audit.record("CLIENTS_ASSIGNED", "CONSULTANT", to.getId(),
                meta("clientIds", List.copyOf(ids), "count", ids.size(),
                        "batchId", batchId == null ? null : batchId.toString(), "reason", request.reason()));

        return new AssignClientsResponse(batchId, locked.size(), TransferResponse.ConsultantRef.of(to));
    }

    /**
     * Takes one client away from their consultant without giving them a new one.
     * The client is never deleted: their details, cases, notes and history all
     * stay, and they show up under GET /api/clients?unassigned=true.
     *
     * <p>
     * Refused while the client has a case that is not CLOSED: with nobody
     * assigned, no consultant could see that case any more.
     */
    @Transactional
    public UnassignClientResponse unassign(long actorUserId, long clientId, UnassignClientRequest request) {
        // Lock the row, so a transfer or assignment of this client cannot run at the
        // same time.
        List<Client> locked = clients.findAllByIdForUpdate(List.of(clientId));
        if (locked.isEmpty()) {
            throw ApiException.notFound("client:not-found", "Client " + clientId + " does not exist.");
        }
        Client client = locked.get(0);

        Consultant from = client.getCurrentConsultant();
        if (from == null) {
            throw ApiException.conflict("unassign:not-assigned", "Client not assigned",
                    "This client has no consultant, so there is nothing to unassign.");
        }

        long unfinishedCases = cases.countByClientIdAndStatusNot(clientId, CaseStatus.CLOSED);
        if (unfinishedCases > 0) {
            throw ApiException.conflict("unassign:open-cases", "Client has open cases",
                    "This client has " + unfinishedCases + " case(s) that are not closed. "
                            + "Close them first, or transfer the client to another consultant instead.");
        }

        String reason = request.reason().strip();
        client.assignTo(null);
        history.save(ClientAssignmentHistory.unassigned(client, from, reason, users.getReferenceById(actorUserId)));
        clients.flush();

        audit.record("CLIENT_UNASSIGNED", "CLIENT", clientId,
                meta("fromConsultantId", from.getId(), "reason", reason));

        return new UnassignClientResponse(clientId, TransferResponse.ConsultantRef.of(from));
    }
}
