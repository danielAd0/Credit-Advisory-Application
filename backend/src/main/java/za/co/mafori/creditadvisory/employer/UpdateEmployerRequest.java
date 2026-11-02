package za.co.mafori.creditadvisory.employer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of PATCH /api/employers/{id}. Replaces every editable field. */
public record UpdateEmployerRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 100) String company,
        @Size(max = 40) String employerType,
        @NotNull Long version) {
}
