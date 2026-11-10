package za.co.mafori.creditadvisory.client;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of PATCH /api/clients/{id}. Send ALL editable fields as they should be
 * afterwards (the edit form has them all): null or blank clears a value. The ID
 * number and the consultant cannot be changed here.
 *
 * <p>
 * An employer or product quotation that was deactivated after it was chosen
 * may stay on the client; choosing a different one requires an ACTIVE one.
 */
public record UpdateClientRequest(
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
        @NotNull Long version) {

    /** Trim before validation, exactly like CreateClientRequest. Blank clears. */
    public UpdateClientRequest {
        email = ClientFields.trimToNull(email);
        fileNumber = ClientFields.trimToNull(fileNumber);
        fileId = ClientFields.trimToNull(fileId);
    }
}
