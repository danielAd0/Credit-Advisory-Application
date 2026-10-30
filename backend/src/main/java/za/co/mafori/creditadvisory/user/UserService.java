package za.co.mafori.creditadvisory.user;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.LikePattern;
import za.co.mafori.creditadvisory.common.web.PageResponse;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.consultant.ConsultantRepository;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;

/** User account management (admin) and changing your own password. */
@Service
public class UserService {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserAccountRepository users;
    private final RoleRepository roles;
    private final ConsultantRepository consultants;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock = Clock.systemUTC();

    public UserService(UserAccountRepository users, RoleRepository roles,
            ConsultantRepository consultants, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.roles = roles;
        this.consultants = consultants;
        this.passwordEncoder = passwordEncoder;
    }

    // ---------- queries ----------

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(String search, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by("email"));
        Instant now = Instant.now(clock);
        return PageResponse.of(users.search(LikePattern.contains(search), pageable),
                user -> UserResponse.from(user, now));
    }

    @Transactional(readOnly = true)
    public UserResponse get(long id) {
        return UserResponse.from(load(id), Instant.now(clock));
    }

    // ---------- admin actions ----------

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String email = UserAccount.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("user:duplicate-email", "Email already in use",
                    "A user with this email already exists.");
        }
        PasswordPolicy.check(request.temporaryPassword(), email);

        Consultant consultant = request.consultantId() == null
                ? null
                : loadLinkableConsultant(request.consultantId(), null);
        requireConsultantLinkForRole(request.roles(), consultant);

        UserAccount user = new UserAccount(email, passwordEncoder.encode(request.temporaryPassword()));
        for (RoleName name : request.roles()) {
            user.addRole(role(name));
        }
        if (consultant != null) {
            user.linkConsultant(consultant);
        }
        UserAccount saved = users.saveAndFlush(user);
        return UserResponse.from(saved, Instant.now(clock));
    }

    /** Replaces the user's roles with exactly the requested set. */
    @Transactional
    public UserResponse changeRoles(long id, ChangeRolesRequest request) {
        UserAccount user = load(id);
        requireVersion(user, request.version());
        Set<RoleName> wanted = request.roles();

        requireConsultantLinkForRole(wanted, user.getConsultant());
        if (user.isEnabled() && user.hasRole(RoleName.ADMIN) && !wanted.contains(RoleName.ADMIN)) {
            requireAnotherAdmin();
        }

        // Copy first: removing while looping over the live set would throw.
        for (Role current : List.copyOf(user.getRoles())) {
            if (!wanted.contains(current.getName())) {
                user.removeRole(current);
            }
        }
        for (RoleName name : wanted) {
            if (!user.hasRole(name)) {
                user.addRole(role(name));
            }
        }
        users.flush();
        return UserResponse.from(user, Instant.now(clock));
    }

    /** Links the user to a consultant, or unlinks with consultantId = null. */
    @Transactional
    public UserResponse changeConsultant(long id, LinkConsultantRequest request) {
        UserAccount user = load(id);
        requireVersion(user, request.version());

        if (request.consultantId() == null) {
            requireConsultantLinkForRole(user.getRoleNames(), null);
            user.unlinkConsultant();
        } else {
            user.linkConsultant(loadLinkableConsultant(request.consultantId(), user.getId()));
        }
        users.flush();
        return UserResponse.from(user, Instant.now(clock));
    }

    /**
     * Disabling ends the user's session on their next request
     * (AccountStillActiveFilter).
     */
    @Transactional
    public UserResponse changeEnabled(long id, ChangeEnabledRequest request) {
        UserAccount user = load(id);
        requireVersion(user, request.version());

        if (request.enabled()) {
            user.enable();
        } else {
            if (user.isEnabled() && user.hasRole(RoleName.ADMIN)) {
                requireAnotherAdmin();
            }
            user.disable();
        }
        users.flush();
        return UserResponse.from(user, Instant.now(clock));
    }

    /**
     * Sets a temporary password and clears any lockout (FR-01 recovery, until email
     * exists).
     */
    @Transactional
    public UserResponse resetPassword(long id, ResetPasswordRequest request) {
        UserAccount user = load(id);
        PasswordPolicy.check(request.temporaryPassword(), user.getEmail());
        user.changePasswordHash(passwordEncoder.encode(request.temporaryPassword()));
        user.unlock();
        users.flush();
        return UserResponse.from(user, Instant.now(clock));
    }

    @Transactional
    public UserResponse unlock(long id) {
        UserAccount user = load(id);
        user.unlock();
        users.flush();
        return UserResponse.from(user, Instant.now(clock));
    }

    // ---------- self service ----------

    @Transactional
    public void changeOwnPassword(long userId, String currentPassword, String newPassword) {
        UserAccount user = load(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "auth:wrong-current-password",
                    "Current password is wrong", "The current password you entered is not correct.");
        }
        PasswordPolicy.check(newPassword, user.getEmail());
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation:weak-password",
                    "Password not allowed", "The new password must be different from the current one.");
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
    }

    // ---------- rules ----------

    /** Users with the CONSULTANT role must be linked to a consultant record. */
    private static void requireConsultantLinkForRole(Set<RoleName> roleNames, Consultant consultant) {
        if (roleNames.contains(RoleName.CONSULTANT) && consultant == null) {
            throw ApiException.unprocessable("user:consultant-role-needs-link", "Consultant link required",
                    "Users with the CONSULTANT role must be linked to a consultant record.");
        }
    }

    /**
     * The consultant must exist, be ACTIVE, and not already have a login (other
     * than ownerUserId).
     */
    private Consultant loadLinkableConsultant(long consultantId, Long ownerUserId) {
        Consultant consultant = consultants.findById(consultantId)
                .orElseThrow(() -> ApiException.notFound("consultant:not-found",
                        "Consultant " + consultantId + " does not exist."));
        if (consultant.getStatus() != ConsultantStatus.ACTIVE) {
            throw ApiException.unprocessable("user:consultant-not-active", "Consultant not active",
                    "Only ACTIVE consultants can be linked to a login.");
        }
        boolean takenBySomeoneElse = users.findByConsultantId(consultantId)
                .filter(other -> !Objects.equals(other.getId(), ownerUserId))
                .isPresent();
        if (takenBySomeoneElse) {
            throw ApiException.conflict("user:consultant-already-linked", "Consultant already has a login",
                    "This consultant is already linked to another user.");
        }
        return consultant;
    }

    /**
     * Stops the last active administrator from being disabled or losing the ADMIN
     * role.
     */
    private void requireAnotherAdmin() {
        if (users.countEnabledWithRole(RoleName.ADMIN) <= 1) {
            throw ApiException.conflict("user:last-admin", "Last administrator",
                    "This is the only active administrator. Give another user the ADMIN role first.");
        }
    }

    private Role role(RoleName name) {
        return roles.findByName(name)
                .orElseThrow(() -> new IllegalStateException("Role " + name + " missing - has V5 run?"));
    }

    private UserAccount load(long id) {
        return users.findById(id)
                .orElseThrow(() -> ApiException.notFound("user:not-found", "User " + id + " does not exist."));
    }

    private static void requireVersion(UserAccount user, long expectedVersion) {
        if (user.getVersion() != expectedVersion) {
            throw ApiException.staleVersion();
        }
    }
}
