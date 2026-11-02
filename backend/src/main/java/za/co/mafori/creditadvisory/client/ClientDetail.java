package za.co.mafori.creditadvisory.client;

import java.time.Instant;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;
import za.co.mafori.creditadvisory.consultant.Consultant;

/**
 * The single-client object (contract section 4): the list item plus the FULL ID
 * number. Only built for a caller who may see this client, and every time it is
 * returned from GET /api/clients/{id} an audit entry is written.
 */
public record ClientDetail(
        long id,
        String idNumber,
        String idNumberMasked,
        String firstName,
        String lastName,
        String email,
        String phone,
        ClientListItem.ConsultantRef consultant,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** Must be called inside a transaction (the consultant link is lazy). */
    public static ClientDetail from(Client client, SaIdNumber idNumber) {
        Consultant c = client.getCurrentConsultant();
        ClientListItem.ConsultantRef consultant = c == null
                ? null
                : new ClientListItem.ConsultantRef(c.getId(), c.getConsultantCode(), c.getFullName(), c.getStatus());
        return new ClientDetail(client.getId(), idNumber.value(), client.getIdNumberMasked(),
                client.getFirstName(), client.getLastName(), client.getEmail(), client.getPhone(), consultant,
                client.getVersion(), client.getCreatedAt(), client.getUpdatedAt());
    }

    /** Never print the full ID number, even by accident. */
    @Override
    public String toString() {
        return "ClientDetail[id=" + id + ", idNumber=" + idNumberMasked + "]";
    }
}
