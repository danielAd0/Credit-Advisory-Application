package za.co.mafori.creditadvisory.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * Body of POST /api/users. consultantId is required when roles contains
 * CONSULTANT.
 */
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_BYTES) String temporaryPassword,
        @NotEmpty Set<@NotNull RoleName> roles,
        Long consultantId) {

    @Override
    public String toString() {
        return "CreateUserRequest[email=" + email + ", roles=" + roles
                + ", consultantId=" + consultantId + ", temporaryPassword=****]";
    }
}
