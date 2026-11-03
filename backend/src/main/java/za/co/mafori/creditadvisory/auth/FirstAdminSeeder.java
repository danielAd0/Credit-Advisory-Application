package za.co.mafori.creditadvisory.auth;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.user.PasswordPolicy;
import za.co.mafori.creditadvisory.user.Role;
import za.co.mafori.creditadvisory.user.RoleName;
import za.co.mafori.creditadvisory.user.RoleRepository;
import za.co.mafori.creditadvisory.user.UserAccount;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * Creates the FIRST administrator of a new server, so somebody can sign in.
 *
 * <p>
 * Only does something when app.bootstrap.admin-email and -password are set (in
 * production: FIRST_ADMIN_EMAIL / FIRST_ADMIN_PASSWORD) AND there is no enabled
 * administrator yet. The password is temporary: the admin must choose their own
 * on first sign-in (must_change_password, V11). IT removes the two variables
 * once the admin has signed in.
 *
 * <p>
 * Development uses DevAdminSeeder instead; there these properties are not set.
 */
@Component
public class FirstAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstAdminSeeder.class);

    private final UserAccountRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogWriter audit;
    private final String email;
    private final String password;

    public FirstAdminSeeder(UserAccountRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
            AuditLogWriter audit,
            @Value("${app.bootstrap.admin-email:}") String email,
            @Value("${app.bootstrap.admin-password:}") String password) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createFirstAdminIfNeeded();
    }

    /** @return true if an administrator was created */
    @Transactional
    public boolean createFirstAdminIfNeeded() {
        if (email.isBlank() || password.isBlank()) {
            return false;
        }
        if (users.countEnabledWithRole(RoleName.ADMIN) > 0) {
            log.info("An administrator already exists: ignoring the first-admin settings."
                    + " Remove FIRST_ADMIN_EMAIL and FIRST_ADMIN_PASSWORD from the server.");
            return false;
        }
        String normalizedEmail = UserAccount.normalizeEmail(email);
        if (users.existsByEmail(normalizedEmail)) {
            log.warn("First-admin email is already used by another account: no administrator created.");
            return false;
        }
        PasswordPolicy.check(password, normalizedEmail);

        Role admin = roles.findByName(RoleName.ADMIN)
                .orElseThrow(() -> new IllegalStateException("ADMIN role missing - has V5 run?"));
        UserAccount user = new UserAccount(normalizedEmail, passwordEncoder.encode(password));
        user.addRole(admin);
        user.requirePasswordChange();
        users.saveAndFlush(user);

        // Never the password.
        audit.record("FIRST_ADMIN_CREATED", "USER", user.getId(), meta("email", user.getEmail()));
        log.info("Created the first administrator {}. They must choose a new password when they sign in.",
                user.getEmail());
        return true;
    }
}
