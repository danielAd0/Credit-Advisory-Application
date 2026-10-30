package za.co.mafori.creditadvisory.consultant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of PATCH /api/consuktants/{id}. The code cannot be changed. */
public record RenameConsultantRequest(@NotBlank @Size(max = 200) String fullName, @NotNull Long version) {

}
