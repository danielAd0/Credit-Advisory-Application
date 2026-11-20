package za.co.mafori.creditadvisory.employer;

import java.util.List;

/**
 * Answer of GET /api/employers/duplicate-suggestions (docs/api-admin.md,
 * section 3): groups of employers that are probably the same employer.
 *
 * @param groupCount    how many groups
 * @param employerCount how many employers are in a group, all groups together
 */
public record EmployerDuplicatesResponse(int groupCount, int employerCount, List<Group> groups) {

    /**
     * @param suggestedTargetId the employer to merge the others into, or null when
     *                          none of them is active (activate one first)
     * @param employers         the suggested target first, then by name
     */
    public record Group(Long suggestedTargetId, List<Member> employers) {
    }

    /**
     * @param clients how many clients have this employer now
     * @param version for POST /api/employers/{id}/merge
     */
    public record Member(long id, String name, boolean active, boolean needsReview, long clients, long version) {
    }

    static EmployerDuplicatesResponse from(List<EmployerDuplicates.Group> groups) {
        List<Group> out = groups.stream().map(g -> new Group(
                g.suggestedTarget() == null ? null : g.suggestedTarget().id(),
                g.employers().stream().map(c -> new Member(c.id(), c.name(), c.active(), c.needsReview(),
                        c.clients(), c.version())).toList()))
                .toList();
        return new EmployerDuplicatesResponse(out.size(), out.stream().mapToInt(g -> g.employers().size()).sum(),
                out);
    }
}
