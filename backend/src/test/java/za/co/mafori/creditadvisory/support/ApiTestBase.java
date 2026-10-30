package za.co.mafori.creditadvisory.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadLocalRandom;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.consultant.ConsultantRepository;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.user.RoleName;
import za.co.mafori.creditadvisory.user.RoleRepository;
import za.co.mafori.creditadvisory.user.UserAccount;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * Starts the whole backend on a random port against the credit_advisory_test
 * database.
 * Before every test the database is wiped and rebuilt by Flyway, so tests never
 * depend on each
 * other. All data is fake.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class ApiTestBase {

    protected static final String PASSWORD = "Test-Password-2026";
    protected static final String ADMIN_EMAIL = "admin@test.local";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private Flyway flyway;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private ConsultantRepository consultants;

    @Autowired
    private PasswordEncoder passwordEncoder;

    protected long adminId;

    private int clientCounter;

    @BeforeEach
    protected void resetDatabase() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        if (database == null || !database.endsWith("_test")) {
            throw new IllegalStateException("Refusing to wipe database '" + database
                    + "'. Tests only run against a database whose name ends in _test.");
        }
        flyway.clean();
        flyway.migrate();
        adminId = createUser(ADMIN_EMAIL, null, RoleName.ADMIN);
    }

    /** A new browser with no cookies (not signed in). */
    protected ApiClient browser() {
        return new ApiClient("http://localhost:" + port);
    }

    /**
     * A new browser signed in as this user (every test user has the password
     * PASSWORD).
     */
    protected ApiClient signIn(String email) {
        ApiClient api = browser();
        ApiClient.Response response = api.signIn(email, PASSWORD);
        assertThat(response.status()).as("sign-in for %s: %s", email, response.body()).isEqualTo(200);
        return api;
    }

    protected long createUser(String email, Long consultantId, RoleName... roleNames) {
        UserAccount user = new UserAccount(email, passwordEncoder.encode(PASSWORD));
        for (RoleName name : roleNames) {
            user.addRole(roles.findByName(name).orElseThrow());
        }
        if (consultantId != null) {
            user.linkConsultant(consultants.findById(consultantId).orElseThrow());
        }
        return users.save(user).getId();
    }

    protected long createConsultant(String code, String fullName) {
        return consultants.save(new Consultant(code, fullName)).getId();
    }

    protected void setConsultantStatus(long consultantId, ConsultantStatus status) {
        jdbc.update("update consultants set status = ? where id = ?", status.name(), consultantId);
    }

    /**
     * Inserts a FAKE client straight into the table. The hash and "encrypted" bytes
     * are random
     * filler, not a real ID number, so these rows can never be decrypted.
     */
    protected long createClient(String firstName, String lastName, Long consultantId) {
        clientCounter++;
        byte[] fakeHash = new byte[32];
        ThreadLocalRandom.current().nextBytes(fakeHash);
        byte[] fakeEncrypted = new byte[40];
        String last4 = String.format("%04d", clientCounter);
        return jdbc.queryForObject("""
                insert into clients (id_number_hash, id_number_encrypted, id_number_last4,
                                     first_name, last_name, current_consultant_id)
                values (?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, fakeHash, fakeEncrypted, last4, firstName, lastName, consultantId);
    }

    /**
     * The current version of a user, read through the API like the frontend would.
     */
    protected long userVersion(ApiClient admin, long userId) {
        return admin.get("/api/users/" + userId).number("$.version");
    }
}
