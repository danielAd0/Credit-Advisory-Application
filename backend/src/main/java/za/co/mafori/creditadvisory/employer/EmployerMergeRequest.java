package za.co.mafori.creditadvisory.employer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EmployerMergeRequest(@NotNull Long targetEmployerId, @NotNull Long version,
        @NotBlank @Size(max = 500) String reason) {

}
