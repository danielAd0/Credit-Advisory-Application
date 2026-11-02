package za.co.mafori.creditadvisory.transfer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of POST /api/client-assignments: give one or more UNASSIGNED clients to
 * an ACTIVE consultant. Clients that already have a consultant are moved with
 * POST /api/client-transfers instead.
 */
public record AssignClientsRequest(
                @NotNull Long consultantId,
                @NotEmpty @Size(max = TransferService.MAX_CLIENTS) List<@NotNull Long> clientIds,
                @NotBlank @Size(max = 500) String reason) {
}
