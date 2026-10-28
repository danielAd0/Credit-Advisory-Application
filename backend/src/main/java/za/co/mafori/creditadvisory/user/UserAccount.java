package za.co.mafori.creditadvisory.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import za.co.mafori.creditadvisory.consultant.Consultant;;

/**
 * A login account, Maps to the users table (V1).
 *
 * Called UserAccount, not User, to avoid clashing with Springs Security's User
 * class.
 */
@Entity
@Table(name = "users")
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Mirrors email (lower-cased). The table requires a unique username; we sign in
     * by email.
     */
    @Column(name = "username", nullable = false, length = 100)
    private String username;

    /**
     * Stored lower-cased and trimmed, so it matches the case-insensitive unique
     * index.
     */
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    /** BCrypt hash, never the plain password. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Set only for users who are consultants. One login per consultant. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_consultant_id")
    private Consultant consultant;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /**
     * Roles are few and needed on every sign-in, so they are loaded with the user.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA */
    protected UserAccount() {
    }

    /**
     * @param email        the sign in email; trimmed and lower-cased
     * @param passwordHash an already-hashed password (BCrypt), never plain text
     */
    public UserAccount(String email, String passwordHash) {
        this.email = normalizeEmail(email);
        this.username = this.email;
        this.passwordHash = passwordHash;
        this.enabled = true;
    }

    // -------- sign-in rules --------

    /** True if the account is currently locked out. */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * Whether this account may sign in right now: enabled, not locked, and - if it
     * belongs to
     * a consultant - that consultant is ACTIVE (FR-18)
     */
    public boolean canSignIn(Instant now) {
        if (!enabled || isLocked(now)) {
            return false;
        }
        return consultant == null || consultant.getStatus().isActive();
    }

    /**
     * Counts a failed sign-in. After maxAttempts failures in a row the account
     * locks for
     * lockDuration and the counter starts again
     */
    public void recordFailedSignIn(Instant now, int maxAttempts, Duration lockDuration) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            lockedUntil = now.plus(lockDuration);
            failedLoginAttempts = 0;
        }
    }

    /** Clears failures and any lock, and records the sign-in time. */
    public void recordSuccessfulSignIn(Instant now) {
        failedLoginAttempts = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    // -------- account management --------

    public void changePasswordHash(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public void addRole(Role role) {
        roles.add(role);
    }

    public void removeRole(Role role) {
        roles.remove(role);
    }

    public boolean hasRole(RoleName roleName) {
        return roles.stream().anyMatch(r -> r.getName() == roleName);
    }

    public void linkConsultant(Consultant consultant) {
        this.consultant = consultant;
    }

    public void unlinkConsultant(Consultant consultant) {
        this.consultant = null;
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
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

    // ---------- getters ----------

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** May be null. Lazy: only call inside a transaction. */
    public Consultant getConsultant() {
        return consultant;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    /** read-only view; change roles with addRole/removeRole */
    public Set<Role> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public Set<RoleName> getRoleNames() {
        return roles.stream().map(Role::getName).collect(Collectors.toUnmodifiableSet());
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
