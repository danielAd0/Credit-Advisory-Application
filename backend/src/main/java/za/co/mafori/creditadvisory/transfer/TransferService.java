package za.co.mafori.creditadvisory.transfer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.client.Client;
import za.co.mafori.creditadvisory.client.ClientRepository;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.consultant.ConsultantRepository;
import za.co.mafori.creditadvisory.user.UserAccount;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * Client handover (FR-13 to FR-17). All or nothing: one transaction, rows
 * locked.
 */
@Service
public class TransferService {

    /**
     * Contract section 5: maximum clients per transfer (to confirm with the
     * business).
     */
    public static final int MAX_CLIENTS = 500;

    private final ClientRepository clients;
    private final ConsultantRepository consultants;
    private final UserAccountRepository users;
    private final ClientAssignmentHistoryRepository history;

    public TransferService(ClientRepository clients, ConsultantRepository consultants,
            UserAccountRepository users, ClientAssignmentHistoryRepository history) {
        this.clients = clients;
        this.consultants = consultants;
        this.users = users;
        this.history = history;
    }

    @Transactional
    public TransferResponse transfer(long actorUserId, TransferRequest request) {
        if (request.fromConsultantId().equals(request.toConsultantId())) {
            throw badRequest("transfer:same-consultant", "Source and destination are the same consultant.");
        }
        Set<Long> ids = new LinkedHashSet<>(request.clientIds());
        if (ids.size() != request.clientIds().size()) {
            throw badRequest("transfer:duplicate-clients", "The same client is listed more than once.");
        }

        Consultant from = loadConsultant(request.fromConsultantId());
        Consultant to = loadConsultant(request.toConsultantId());
        if (!to.getStatus().isActive()) {
            // FR-17. The source may have any status - that is the point of a handover.
            throw ApiException.unprocessable("transfer:destination-not-active", "Destination not active",
                    "Clients can only be moved to an ACTIVE consultant.");
        }

        // Lock the client rows for the rest of this transaction (no concurrent transfer
        // of the same client).
        List<Client> locked = clients.findAllByIdForUpdate(ids);
        if (locked.size() != ids.size()) {
            Set<Long> missing = new LinkedHashSet<>(ids);
            locked.forEach(c -> missing.remove(c.getId()));
            throw ApiException.notFound("transfer:client-not-found", "These clients do not exist: " + missing);
        }

        List<Long> notOwned = locked.stream()
                .filter(c -> !c.isAssignedTo(from.getId()))
                .map(Client::getId)
                .toList();
        if (!notOwned.isEmpty()) {
            throw ApiException.conflict("transfer:ownership-changed", "Clients changed hands",
                    "These clients are no longer assigned to the source consultant: " + notOwned);
        }

        UUID batchId = ids.size() > 1 ? UUID.randomUUID() : null;
        UserAccount actor = users.getReferenceById(actorUserId);
        for (Client client : locked) {
            client.assignTo(to);
            history.save(ClientAssignmentHistory.transfer(client, from, to, request.reason(), batchId, actor));
        }
        // TODO: audit entry CLIENTS_TRANSFERRED once AuditLogWriter is on main.
        clients.flush();

        return new TransferResponse(batchId, locked.size(),
                TransferResponse.ConsultantRef.of(from), TransferResponse.ConsultantRef.of(to));
    }

    @Transactional(readOnly = true)
    public List<AssignmentResponse> history(long clientId) {
        if (!clients.existsById(clientId)) {
            throw ApiException.notFound("client:not-found", "Client " + clientId + " does not exist.");
        }
        return history.findByClientIdOrderByChangedAtDesc(clientId).stream()
                .map(AssignmentResponse::from)
                .toList();
    }

    private Consultant loadConsultant(long id) {
        return consultants.findById(id)
                .orElseThrow(() -> ApiException.notFound("consultant:not-found",
                        "Consultant " + id + " does not exist."));
    }

    private static ApiException badRequest(String type, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, type, "Invalid transfer", detail);
    }
}
