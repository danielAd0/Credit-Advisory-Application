package za.co.mafori.creditadvisory.client;

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
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.employer.Employer;
import za.co.mafori.creditadvisory.productquotation.ProductQuotation;

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
     * clients. Changes only through correctIdNumber.
     */
    @Column(name = "id_number_hash", nullable = false)
    private byte[] idNumberHash;

    /**
     * AES-GCM ciphertext of the ID number. Decrypt only for authorised, audited
     * views
     */
    @Column(name = "id_number_encrypted", nullable = false)
    private byte[] idNumberEncrypted;

    /** Changes only through correctIdNumber. */
    @Column(name = "id_number_last4", nullable = false, length = 4)
    private String idNumberLast4;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", length = 254)
    private String email;

    /** The cellphone. International format, e.g. +27821234567 */
    @Column(name = "phone", length = 20)
    private String phone;

    /** Same format as phone (V20). */
    @Column(name = "work_phone", length = 20)
    private String workPhone;

    /** Same format as phone (V20). */
    @Column(name = "home_phone", length = 20)
    private String homePhone;

    /** Null when unknown, for example a debit-order client from the launch list. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employer_id")
    private Employer employer;

    /** Picked from the list admins manage (V20). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_quotation_id")
    private ProductQuotation productQuotation;

    /** The old file number: digits only, kept as text so a leading 0 survives. */
    @Column(name = "file_number", length = 20)
    private String fileNumber;

    /** The old file ID: digits only, kept as text. A different thing from the file number. */
    @Column(name = "file_id", length = 20)
    private String fileId;

    /** Null means not recorded. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

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
     * Null clears a value. Every value must already be validated and normalised
     * (ClientService).
     */
    public void updateContactDetails(String newEmail, String newPhone, String newWorkPhone, String newHomePhone) {
        this.email = newEmail;
        this.phone = newPhone;
        this.workPhone = newWorkPhone;
        this.homePhone = newHomePhone;
    }

    /**
     * Employer, product quotation, the old file references and the payment method.
     * Null clears a value. ClientService checks that a newly chosen employer or
     * product quotation is active.
     */
    public void updateWorkDetails(Employer newEmployer, ProductQuotation newProductQuotation, String newFileNumber,
            String newFileId, PaymentMethod newPaymentMethod) {
        this.employer = newEmployer;
        this.productQuotation = newProductQuotation;
        this.fileNumber = newFileNumber;
        this.fileId = newFileId;
        this.paymentMethod = newPaymentMethod;
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
     * Re-encryption with a newer key (key rotation). The number itself, and so the
     * hash and last four, stay the same. To change the number, use
     * correctIdNumber.
     */
    public void replaceEncryptedIdNumber(byte[] newCiphertext) {
        this.idNumberEncrypted = newCiphertext.clone();
    }

    /**
     * Replaces a wrongly captured ID number. All three protected forms change
     * together, so they always describe the same number.
     *
     * Only call this from ClientService.correctIdNumber, which checks who is
     * asking, refuses duplicates and audits the correction. Never call it on its
     * own.
     */
    public void correctIdNumber(byte[] newIdNumberHash, byte[] newIdNumberEncrypted, String newIdNumberLast4) {
        this.idNumberHash = newIdNumberHash.clone();
        this.idNumberEncrypted = newIdNumberEncrypted.clone();
        this.idNumberLast4 = newIdNumberLast4;
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

    public String getWorkPhone() {
        return workPhone;
    }

    public String getHomePhone() {
        return homePhone;
    }

    /** May be null. Lazy: only call inside a transaction. */
    public Employer getEmployer() {
        return employer;
    }

    /** May be null. Lazy: only call inside a transaction. */
    public ProductQuotation getProductQuotation() {
        return productQuotation;
    }

    public String getFileNumber() {
        return fileNumber;
    }

    public String getFileId() {
        return fileId;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
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
