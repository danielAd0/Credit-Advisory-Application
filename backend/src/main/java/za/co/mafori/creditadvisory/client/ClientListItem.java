package za.co.mafori.creditadvisory.client;

import java.time.Instant;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.employer.Employer;
import za.co.mafori.creditadvisory.productquotation.ProductQuotation;

/**
 * The Client list item (contract section 4): what the Clients page shows for
 * one row. Never contains the full ID number.
 *
 * @param phone      the cellphone
 * @param latestSale the client's most recent sale (its payment term and
 *                   structure), or null if they have no sales yet
 */
public record ClientListItem(
                long id,
                String idNumberMasked,
                String firstName,
                String lastName,
                String email,
                String phone,
                String workPhone,
                String homePhone,
                NamedRef employer,
                NamedRef productQuotation,
                String fileNumber,
                String fileId,
                PaymentMethod paymentMethod,
                LatestSale latestSale,
                ConsultantRef consultant,
                long version,
                Instant createdAt,
                Instant updatedAt) {

        public record ConsultantRef(long id, String consultantCode, String fullName, ConsultantStatus status) {
        }

        /**
         * A list entry the client points at: an employer or a product quotation.
         * active=false means it was deactivated after it was chosen.
         */
        public record NamedRef(long id, String name, boolean active) {
        }

        /**
         * Without the latest sale. Must be called inside a transaction (lazy links).
         */
        public static ClientListItem from(Client client) {
                return from(client, null);
        }

        /** Must be called inside a transaction (the links are lazy). */
        public static ClientListItem from(Client client, LatestSale latestSale) {
                return new ClientListItem(client.getId(), client.getIdNumberMasked(), client.getFirstName(),
                                client.getLastName(), client.getEmail(), client.getPhone(), client.getWorkPhone(),
                                client.getHomePhone(), employerRef(client.getEmployer()),
                                quotationRef(client.getProductQuotation()), client.getFileNumber(), client.getFileId(),
                                client.getPaymentMethod(), latestSale, consultantRef(client.getCurrentConsultant()),
                                client.getVersion(), client.getCreatedAt(), client.getUpdatedAt());
        }

        static ConsultantRef consultantRef(Consultant c) {
                return c == null ? null
                                : new ConsultantRef(c.getId(), c.getConsultantCode(), c.getFullName(), c.getStatus());
        }

        static NamedRef employerRef(Employer e) {
                return e == null ? null : new NamedRef(e.getId(), e.getName(), Boolean.TRUE.equals(e.isActive()));
        }

        static NamedRef quotationRef(ProductQuotation q) {
                return q == null ? null : new NamedRef(q.getId(), q.getName(), q.isActive());
        }
}
