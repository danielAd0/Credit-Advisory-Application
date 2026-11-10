package za.co.mafori.creditadvisory.productquotation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductQuotationRepository extends JpaRepository<ProductQuotation, Long> {

    /**
     * True if another product quotation already has this name, ignoring capitals
     * and extra spaces (the database refuses it too:
     * uq_product_quotations_name_ci).
     *
     * @param excludeId the one being renamed, or 0 for a new one
     */
    @Query("""
            select count(q) > 0 from ProductQuotation q
            where lower(q.name) = lower(:name) and q.id <> :excludeId
            """)
    boolean nameTaken(@Param("name") String name, @Param("excludeId") long excludeId);

    /**
     * GET /api/product-quotations. Both filters optional (null = no filter).
     *
     * @param searchPattern lower-case, escaped LIKE pattern matched against the
     *                      name
     */
    @Query("""
            select q from ProductQuotation q
            where (:active is null or q.active = :active)
              and (:searchPattern is null or lower(q.name) like :searchPattern escape '\\')
            """)
    Page<ProductQuotation> search(@Param("searchPattern") String searchPattern, @Param("active") Boolean active,
            Pageable pageable);
}
