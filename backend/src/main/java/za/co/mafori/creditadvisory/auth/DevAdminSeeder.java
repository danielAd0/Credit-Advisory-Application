package za.co.mafori.creditadvisory.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.user.Role;
import za.co.mafori.creditadvisory.user.RoleName;
import za.co.mafori.creditadvisory.user.RoleRepository;
import za.co.mafori.creditadvisory.user.UserAccount;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * Local development only: creates an ADMIN user from
 * application-local.properties so you can
 * sign in. Never runs outside the "local" profile. Does nothing if the user
 * already exists.
 */
@Component
@Profile("local")
public class DevAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAdminSeeder.class);

    private final UserAccountRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public DevAdminSeeder(UserAccountRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
            @Value("${app.dev.admin-email:}") String email, @Value("${app.dev.admin-password:}") String password) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            log.info("No local admin configured (app.dev.admin-email / app.dev.admin-password) - skipping");
            return;
        }
        if (users.existsByEmail(email)) {
            return;
        }
        Role admin = roles.findByName(RoleName.ADMIN)
                .orElseThrow(() -> new IllegalStateException("ADMIN role missing - has V5 run?"));
        UserAccount user = new UserAccount(email, passwordEncoder.encode(password));
        user.addRole(admin);
        users.save(user);
        log.info("Created local admin user {}", user.getEmail());
    }
}
