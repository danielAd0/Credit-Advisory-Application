package za.co.mafori.creditadvisory.creditcase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import za.co.mafori.creditadvisory.user.UserAccount;

/**
 * One note on a credit case. Maps to case_notes (V12).
 *
 * <p>
 * Append-only: there are no setters, and the database refuses UPDATE and
 * DELETE. A correction is a new note. The author never changes, so notes keep
 * their original author after a client transfer (FR-15).
 */
@Entity
@Table(name = "case_notes")
public class CaseNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false, updatable = false)
    private CreditCase creditCase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false, updatable = false)
    private UserAccount author;

    @Column(name = "note", nullable = false, updatable = false, length = 4000)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA */
    protected CaseNote() {
    }

    /** The note text must already be validated. */
    public CaseNote(CreditCase creditCase, UserAccount author, String note) {
        this.creditCase = creditCase;
        this.author = author;
        this.note = note.strip();
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    /** Lazy: only call inside a transaction. */
    public CreditCase getCreditCase() {
        return creditCase;
    }

    /** Lazy: only call inside a transaction. */
    public UserAccount getAuthor() {
        return author;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
