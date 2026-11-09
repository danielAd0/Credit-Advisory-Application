package za.co.mafori.creditadvisory.employer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployerRepository extends JpaRepository<Employer, Long> {

        @Query("""
                        select count(e) > 0 from Employer e
                        where lower(trim(e.name)) = lower(trim(:name)) and e.id <> :excludeId
                        """)
        boolean nameTaken(@Param("name") String name, @Param("excludeId") long excludeId);

        @Query("""
                        select e from Employer e
                        where (:active is null or e.active = :active)
                                        and (:needsReview is null or e.needsReview = :needsReview)
                                        and (:employerType is null or lower(e.employerType) = :employerType)
                          and (:searchPattern is null
                               or lower(e.name) like :searchPattern escape '\\'
                               or lower(e.company) like :searchPattern escape '\\')
                        """)
        Page<Employer> search(@Param("searchPattern") String searchPattern, @Param("active") Boolean active,
                        @Param("employerType") String employerType, @Param("needsReview") Boolean needsReview,
                        Pageable pageable);
}
