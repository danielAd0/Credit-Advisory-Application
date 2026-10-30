package za.co.mafori.creditadvisory.transfer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Body of POST /api/client-transfers (contract section 5). */
public record TransferRequest(
        @NotNull Long fromConsultantId,
        @NotNull Long toConsultantId,
        @NotEmpty @Size(max = TransferService.MAX_CLIENTS) List<@NotNull Long> clientIds,
        @NotBlank @Size(max = 500) String reason) {
}
