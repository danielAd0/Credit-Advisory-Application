package za.co.mafori.creditadvisory.client;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of PATCH /api/clients/{id}. Send ALL four editable fields as they should
 * be
 * afterwards (the edit form has them all); null or blank email / phone clears
 * it.
 * The ID number and the consultant cannot be changed here.
 */
public record UpdateClientRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @Email @Size(max = 254) String email,
        @Size(max = 30) String phone,
        @NotNull Long version) {

    /**
     * Trim the email before validation, exactly like CreateClientRequest. Blank
     * clears it.
     */
    public UpdateClientRequest {
        email = email == null || email.isBlank() ? null : email.strip();
    }
}
