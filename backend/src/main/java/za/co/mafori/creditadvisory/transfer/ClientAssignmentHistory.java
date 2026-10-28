package za.co.mafori.creditadvisory.transfer;

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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import za.co.mafori.creditadvisory.client.Client;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.user.UserAccount;

@Entity
@Immutable
@Table(name = "client_assignment_history")
public class ClientAssignmentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_consultant_id")
    private Consultant fromConsultant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_consultant_id")
    private Consultant toConsultant;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 16)
    private AssignmentChangeType changeType;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /** Shared by every row of one bulk transfer. Null otherwise. */
    @Column(name = "transfer_batch_id")
    private UUID transferBatchId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "changed_by_user_id", nullable = false)
    private UserAccount changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    /** Required by JPA */
    protected ClientAssignmentHistory() {
    }

    private ClientAssignmentHistory(Client client, Consultant from, Consultant to, AssignmentChangeType changeType,
            String reason, UUID transferBatchId, UserAccount changedBy) {
        this.client = client;
        this.fromConsultant = from;
        this.toConsultant = to;
        this.changeType = changeType;
        this.reason = reason.strip();
        this.transferBatchId = transferBatchId;
        this.changedBy = changedBy;
    }

    /** A new client created already assigned to a consultant */
    public static ClientAssignmentHistory clientCreated(Client client, Consultant to, UserAccount changedBy) {
        return new ClientAssignmentHistory(client, null, to, AssignmentChangeType.MANUAL, "Client created", null,
                changedBy);
    }

    /**
     * A transfer. Pass a batch id when several clients move together
     * (BULK_TRANSFER),
     * or null for a single client (TRANSFER)
     */
    public static ClientAssignmentHistory transfer(Client client, Consultant from, Consultant to, String reason,
            UUID batchID, UserAccount changedBy) {
        AssignmentChangeType type = batchID == null ? AssignmentChangeType.TRANSFER
                : AssignmentChangeType.BULK_TRANSFER;
        return new ClientAssignmentHistory(client, from, to, type, reason, batchID, changedBy);
    }

    @PrePersist
    void onCreate() {
        this.changedAt = Instant.now();
    }

    // -------- getters --------

    public Long getId() {
        return id;
    }

    public Client getClient() {
        return client;
    }

    public Consultant getFromConsultant() {
        return fromConsultant;
    }

    public Consultant getToConsultant() {
        return toConsultant;
    }

    public AssignmentChangeType getChangeType() {
        return changeType;
    }

    public String getReason() {
        return reason;
    }

    public UUID getTransferBatchId() {
        return transferBatchId;
    }

    public UserAccount getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
