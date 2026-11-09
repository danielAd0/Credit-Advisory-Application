package za.co.mafori.creditadvisory.transfer;

/**
 * Answer of POST /api/clients/{id}/unassign: which client, and who had them
 * until now.
 */
public record UnassignClientResponse(
        long clientId,
        TransferResponse.ConsultantRef fromConsultant) {
}
