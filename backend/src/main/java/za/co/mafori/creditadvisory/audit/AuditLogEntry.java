package za.co.mafori.creditadvisory.audit;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

/**
 * One row of GET /api/audit-logs (contract section 8).
 *
 * @param actor    who did it; null for events with nobody signed in
 *                 (ACCOUNT_LOCKED,
 *                 SIGN_IN_RATE_LIMITED)
 * @param metadata the stored JSON object, passed through as-is
 */
public record AuditLogEntry(
        long id,
        Instant occurredAt,
        String action,
        Actor actor,
        String targetType,
        String targetId,
        @JsonRawValue String metadata) {

    public record Actor(long id, String email) {
    }
}
