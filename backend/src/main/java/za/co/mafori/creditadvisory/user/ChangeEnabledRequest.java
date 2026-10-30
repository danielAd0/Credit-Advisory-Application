package za.co.mafori.creditadvisory.user;

import jakarta.validation.constraints.NotNull;

/** Body of PATCH /api/users/{id}/enabled. */
public record ChangeEnabledRequest(
        @NotNull Boolean enabled,
        @NotNull Long version) {
}
