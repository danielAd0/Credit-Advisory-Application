package za.co.mafori.creditadvisory.transfer;

import java.util.UUID;

/**
 * Response of POST /api/client-assignments. batchId is null when one client was
 * assigned.
 */
public record AssignClientsResponse(UUID batchId, int assignedCount, TransferResponse.ConsultantRef consultant) {
}
