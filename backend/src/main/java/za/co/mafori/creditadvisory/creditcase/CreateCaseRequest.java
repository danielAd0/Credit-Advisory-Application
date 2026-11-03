package za.co.mafori.creditadvisory.creditcase;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /api/cases. A new case always starts OPEN. */
public record CreateCaseRequest(
        @NotNull Long clientId,
        @NotBlank @Size(max = 200) String title) {
}
