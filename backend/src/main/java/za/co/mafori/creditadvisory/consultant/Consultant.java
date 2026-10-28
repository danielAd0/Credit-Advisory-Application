package za.co.mafori.creditadvisory.consultant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;

/**
 * A consultant clients can be assigned to. Maps to the consultants table (V1).
 */
@Entity
@Table(name = "consultants")
public class Consultant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consultant_code", nullable = false, length = 32)
    private String consultantCode;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ConsultantStatus status;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. NOt for application code - use the public constructor. */
    protected Consultant() {
    }

    /** A new consultant always starts ACTIVE */
    public Consultant(String consultantCode, String fullName) {
        this.consultantCode = normalizeCode(consultantCode);
        this.fullName = fullName;
        this.status = ConsultantStatus.ACTIVE;
    }

    // -------- behaviour --------
    public void rename(String fullName) {
        this.fullName = fullName.strip();
    }

    /** Changes status and records when. Does nothing if the status is unchanged. */
    public void changeStatus(ConsultantStatus newStatus) {
        if (newStatus == this.status) {
            return;
        }
        this.status = newStatus;
        this.statusChangedAt = Instant.now();
    }

    /** "c004" becomes "C004" - the database only accepts upper-case codes */
    public static String normalizeCode(String code) {
        return code.strip().toUpperCase(Locale.ROOT);
    }

    // -------- timestamps --------

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.statusChangedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // -------- getters (no setters - change state through the methods above)
    // --------

    public Long getId() {
        return id;
    }

    public String getConsultantCode() {
        return consultantCode;
    }

    public String getFullName() {
        return fullName;
    }

    public ConsultantStatus getStatus() {
        return status;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
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
