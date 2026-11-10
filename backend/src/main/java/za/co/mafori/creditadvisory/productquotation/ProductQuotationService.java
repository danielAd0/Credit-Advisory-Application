package za.co.mafori.creditadvisory.productquotation;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.LikePattern;
import za.co.mafori.creditadvisory.common.web.PageResponse;

/** The product quotation list. Role checks live on the controller. */
@Service
public class ProductQuotationService {

    private static final int MAX_PAGE_SIZE = 200;

    private final ProductQuotationRepository quotations;
    private final AuditLogWriter audit;

    public ProductQuotationService(ProductQuotationRepository quotations, AuditLogWriter audit) {
        this.quotations = quotations;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductQuotationResponse> search(String search, Boolean active, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by("name").and(Sort.by("id")));
        return PageResponse.of(quotations.search(LikePattern.contains(search), active, pageable),
                ProductQuotationResponse::from);
    }

    @Transactional(readOnly = true)
    public ProductQuotationResponse get(long id) {
        return ProductQuotationResponse.from(load(id));
    }

    @Transactional
    public ProductQuotationResponse create(ProductQuotationRequest request) {
        String name = ProductQuotation.normalizeName(request.name());
        requireFreeName(name, 0);
        ProductQuotation saved = quotations.saveAndFlush(new ProductQuotation(name));
        audit.record("PRODUCT_QUOTATION_CREATED", "PRODUCT_QUOTATION", saved.getId(), meta("name", saved.getName()));
        return ProductQuotationResponse.from(saved);
    }

    @Transactional
    public ProductQuotationResponse rename(long id, UpdateProductQuotationRequest request) {
        ProductQuotation quotation = load(id);
        requireVersion(quotation, request.version());
        String name = ProductQuotation.normalizeName(request.name());
        if (!name.equals(quotation.getName())) {
            requireFreeName(name, id);
            String from = quotation.getName();
            quotation.rename(name);
            quotations.flush();
            audit.record("PRODUCT_QUOTATION_UPDATED", "PRODUCT_QUOTATION", quotation.getId(),
                    meta("from", from, "to", name));
        }
        return ProductQuotationResponse.from(quotation);
    }

    /**
     * An inactive product quotation stays on the clients that have it, but is no
     * longer offered for new or edited clients.
     */
    @Transactional
    public ProductQuotationResponse changeActive(long id, ChangeProductQuotationActiveRequest request) {
        ProductQuotation quotation = load(id);
        requireVersion(quotation, request.version());
        if (quotation.isActive() != request.active()) {
            quotation.changeActive(request.active());
            quotations.flush();
            audit.record(request.active() ? "PRODUCT_QUOTATION_ACTIVATED" : "PRODUCT_QUOTATION_DEACTIVATED",
                    "PRODUCT_QUOTATION", quotation.getId(), meta("name", quotation.getName()));
        }
        return ProductQuotationResponse.from(quotation);
    }

    private ProductQuotation load(long id) {
        return quotations.findById(id).orElseThrow(() -> ApiException.notFound("product-quotation:not-found",
                "Product quotation " + id + " does not exist."));
    }

    private void requireFreeName(String name, long excludeId) {
        if (quotations.nameTaken(name, excludeId)) {
            throw ApiException.conflict("product-quotation:duplicate-name", "Duplicate name",
                    "A product quotation called " + name + " already exists.");
        }
    }

    private static void requireVersion(ProductQuotation quotation, long expectedVersion) {
        if (quotation.getVersion() != expectedVersion) {
            throw ApiException.staleVersion();
        }
    }
}
