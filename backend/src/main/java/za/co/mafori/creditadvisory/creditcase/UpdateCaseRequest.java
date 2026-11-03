package za.co.mafori.creditadvisory.creditcase;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of PATCH /api/cases/{id}. Send both editable fields as they should be
 * afterwards. The client and the notes cannot be changed here.
 */
public record UpdateCaseRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull CaseStatus status,
        @NotNull Long version) {
}
