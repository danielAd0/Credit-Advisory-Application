package za.co.mafori.creditadvisory.transfer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/clients/{id}/unassign: why the client no longer has a
 * consultant. Stored in the assignment history and the audit log.
 */
public record UnassignClientRequest(
        @NotBlank @Size(max = 500) String reason) {
}
