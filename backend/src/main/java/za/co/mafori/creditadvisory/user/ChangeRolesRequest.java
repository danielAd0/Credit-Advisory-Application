package za.co.mafori.creditadvisory.user;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

/**
 * Body of PATCH /api/users/{id}/roles. Replaces the user's roles with exactly
 * these.
 */
public record ChangeRolesRequest(
        @NotEmpty Set<@NotNull RoleName> roles,
        @NotNull Long version) {
}
