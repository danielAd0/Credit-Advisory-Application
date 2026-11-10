package za.co.mafori.creditadvisory.productquotation;

import jakarta.validation.constraints.NotNull;

/** Body of PATCH /api/product-quotations/{id}/active. */
public record ChangeProductQuotationActiveRequest(
        @NotNull Boolean active,
        @NotNull Long version) {
}
