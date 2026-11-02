package za.co.mafori.creditadvisory.employer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/employers. Columns follow the Mafori Employer Schedule:
 * Employer, Company, Employer Type.
 */
public record EmployerRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 100) String company,
        @Size(max = 40) String employerType) {
}
