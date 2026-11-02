package za.co.mafori.creditadvisory.product;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.LikePattern;
import za.co.mafori.creditadvisory.common.web.PageResponse;

/** Product catalogue use cases. Role checks live on the controller. */
@Service
public class ProductService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository products;
    private final AuditLogWriter audit;

    public ProductService(ProductRepository products, AuditLogWriter audit) {
        this.products = products;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> search(String search, Boolean active, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by("displayOrder").and(Sort.by("name")).and(Sort.by("id")));
        return PageResponse.of(products.search(LikePattern.contains(search), active, pageable),
                ProductResponse::from);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long id) {
        return ProductResponse.from(load(id));
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        String code = Product.normalizeCode(request.code());
        if (products.existsByCode(code)) {
            throw ApiException.conflict("product:duplicate-code", "Duplicate product code",
                    "A product with code " + code + " already exists.");
        }
        int displayOrder = request.displayOrder() == null ? 0 : request.displayOrder();
        Product saved = products.saveAndFlush(new Product(code, request.name(), request.standardPrice(),
                request.countsTowardQuotation(), request.countsTowardBanked(), displayOrder));
        audit.record("PRODUCT_CREATED", "PRODUCT", saved.getId(),
                meta("code", saved.getCode(), "name", saved.getName(), "standardPrice", saved.getStandardPrice()));
        return ProductResponse.from(saved);
    }

    @Transactional
    public ProductResponse update(long id, UpdateProductRequest request) {
        Product product = load(id);
        requireVersion(product, request.version());
        product.update(request.name(), request.standardPrice(), request.countsTowardQuotation(),
                request.countsTowardBanked(), request.displayOrder());
        products.flush();
        audit.record("PRODUCT_UPDATED", "PRODUCT", product.getId(),
                meta("code", product.getCode(), "name", product.getName(),
                        "standardPrice", product.getStandardPrice(),
                        "countsTowardQuotation", product.isCountsTowardQuotation(),
                        "countsTowardBanked", product.isCountsTowardBanked()));
        return ProductResponse.from(product);
    }

    /**
     * Inactive products stay on old transactions but should not be offered for new
     * ones.
     */
    @Transactional
    public ProductResponse changeActive(long id, ChangeProductActiveRequest request) {
        Product product = load(id);
        requireVersion(product, request.version());
        if (product.isActive() != request.active()) {
            product.changeActive(request.active());
            products.flush();
            audit.record(request.active() ? "PRODUCT_ACTIVATED" : "PRODUCT_DEACTIVATED", "PRODUCT",
                    product.getId(), meta("code", product.getCode()));
        }
        return ProductResponse.from(product);
    }

    private Product load(long id) {
        return products.findById(id)
                .orElseThrow(() -> ApiException.notFound("product:not-found", "Product " + id + " does not exist."));
    }

    private static void requireVersion(Product product, long expectedVersion) {
        if (product.getVersion() != expectedVersion) {
            throw ApiException.staleVersion();
        }
    }
}
