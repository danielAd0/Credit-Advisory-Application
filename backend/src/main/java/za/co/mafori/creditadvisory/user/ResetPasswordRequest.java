package za.co.mafori.creditadvisory.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/users/{id}/password-reset. */
public record ResetPasswordRequest(
        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_BYTES) String temporaryPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[temporaryPassword=****]";
    }
}
