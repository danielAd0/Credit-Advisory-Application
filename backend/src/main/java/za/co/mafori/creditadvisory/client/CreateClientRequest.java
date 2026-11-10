package za.co.mafori.creditadvisory.client;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/clients. idNumber and the three phones are checked and
 * normalised by ClientService (SaIdNumber, PhoneNumbers); employerId and
 * productQuotationId must point at an ACTIVE entry of their list.
 *
 * @param phone         the cellphone
 * @param fileNumber    digits only, up to 20; a leading 0 is kept
 * @param fileId        digits only, up to 20
 * @param paymentMethod null = not recorded
 */
public record CreateClientRequest(
        @NotBlank @Size(max = 30) String idNumber,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @Email @Size(max = 254) String email,
        @Size(max = 30) String phone,
        @Size(max = 30) String workPhone,
        @Size(max = 30) String homePhone,
        Long employerId,
        Long productQuotationId,
        @Pattern(regexp = ClientFields.FILE_REFERENCE, message = ClientFields.FILE_REFERENCE_MESSAGE) String fileNumber,
        @Pattern(regexp = ClientFields.FILE_REFERENCE, message = ClientFields.FILE_REFERENCE_MESSAGE) String fileId,
        PaymentMethod paymentMethod,
        Long consultantId) {

    /**
     * Runs when the JSON is read, BEFORE validation: a pasted " name@example.com "
     * or " 012345 " is trimmed first, so the checks see the clean value. Blank
     * means none.
     */
    public CreateClientRequest {
        email = ClientFields.trimToNull(email);
        fileNumber = ClientFields.trimToNull(fileNumber);
        fileId = ClientFields.trimToNull(fileId);
    }

    /** Never print the ID number, even by accident in a log line. */
    @Override
    public String toString() {
        return "CreateClientRequest[idNumber=****, consultantId=" + consultantId + "]";
    }
}
