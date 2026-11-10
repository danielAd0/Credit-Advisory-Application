package za.co.mafori.creditadvisory.client;

import java.time.Instant;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;

/**
 * The single-client object (contract section 4): the list item plus the FULL ID
 * number - but only when it was asked for. idNumber is null unless the caller
 * asked for it with GET /api/clients/{id}?include=idNumber (audited as
 * CLIENT_VIEWED), or just typed it in to create the client.
 */
public record ClientDetail(
        long id,
        String idNumber,
        String idNumberMasked,
        String firstName,
        String lastName,
        String email,
        String phone,
        String workPhone,
        String homePhone,
        ClientListItem.NamedRef employer,
        ClientListItem.NamedRef productQuotation,
        String fileNumber,
        String fileId,
        PaymentMethod paymentMethod,
        LatestSale latestSale,
        ClientListItem.ConsultantRef consultant,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Must be called inside a transaction (the links are lazy).
     *
     * @param idNumber   the full ID number to include, or null to leave it out
     * @param latestSale the client's most recent sale, or null if none
     */
    public static ClientDetail from(Client client, SaIdNumber idNumber, LatestSale latestSale) {
        return new ClientDetail(client.getId(), idNumber == null ? null : idNumber.value(), client.getIdNumberMasked(),
                client.getFirstName(), client.getLastName(), client.getEmail(), client.getPhone(),
                client.getWorkPhone(), client.getHomePhone(), ClientListItem.employerRef(client.getEmployer()),
                ClientListItem.quotationRef(client.getProductQuotation()), client.getFileNumber(),
                client.getFileId(), client.getPaymentMethod(), latestSale,
                ClientListItem.consultantRef(client.getCurrentConsultant()), client.getVersion(),
                client.getCreatedAt(), client.getUpdatedAt());
    }

    /** Never print the full ID number, even by accident. */
    @Override
    public String toString() {
        return "ClientDetail[id=" + id + ", idNumber=" + idNumberMasked + "]";
    }
}
