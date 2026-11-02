package za.co.mafori.creditadvisory.client;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/clients. idNumber and phone are checked and normalised by
 * ClientService (SaIdNumber, PhoneNumbers).
 */
public record CreateClientRequest(
        @NotBlank @Size(max = 30) String idNumber,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @Email @Size(max = 254) String email,
        @Size(max = 30) String phone,
        Long consultantId) {

    /**
     * Runs when the JSON is read, BEFORE validation: a pasted " name@example.com "
     * is trimmed first, so @Email checks the clean value. Blank means no email.
     */
    public CreateClientRequest {
        email = email == null || email.isBlank() ? null : email.strip();
    }

    /** Never print the ID number, even by accident in a log line. */
    @Override
    public String toString() {
        return "CreateClientRequest[idNumber=****, consultantId=" + consultantId + "]";
    }
}
