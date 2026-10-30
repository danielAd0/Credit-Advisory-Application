package za.co.mafori.creditadvisory.transfer;

import java.util.UUID;
import za.co.mafori.creditadvisory.consultant.Consultant;

/**
 * Response of POST /api/client-transfers. batchId is null for a single-client
 * transfer.
 */
public record TransferResponse(UUID batchId, int transferredCount, ConsultantRef fromConsultant,
        ConsultantRef toConsultant) {

    public record ConsultantRef(long id, String consultantCode, String fullName) {

        static ConsultantRef of(Consultant c) {
            return new ConsultantRef(c.getId(), c.getConsultantCode(), c.getFullName());
        }
    }
}
