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
import za.co.mafori.creditadvisory.user.UserAccount;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * First assignment of unassigned clients to a consultant (FR-13, FR-16, FR-17).
 * All or nothing: one transaction, client rows locked, one history row per
 * client.
 */
@Service
public class ClientAssignmentService {

    private final ClientRepository clients;
    private final ConsultantRepository consultants;
    private final UserAccountRepository users;
    private final ClientAssignmentHistoryRepository history;
    private final AuditLogWriter audit;

    public ClientAssignmentService(ClientRepository clients, ConsultantRepository consultants,
            UserAccountRepository users, ClientAssignmentHistoryRepository history, AuditLogWriter audit) {
        this.clients = clients;
        this.consultants = consultants;
        this.users = users;
        this.history = history;
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
}
