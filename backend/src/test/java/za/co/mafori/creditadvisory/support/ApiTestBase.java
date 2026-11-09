package za.co.mafori.creditadvisory.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import za.co.mafori.creditadvisory.auth.LoginRateLimiter;
import za.co.mafori.creditadvisory.client.IdRevealLimiter;
import za.co.mafori.creditadvisory.common.idnumber.IdNumberProtector;
import za.co.mafori.creditadvisory.common.idnumber.ProtectedIdNumber;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;
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

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    @Autowired
    private IdRevealLimiter idRevealLimiter;

    @Autowired
    protected IdNumberProtector idNumberProtector;

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
        // Every test signs in from 127.0.0.1, so start each test with a clean rate
        // limit.
        loginRateLimiter.clear();
        idRevealLimiter.clear();
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

    /**
     * Re-enters PASSWORD in this signed-in browser, so it may see full ID numbers
     * for the next few minutes (POST /api/auth/reauthenticate).
     */
    protected void confirmPassword(ApiClient api) {
        ApiClient.Response response = api.post("/api/auth/reauthenticate", """
                {"password":"%s"}""".formatted(PASSWORD));
        assertThat(response.status()).as("reauthenticate: %s", response.body()).isEqualTo(200);
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
     * Inserts a client straight into the table, with a MADE-UP but valid ID number
     * (see fakeIdNumber), protected exactly like the real code does. Quicker than
     * the
     * API when a test only needs clients to exist.
     */
    protected long createClient(String firstName, String lastName, Long consultantId) {
        clientCounter++;
        ProtectedIdNumber stored = idNumberProtector.protect(SaIdNumber.parse(fakeIdNumber(clientCounter)));
        return jdbc.queryForObject("""
                insert into clients (id_number_hash, id_number_encrypted, id_number_last4,
                                     first_name, last_name, current_consultant_id)
                values (?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, stored.lookupHash(), stored.encrypted(), stored.last4(), firstName, lastName,
                consultantId);
    }

    /**
     * A made-up SA ID number that passes validation: born 1 Jan 1990, sequence n,
     * with the right check digit. Different n gives a different number. NEVER use a
     * real person's ID number in a test.
     */
    protected static String fakeIdNumber(int n) {
        String withoutCheckDigit = String.format("900101%04d08", n % 10_000);
        for (int checkDigit = 0; checkDigit <= 9; checkDigit++) {
            String candidate = withoutCheckDigit + checkDigit;
            if (SaIdNumber.isValid(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No check digit found");
    }

    /**
     * The current version of a user, read through the API like the frontend would.
     */
    protected long userVersion(ApiClient admin, long userId) {
        return admin.get("/api/users/" + userId).number("$.version");
    }
}
