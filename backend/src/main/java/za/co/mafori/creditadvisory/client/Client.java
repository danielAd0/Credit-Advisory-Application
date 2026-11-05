package za.co.mafori.creditadvisory.client;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import za.co.mafori.creditadvisory.consultant.Consultant;

/**
 * A client. Maps to the clients table (V2).
 * The SA ID number is never held here in plain text. The caller passes the
 * three protected
 * forms produced by IdNumberProtector: the lookup hash, the ciphertext and the
 * last four digits
 */
@Entity
@Table(name = "clients")
public class Client {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Keyed HMAC-SHA256 of the ID number (32 bytes). Unique: catches duplicate
     * clients.
     */
    @Column(name = "id_number_hash", nullable = false, updatable = false)
    private byte[] idNumberHash;

    /**
     * AES-GCM ciphertext of the ID number. Decrypt only for authorised, audited
     * views
     */
    @Column(name = "id_number_encrypted", nullable = false)
    private byte[] idNumberEncrypted;

    @Column(name = "id_number_last4", nullable = false, length = 4, updatable = false)
    private String idNumberLast4;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", length = 254)
    private String email;

    /** International format, e.g. +2781234567 */
    @Column(name = "phone", length = 20)
    private String phone;

    /**
     * Null means unassigned. Change only through methods that also write assignment
     * history.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_consultant_id")
    private Consultant currentConsultant;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA */
    protected Client() {
    }

    /**
     * @param idNumberHash      lookup hash from IdNumberProtector.lookupHash
     * @param idNumberEncrypted ciphertext from IdNumberProtector.encrypt
     * @param idNumberLast4     last four digits of the ID Number
     */
    public Client(byte[] idNumberHash, byte[] idNumberEncrypted, String idNumberLast4, String firstName,
            String lastName) {
        this.idNumberHash = idNumberHash.clone();
        this.idNumberEncrypted = idNumberEncrypted.clone();
        this.idNumberLast4 = idNumberLast4;
        this.firstName = firstName.strip();
        this.lastName = lastName.strip();
    }

    // -------- behaviour --------
    public void rename(String newFirstName, String newLastName) {
        this.firstName = newFirstName.strip();
        this.lastName = newLastName.strip();
    }

    /**
     * Null clears a value. Email and phone must already be validated and
     * normalised.
     */
    public void updateContactDetails(String newEmail, String newPhone) {
        this.email = newEmail;
        this.phone = newPhone;
    }

    /**
     * Moves the client to another consultant (or to nobody, with null).
     *
     * Only call this from code that writes a ClientAssignmentHistory row in the
     * same
     * transaction - client creation and transfers. Never call it on its own
     */
    public void assignTo(Consultant consultant) {
        this.currentConsultant = consultant;
    }

    public boolean isAssignedTo(Long consultantId) {
        return currentConsultant != null && currentConsultant.getId().equals(consultantId);
    }

    /**
     * Re-encryption with a newer key (key rotation). The hash and last four never
     * change.
     */
    public void replaceEncryptedIdNumber(byte[] newCiphertext) {
        this.idNumberEncrypted = newCiphertext.clone();
    }

    /** Masked for lists and broad views: ********1234 */
    public String getIdNumberMasked() {
        return "*********" + idNumberLast4;
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

    public byte[] getIdNumberHash() {
        return idNumberHash.clone();
    }

    public byte[] getIdNumberEncrypted() {
        return idNumberEncrypted.clone();
    }

    public String getIdNumberLast4() {
        return idNumberLast4;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    /** May be null. Lazy: only call inside a transaction. */
    public Consultant getCurrentConsultant() {
        return currentConsultant;
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
