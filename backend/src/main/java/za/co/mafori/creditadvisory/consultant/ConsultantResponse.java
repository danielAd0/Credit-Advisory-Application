package za.co.mafori.creditadvisory.consultant;

import java.time.Instant;

/**
 * The Consultant object in the API (contract section 3).
 *
 * @param email       the consultant's email, or null (V23)
 * @param clientCount how many clients are currently assigned to this consultant
 */
public record ConsultantResponse(long id, String consultantCode, String fullName, String email, ConsultantStatus status,
        Instant statusChangedAt, long clientCount, long version, Instant createdAt, Instant updatedAt) {

    public static ConsultantResponse from(Consultant c, long clientCount) {
        return new ConsultantResponse(c.getId(), c.getConsultantCode(), c.getFullName(), c.getEmail(), c.getStatus(),
                c.getStatusChangedAt(), clientCount, c.getVersion(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
