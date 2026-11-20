package za.co.mafori.creditadvisory.employer;

import java.util.List;

/**
 * Answer of POST /api/employers/{targetId}/merge-group.
 *
 * @param target            the employer everything was merged into, as it is
 *                          now
 * @param merged            one entry per merged employer, in id order (the same
 *                          as a single merge answers)
 * @param clientsMoved      all merges together
 * @param transactionsMoved all merges together
 */
public record EmployerMergeGroupResponse(EmployerResponse target, List<EmployerMergeResponse> merged,
        int clientsMoved, int transactionsMoved) {
}
