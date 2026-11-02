package za.co.mafori.creditadvisory.employer;

import jakarta.validation.constraints.NotNull;

public record ChangeEmployerActiveRequest(@NotNull Boolean active, @NotNull Long version) {
}
