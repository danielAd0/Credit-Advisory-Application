package za.co.mafori.creditadvisory.transfer;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientAssignmentHistoryRepository extends JpaRepository<ClientAssignmentHistory, Long> {
    /**
     * Full history of one client, newest first, with consultants and actor loaded
     * (for GET /assignments)
     */
    @EntityGraph(attributePaths = { "fromConsultant", "toConsultant", "changedBy" })
    List<ClientAssignmentHistory> findByClientIdOrderByChangedAtDesc(Long clientId);
}
