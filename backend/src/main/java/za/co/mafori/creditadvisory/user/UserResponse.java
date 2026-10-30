package za.co.mafori.creditadvisory.user;

import java.time.Instant;
import java.util.List;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;

/** The User object in the API. Never contains the password hash. */
public record UserResponse(
        long id,
        String email,
        boolean enabled,
        List<RoleName> roles,
        ConsultantLink consultant,
        boolean locked,
        Instant lockedUntil,
        Instant lastLoginAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public record ConsultantLink(long id, String consultantCode, String fullName, ConsultantStatus status) {
    }

    /** Must be called inside a transaction (the consultant link is lazy). */
    public static UserResponse from(UserAccount user, Instant now) {
        Consultant c = user.getConsultant();
        ConsultantLink link = c == null
                ? null
                : new ConsultantLink(c.getId(), c.getConsultantCode(), c.getFullName(), c.getStatus());
        boolean locked = user.isLocked(now);
        return new UserResponse(user.getId(), user.getEmail(), user.isEnabled(),
                user.getRoleNames().stream().sorted().toList(), link, locked,
                locked ? user.getLockedUntil() : null, user.getLastLoginAt(),
                user.getVersion(), user.getCreatedAt(), user.getUpdatedAt());
    }
}
