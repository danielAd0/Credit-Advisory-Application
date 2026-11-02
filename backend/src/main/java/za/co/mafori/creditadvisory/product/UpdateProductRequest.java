package za.co.mafori.creditadvisory.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of PATCH /api/products/{id}. Replaces every editable field; the code
 * cannot be changed. standardPrice null = no standard price.
 */
public record UpdateProductRequest(
        @NotBlank @Size(max = 100) String name,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal standardPrice,
        @NotNull Boolean countsTowardQuotation,
        @NotNull Boolean countsTowardBanked,
        @NotNull @Min(0) Integer displayOrder,
        @NotNull Long version) {
}
