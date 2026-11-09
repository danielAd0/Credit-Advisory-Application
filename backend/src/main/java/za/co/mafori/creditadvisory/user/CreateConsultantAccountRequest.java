package za.co.mafori.creditadvisory.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/consultant-accounts: a new consultant record and its login,
 * created together. The code is optional (generated when left out), else
 * trimmed and upper-cased; the password must pass
 * PasswordPolicy and is given to the consultant out of band.
 */
public record CreateConsultantAccountRequest(
        // Optional: left out or blank, the next code (C0001, C0002, ...) is generated.
        @Pattern(regexp = "^\\s*([A-Za-z0-9][A-Za-z0-9_-]{0,31})?\\s*$", message = "must be 1-32 letters, digits, '_' or '-', starting with a letter or digit (or leave it out to have one generated)") String consultantCode,
        @NotBlank @Size(max = 200) String fullName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_BYTES) String temporaryPassword) {

    @Override
    public String toString() {
        return "CreateConsultantAccountRequest[consultantCode=" + consultantCode + ", fullName=" + fullName
                + ", email=" + email + ", temporaryPassword=****]";
    }
}
