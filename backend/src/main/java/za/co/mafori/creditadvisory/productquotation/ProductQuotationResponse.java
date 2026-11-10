package za.co.mafori.creditadvisory.productquotation;

import java.time.Instant;

/** The ProductQuotation object in the API. */
public record ProductQuotationResponse(long id, String name, boolean active, long version, Instant createdAt,
        Instant updatedAt) {

    public static ProductQuotationResponse from(ProductQuotation q) {
        return new ProductQuotationResponse(q.getId(), q.getName(), q.isActive(), q.getVersion(), q.getCreatedAt(),
                q.getUpdatedAt());
    }
}
