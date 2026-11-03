package za.co.mafori.creditadvisory.creditcase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import za.co.mafori.creditadvisory.client.Client;
import za.co.mafori.creditadvisory.user.UserAccount;

/**
 * A credit advisory case. Maps to the credit_cases table (V12).
 *
 * <p>
 * Belongs to a client, not to a consultant: whoever the client is assigned to
 * sees the case, so a transfer keeps every case (FR-15). Never deleted - it is
 * closed instead.
 */
@Entity
@Table(name = "credit_cases")
public class CreditCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false, updatable = false)
    private Client client;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CaseStatus status;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    /** Set exactly while the case is CLOSED. */
    @Column(name = "closed_at")
    private Instant closedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false, updatable = false)
    private UserAccount createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA */
    protected CreditCase() {
    }

    /** A new OPEN case. The title must already be validated. */
    public CreditCase(Client client, String title, UserAccount createdBy) {
        this.client = client;
        this.title = title.strip();
        this.createdBy = createdBy;
        this.status = CaseStatus.OPEN;
        this.openedAt = Instant.now();
    }

    // -------- behaviour --------

    public void retitle(String newTitle) {
        this.title = newTitle.strip();
    }

    /**
     * Moves the case to another status. Closing records the time; reopening clears
     * it (the audit log keeps the history).
     */
    public void changeStatus(CaseStatus newStatus) {
        if (newStatus == status) {
            return;
        }
        this.closedAt = newStatus.isClosed() ? Instant.now() : null;
        this.status = newStatus;
    }

    public boolean isClosed() {
        return status.isClosed();
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

    /** Lazy: only call inside a transaction. */
    public Client getClient() {
        return client;
    }

    public String getTitle() {
        return title;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    /** Lazy: only call inside a transaction. */
    public UserAccount getCreatedBy() {
        return createdBy;
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
