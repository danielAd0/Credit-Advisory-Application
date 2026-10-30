package za.co.mafori.creditadvisory.consultant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/consultants. The code is trimmed and upper-cased before
 * saving.
 */
public record CreateConsultantRequest(
        @NotBlank @Pattern(regexp = "^\\s*[A-Za-z0-9][A-Za-z0-9_-]{0,31}\\s*$", message = "must be 1-32 letters, digits, '_' or '-', starting with a letter or digit") String consultantCode,

        @NotBlank @Size(max = 200) String fullName) {
}
