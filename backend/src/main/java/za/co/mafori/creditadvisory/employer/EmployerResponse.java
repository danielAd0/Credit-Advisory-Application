package za.co.mafori.creditadvisory.employer;

import java.time.Instant;

/** The Employer object in the API. */
public record EmployerResponse(long id, String name, String company, String employerType, boolean active,
        long version, Instant createdAt, Instant updatedAt) {

    public static EmployerResponse from(Employer e) {
        return new EmployerResponse(e.getId(), e.getName(), e.getCompany(), e.getEmployerType(), e.isActive(),
                e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
