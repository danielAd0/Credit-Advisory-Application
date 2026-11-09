package za.co.mafori.creditadvisory.employer;

import jakarta.validation.constraints.NotNull;

public record EmployerReviewRequest(@NotNull Long version) {
}
