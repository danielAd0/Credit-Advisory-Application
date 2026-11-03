package za.co.mafori.creditadvisory.creditcase;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditCaseRepository extends JpaRepository<CreditCase, Long> {

    /**
     * One case with everything the response needs - for the single-case endpoints.
     */
    @EntityGraph(attributePaths = { "client", "client.currentConsultant", "createdBy", "createdBy.consultant" })
    Optional<CreditCase> findWithClientById(Long id);

    /**
     * GET /api/cases. Every filter is optional (null = no filter).
     *
     * @param consultantId only cases of clients CURRENTLY assigned to this
     *                     consultant (so after a transfer the cases move with the
     *                     client)
     */
    @EntityGraph(attributePaths = { "client", "client.currentConsultant", "createdBy", "createdBy.consultant" })
    @Query(value = """
            select cc from CreditCase cc join cc.client c left join c.currentConsultant con
            where (:consultantId is null or con.id = :consultantId)
              and (:clientId is null or c.id = :clientId)
              and (:status is null or cc.status = :status)
            """, countQuery = """
            select count(cc) from CreditCase cc join cc.client c left join c.currentConsultant con
            where (:consultantId is null or con.id = :consultantId)
              and (:clientId is null or c.id = :clientId)
              and (:status is null or cc.status = :status)
            """)
    Page<CreditCase> search(@Param("consultantId") Long consultantId,
            @Param("clientId") Long clientId,
            @Param("status") CaseStatus status,
            Pageable pageable);
}
