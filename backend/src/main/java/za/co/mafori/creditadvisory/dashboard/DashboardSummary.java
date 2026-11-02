package za.co.mafori.creditadvisory.dashboard;

/**
 * GET /api/dashboard/summary (contract section 7). Counts only - never names or
 * ID
 * numbers.
 *
 * @param scope                  "ALL" for MANAGER / ADMIN, "OWN" for a
 *                               CONSULTANT
 * @param totalClients           all clients (ALL) or the consultant's own
 *                               clients (OWN)
 * @param unassignedClients      clients with no consultant; null for OWN
 * @param clientsNeedingHandover clients whose consultant is INACTIVE or
 *                               SUSPENDED; null for OWN
 * @param consultants            consultants per status; null for OWN
 */
public record DashboardSummary(
        String scope,
        long totalClients,
        Long unassignedClients,
        Long clientsNeedingHandover,
        ConsultantCounts consultants) {

    public record ConsultantCounts(long active, long inactive, long suspended) {
    }
}
