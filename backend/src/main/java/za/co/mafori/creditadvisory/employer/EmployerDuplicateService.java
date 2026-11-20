package za.co.mafori.creditadvisory.employer;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /api/employers/duplicate-suggestions: every employer, grouped by
 * EmployerDuplicates. Read-only: nothing is merged or changed here, so nothing
 * is audited.
 */
@Service
public class EmployerDuplicateService {

    private final JdbcTemplate jdbc;

    public EmployerDuplicateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public EmployerDuplicatesResponse suggestions() {
        List<EmployerDuplicates.Candidate> employers = jdbc.query("""
                select e.id, e.name, e.active, e.needs_review, e.version,
                       (select count(*) from clients c where c.employer_id = e.id)
                from employers e
                order by e.id
                """, (rs, n) -> new EmployerDuplicates.Candidate(rs.getLong(1), rs.getString(2), rs.getBoolean(3),
                rs.getBoolean(4), rs.getLong(6), rs.getLong(5)));
        return EmployerDuplicatesResponse.from(EmployerDuplicates.groups(employers));
    }
}
