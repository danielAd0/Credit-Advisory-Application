package za.co.mafori.creditadvisory.consultant;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsultantRepository extends JpaRepository<Consultant, Long> {

    Optional<Consultant> findByConsultantCode(String consultantCode);

    boolean existsByConsultantCode(String consultantCode);

    /**
     * GET /api/consultants. Both filters are optional (null = no filter).
     *
     * @param searchPattern a lower-case LIKE pattern such as "%thandi%", already
     *                      escaped
     */
    @Query("""
            select c from Consultant c
            where (:status is null or c.status = :status)
              and (:searchPattern is null
                   or lower(c.consultantCode) like :searchPattern escape '\\'
                   or lower(c.fullName) like :searchPattern escape '\\')
            """)
    Page<Consultant> search(@Param("searchPattern") String searchPattern,
            @Param("status") ConsultantStatus status,
            Pageable pageable);
}
