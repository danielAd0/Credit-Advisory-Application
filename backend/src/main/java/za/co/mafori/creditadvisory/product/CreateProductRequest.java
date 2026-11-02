package za.co.mafori.creditadvisory.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of POST /api/products. The code is trimmed, upper-cased and
 * spaces/dashes
 * become "_" (e.g. "mafori card" -> "MAFORI_CARD"). standardPrice may be null
 * when the price is negotiated per client.
 */
public record CreateProductRequest(
        @NotBlank @Pattern(regexp = "^\\s*[A-Za-z][A-Za-z0-9_ -]{1,39}\\s*$", message = "must be 2-40 letters, digits, spaces, '_' or '-', starting with a letter") String code,
        @NotBlank @Size(max = 100) String name,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal standardPrice,
        @NotNull Boolean countsTowardQuotation,
        @NotNull Boolean countsTowardBanked,
        @Min(0) Integer displayOrder) {
}
