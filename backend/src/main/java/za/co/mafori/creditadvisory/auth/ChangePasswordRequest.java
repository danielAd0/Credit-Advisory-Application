package za.co.mafori.creditadvisory.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import za.co.mafori.creditadvisory.user.PasswordPolicy;

/** Body of POST /api/auth/change-password. */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 200) String currentPassword,
        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_BYTES) String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=****, newPassword=****]";
    }
}
