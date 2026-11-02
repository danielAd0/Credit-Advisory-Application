package za.co.mafori.creditadvisory.audit;

import java.time.Instant;

/**
 * Filters for GET /api/audit-logs. Every field is optional (null = no filter).
 */
public record AuditLogQuery(
        String action,
        String targetType,
        String targetId,
        Long actorUserId,
        Instant from,
        Instant to) {
}
