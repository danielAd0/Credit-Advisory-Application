package za.co.mafori.creditadvisory.target;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/targets. current is the row in force today (null if none);
 * history lists every row, newest effective date first, including future ones.
 */
public record TargetsResponse(LocalDate today, TargetResponse current, List<TargetResponse> history) {
}
