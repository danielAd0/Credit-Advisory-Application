package za.co.mafori.creditadvisory.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/**
 * A product Mafori sells (Combo, Insurance, Mafori Card, ...). Maps to the
 * products table (V6).
 *
 * <p>
 * The code never changes after creation: the weekly-register import maps
 * spreadsheet columns to product codes. Products are never deleted (transaction
 * lines reference them); they are deactivated instead.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Null when the price is negotiated per client. */
    @Column(name = "standard_price", precision = 12, scale = 2)
    private BigDecimal standardPrice;

    @Column(name = "counts_toward_quotation", nullable = false)
    private boolean countsTowardQuotation;

    @Column(name = "counts_toward_banked", nullable = false)
    private boolean countsTowardBanked;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA */
    protected Product() {
    }

    /** A new product always starts active. */
    public Product(String code, String name, BigDecimal standardPrice, boolean countsTowardQuotation,
            boolean countsTowardBanked, int displayOrder) {
        this.code = normalizeCode(code);
        this.name = name.strip();
        this.standardPrice = standardPrice;
        this.countsTowardQuotation = countsTowardQuotation;
        this.countsTowardBanked = countsTowardBanked;
        this.displayOrder = displayOrder;
        this.active = true;
    }

    // -------- behaviour --------

    public void update(String name, BigDecimal standardPrice, boolean countsTowardQuotation,
            boolean countsTowardBanked, int displayOrder) {
        this.name = name.strip();
        this.standardPrice = standardPrice;
        this.countsTowardQuotation = countsTowardQuotation;
        this.countsTowardBanked = countsTowardBanked;
        this.displayOrder = displayOrder;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    /**
     * " mafori card " becomes "MAFORI_CARD" - the database only accepts upper-case
     * codes.
     */
    public static String normalizeCode(String code) {
        return code.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
    }

    // -------- timestamps --------

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // -------- getters --------

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getStandardPrice() {
        return standardPrice;
    }

    public boolean isCountsTowardQuotation() {
        return countsTowardQuotation;
    }

    public boolean isCountsTowardBanked() {
        return countsTowardBanked;
    }

    public boolean isActive() {
        return active;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
