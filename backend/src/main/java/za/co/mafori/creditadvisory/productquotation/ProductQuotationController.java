package za.co.mafori.creditadvisory.productquotation;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.common.web.PageResponse;

/**
 * The product quotation list (docs/api-admin.md section 4). Everyone signed in
 * can read
 * it, because consultants pick from it when they add or edit a client; only
 * ADMIN can change it.
 */
@RestController
@RequestMapping("/api/product-quotations")
public class ProductQuotationController {

    private final ProductQuotationService service;

    public ProductQuotationController(ProductQuotationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ProductQuotationResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return service.search(search, active, page, size);
    }

    @GetMapping("/{id}")
    public ProductQuotationResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductQuotationResponse> create(@Valid @RequestBody ProductQuotationRequest request) {
        ProductQuotationResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/product-quotations/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductQuotationResponse rename(@PathVariable long id,
            @Valid @RequestBody UpdateProductQuotationRequest request) {
        return service.rename(id, request);
    }

    @PatchMapping("/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductQuotationResponse changeActive(@PathVariable long id,
            @Valid @RequestBody ChangeProductQuotationActiveRequest request) {
        return service.changeActive(id, request);
    }
}
