package za.co.mafori.creditadvisory.client;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;

public interface ClientRepository extends JpaRepository<Client, Long> {

    /**
     * Finds a client by the lookup hash of their ID number (never by the plain
     * number).
     */
    Optional<Client> findByIdNumberHash(byte[] idNumberHash);

    boolean existsByIdNumberHash(byte[] idNumberHash);

    /**
     * GET /api/clients. Every filter is optional (null / false = no filter).
     *
     * @param searchPattern lower-case, escaped LIKE pattern matched against first,
     *                      last and full name
     */
    @EntityGraph(attributePaths = { "currentConsultant" })
    @Query(value = """
            select c from Client c left join c.currentConsultant con
            where (:consultantId is null or con.id = :consultantId)
              and (:consultantStatus is null or con.status = :consultantStatus)
              and (:unassignedOnly = false or con is null)
              and (:searchPattern is null
                   or lower(c.firstName) like :searchPattern escape '\\'
                   or lower(c.lastName) like :searchPattern escape '\\'
                   or lower(concat(c.firstName, ' ', c.lastName)) like :searchPattern escape '\\')
            """, countQuery = """
            select count(c) from Client c left join c.currentConsultant con
            where (:consultantId is null or con.id = :consultantId)
              and (:consultantStatus is null or con.status = :consultantStatus)
              and (:unassignedOnly = false or con is null)
              and (:searchPattern is null
                   or lower(c.firstName) like :searchPattern escape '\\'
                   or lower(c.lastName) like :searchPattern escape '\\'
                   or lower(concat(c.firstName, ' ', c.lastName)) like :searchPattern escape '\\')
            """)
    Page<Client> search(@Param("searchPattern") String searchPattern,
            @Param("consultantId") Long consultantId,
            @Param("consultantStatus") ConsultantStatus consultantStatus,
            @Param("unassignedOnly") boolean unassignedOnly,
            Pageable pageable);

    /**
     * Loads clients and locks their rows until the transaction ends, so two
     * transfers of the same
     * client cannot run at the same time. Ordered by id so concurrent transfers
     * lock in the same
     * order and cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Client c where c.id in :ids order by c.id")
    List<Client> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);
}
