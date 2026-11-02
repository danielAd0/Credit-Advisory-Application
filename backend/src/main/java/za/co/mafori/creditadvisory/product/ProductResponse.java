package za.co.mafori.creditadvisory.product;

import java.math.BigDecimal;
import java.time.Instant;

/** The Product object in the API. */
public record ProductResponse(long id, String code, String name, BigDecimal standardPrice,
        boolean countsTowardQuotation, boolean countsTowardBanked, boolean active, int displayOrder,
        long version, Instant createdAt, Instant updatedAt) {

    public static ProductResponse from(Product p) {
        return new ProductResponse(p.getId(), p.getCode(), p.getName(), p.getStandardPrice(),
                p.isCountsTowardQuotation(), p.isCountsTowardBanked(), p.isActive(), p.getDisplayOrder(),
                p.getVersion(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
