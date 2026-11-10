package za.co.mafori.creditadvisory.productquotation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * One product quotation, e.g. "Mafori Advance". Maps to the product_quotations
 * table (V20).
 *
 * <p>
 * Never deleted, because clients point at it: an admin deactivates it instead,
 * and it is then no longer offered for new or edited clients.
 */
@Entity
@Table(name = "product_quotations")
public class ProductQuotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA */
    protected ProductQuotation() {
    }

    /** A new product quotation always starts active. */
    public ProductQuotation(String name) {
        this.name = normalizeName(name);
        this.active = true;
    }

    // -------- behaviour --------

    public void rename(String newName) {
        this.name = normalizeName(newName);
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    /** " Mafori Advance " becomes "Mafori Advance". */
    public static String normalizeName(String name) {
        return name.strip().replaceAll("\\s+", " ");
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

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
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
