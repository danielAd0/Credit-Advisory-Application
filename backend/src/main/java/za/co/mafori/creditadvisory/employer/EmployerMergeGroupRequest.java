package za.co.mafori.creditadvisory.employer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of POST /api/employers/{targetId}/merge-group: every employer to merge
 * into the target, each with the version the screen last read.
 *
 * @param employers at most 50; the target itself may not be in the list
 * @param reason    stored in the audit entry of each merge
 */
public record EmployerMergeGroupRequest(
        @NotEmpty @Size(max = EmployerMergeService.MAX_GROUP) List<@Valid @NotNull Source> employers,
        @NotBlank @Size(max = 500) String reason) {

    /** One employer to merge, and its version. */
    public record Source(@NotNull Long id, @NotNull Long version) {
    }
}
