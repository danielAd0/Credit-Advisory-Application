package za.co.mafori.creditadvisory.consultant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/consultants. A code given is trimmed and upper-cased before
 * saving; without one, the next code is generated (ConsultantCodeGenerator).
 */
public record CreateConsultantRequest(
                // Optional: left out or blank, the next code (C0001, C0002, ...) is generated.
                @Pattern(regexp = "^\\s*([A-Za-z0-9][A-Za-z0-9_-]{0,31})?\\s*$", message = "must be 1-32 letters, digits, '_' or '-', starting with a letter or digit (or leave it out to have one generated)") String consultantCode,

                @NotBlank @Size(max = 200) String fullName) {
}
