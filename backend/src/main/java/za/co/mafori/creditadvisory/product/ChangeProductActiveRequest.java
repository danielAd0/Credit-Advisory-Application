package za.co.mafori.creditadvisory.product;

import jakarta.validation.constraints.NotNull;

public record ChangeProductActiveRequest(
        @NotNull Boolean active,
        @NotNull Long version) {
}
