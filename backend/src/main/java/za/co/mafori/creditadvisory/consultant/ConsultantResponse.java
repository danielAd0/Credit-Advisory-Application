package za.co.mafori.creditadvisory.consultant;

import java.time.Instant;

/**
 * The Consultant object in the API (contract section 3).
 *
 * @param clientCount how many clients are currently assigned to this consultant
 */
public record ConsultantResponse(long id, String consultantCode, String fullName, ConsultantStatus status,
        Instant statusChangedAt, long clientCount, long version, Instant createdAt, Instant updatedAt) {

    public static ConsultantResponse from(Consultant c, long clientCount) {
        return new ConsultantResponse(c.getId(), c.getConsultantCode(), c.getFullName(), c.getStatus(),
                c.getStatusChangedAt(), clientCount, c.getVersion(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
