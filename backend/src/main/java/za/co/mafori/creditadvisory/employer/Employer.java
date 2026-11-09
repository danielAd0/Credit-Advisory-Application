package za.co.mafori.creditadvisory.employer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import java.time.Instant;

@Entity
@Table(name = "employers")
public class Employer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "company", nullable = true, length = 100)
    private String company;

    @Column(name = "employer_type", nullable = true, length = 40)
    private String employerType;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "needs_review", nullable = false)
    private Boolean needsReview = false;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Employer() {
        // for JPA
    }

    public Employer(String name, String company, String employerType) {
        this.name = normalizeName(name);
        this.company = blankToNull(company);
        this.employerType = blankToNull(employerType);
        this.active = true; // default to active
    }

    public void update(String name, String company, String employerType) {
        this.name = normalizeName(name);
        this.company = blankToNull(company);
        this.employerType = blankToNull(employerType);
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    public static String normalizeName(String name) {
        return name.strip().replaceAll("\\s+", " ");
    }

    static String blankToNull(String value) {

        return value == null || value.isBlank() ? null : value.strip().replaceAll("\\s+", " ");
    }

    // timestamps
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

    // getters
    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCompany() {
        return company;
    }

    public String getEmployerType() {
        return employerType;
    }

    public Boolean isActive() {
        return active;
    }

    public Boolean needsReview() {
        return Boolean.TRUE.equals(needsReview);
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
