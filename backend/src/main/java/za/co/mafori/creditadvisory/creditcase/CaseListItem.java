package za.co.mafori.creditadvisory.creditcase;

import java.time.Instant;
import za.co.mafori.creditadvisory.client.Client;
import za.co.mafori.creditadvisory.client.ClientListItem.ConsultantRef;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.user.UserAccount;

/**
 * The Case object in lists (contract section 9). No notes, no full ID number.
 */
public record CaseListItem(
        long id,
        ClientRef client,
        String title,
        CaseStatus status,
        Instant openedAt,
        Instant closedAt,
        UserRef createdBy,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * The client a case belongs to, with the consultant they are assigned to NOW.
     */
    public record ClientRef(long id, String idNumberMasked, String firstName, String lastName,
            ConsultantRef consultant) {

        static ClientRef from(Client client) {
            Consultant c = client.getCurrentConsultant();
            ConsultantRef consultant = c == null
                    ? null
                    : new ConsultantRef(c.getId(), c.getConsultantCode(), c.getFullName(), c.getStatus());
            return new ClientRef(client.getId(), client.getIdNumberMasked(), client.getFirstName(),
                    client.getLastName(), consultant);
        }
    }

    /**
     * Who did something. consultantName is null for users without a consultant
     * record.
     */
    public record UserRef(long id, String email, String consultantName) {

        static UserRef from(UserAccount user) {
            Consultant c = user.getConsultant();
            return new UserRef(user.getId(), user.getEmail(), c == null ? null : c.getFullName());
        }
    }

    /** Must be called inside a transaction (the links are lazy). */
    public static CaseListItem from(CreditCase creditCase) {
        return new CaseListItem(creditCase.getId(), ClientRef.from(creditCase.getClient()),
                creditCase.getTitle(), creditCase.getStatus(), creditCase.getOpenedAt(),
                creditCase.getClosedAt(), UserRef.from(creditCase.getCreatedBy()), creditCase.getVersion(),
                creditCase.getCreatedAt(), creditCase.getUpdatedAt());
    }
}
