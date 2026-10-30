package za.co.mafori.creditadvisory.consultant;

import java.time.Instant;

/** The Consultant object in the API (contract section 3). */
public record ConsultantResponse(long id, String consultantCode, String fullName, ConsultantStatus status,
        Instant statusChangedAt, long version, Instant createdAt, Instant updatedAt) {

    public static ConsultantResponse from(Consultant c) {
        return new ConsultantResponse(c.getId(), c.getConsultantCode(), c.getFullName(), c.getStatus(),
                c.getStatusChangedAt(), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
