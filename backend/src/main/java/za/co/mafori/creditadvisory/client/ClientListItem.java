package za.co.mafori.creditadvisory.client;

import java.time.Instant;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;

/**
 * The Client list item (contract section 4). Never contains the full ID number.
 */
public record ClientListItem(
        long id,
        String idNumberMasked,
        String firstName,
        String lastName,
        String email,
        String phone,
        ConsultantRef consultant,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public record ConsultantRef(long id, String consultantCode, String fullName, ConsultantStatus status) {
    }

    /** Must be called inside a transaction (the consultant link is lazy). */
    public static ClientListItem from(Client client) {
        Consultant c = client.getCurrentConsultant();
        ConsultantRef consultant = c == null
                ? null
                : new ConsultantRef(c.getId(), c.getConsultantCode(), c.getFullName(), c.getStatus());
        return new ClientListItem(client.getId(), client.getIdNumberMasked(), client.getFirstName(),
                client.getLastName(), client.getEmail(), client.getPhone(), consultant,
                client.getVersion(), client.getCreatedAt(), client.getUpdatedAt());
    }
}
