package za.co.mafori.creditadvisory.user;

import jakarta.validation.constraints.NotNull;

/** Body of PATCH /api/users/{id}/consultant. consultantId null = unlink. */
public record LinkConsultantRequest(
        Long consultantId,
        @NotNull Long version) {
}
