package za.co.mafori.creditadvisory.productquotation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of PATCH /api/product-quotations/{id}: a new name. Clients that already
 * have this product quotation show the new name too.
 */
public record UpdateProductQuotationRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull Long version) {
}
