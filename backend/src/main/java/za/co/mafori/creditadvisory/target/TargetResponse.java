package za.co.mafori.creditadvisory.target;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of sales_targets. weeklyTarget can be null on very old rows (before
 * V13); setByEmail is null for the seeded row.
 */
public record TargetResponse(long id, LocalDate effectiveFrom, BigDecimal dailyTarget, BigDecimal weeklyTarget,
        String setByEmail, Instant createdAt) {
}
