package za.co.mafori.creditadvisory.productquotation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/product-quotations. Extra spaces are removed. */
public record ProductQuotationRequest(@NotBlank @Size(max = 100) String name) {
}
