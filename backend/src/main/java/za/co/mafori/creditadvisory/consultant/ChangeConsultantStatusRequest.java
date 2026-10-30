package za.co.mafori.creditadvisory.consultant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of PATCH /api/consultants/{id}/status */
public record ChangeConsultantStatusRequest(@NotNull ConsultantStatus status, @NotBlank @Size(max = 500) String reason,
        @NotNull Long version) {
}
