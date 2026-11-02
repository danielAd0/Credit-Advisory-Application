package za.co.mafori.creditadvisory.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsByCode(String code);

    /**
     * GET /api/products. Both filters optional (null = no filter).
     *
     * @param searchPattern lower-case, escaped LIKE pattern matched against code
     *                      and name
     */
    @Query("""
            select p from Product p
            where (:active is null or p.active = :active)
              and (:searchPattern is null
                   or lower(p.code) like :searchPattern escape '\\'
                   or lower(p.name) like :searchPattern escape '\\')
            """)
    Page<Product> search(@Param("searchPattern") String searchPattern, @Param("active") Boolean active,
            Pageable pageable);
}
