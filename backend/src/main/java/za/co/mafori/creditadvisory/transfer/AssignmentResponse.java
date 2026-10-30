package za.co.mafori.creditadvisory.transfer;

import java.time.Instant;
import java.util.UUID;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.user.UserAccount;

/** One entry of GET /api/clients/{id}/assignments (contract section 5). */
public record AssignmentResponse(
        Instant changedAt,
        AssignmentChangeType changeType,
        TransferResponse.ConsultantRef fromConsultant,
        TransferResponse.ConsultantRef toConsultant,
        String reason,
        Actor changedBy,
        UUID batchId) {

    public record Actor(long id, String email) {
    }

    static AssignmentResponse from(ClientAssignmentHistory h) {
        UserAccount by = h.getChangedBy();
        return new AssignmentResponse(h.getChangedAt(), h.getChangeType(),
                ref(h.getFromConsultant()), ref(h.getToConsultant()), h.getReason(),
                new Actor(by.getId(), by.getEmail()), h.getTransferBatchId());
    }

    private static TransferResponse.ConsultantRef ref(Consultant c) {
        return c == null ? null : TransferResponse.ConsultantRef.of(c);
    }
}
