package za.co.mafori.creditadvisory.dashboard;

/**
 * GET /api/dashboard/summary (contract section 7). Counts only - never names,
 * ID
 * numbers or case content.
 *
 * @param scope                  "ALL" for MANAGER / ADMIN, "OWN" for a
 *                               CONSULTANT
 * @param totalClients           all clients (ALL) or the consultant's own
 *                               clients (OWN)
 * @param unassignedClients      clients with no consultant; null for OWN
 * @param clientsNeedingHandover clients whose consultant is INACTIVE or
 *                               SUSPENDED; null for OWN
 * @param consultants            consultants per status; null for OWN
 * @param cases                  credit cases per status: all cases (ALL), or
 *                               the cases of the consultant's own clients (OWN)
 */
public record DashboardSummary(
        String scope,
        long totalClients,
        Long unassignedClients,
        Long clientsNeedingHandover,
        ConsultantCounts consultants,
        CaseCounts cases) {

    public record ConsultantCounts(long active, long inactive, long suspended) {
    }

    public record CaseCounts(long open, long inProgress, long closed) {

        static final CaseCounts NONE = new CaseCounts(0, 0, 0);
    }
}
