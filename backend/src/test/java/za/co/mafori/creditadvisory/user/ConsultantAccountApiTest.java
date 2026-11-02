package za.co.mafori.creditadvisory.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;

public class ConsultantAccountApiTest extends ApiTestBase {

    private static final String TEMP_PASSWORD = "Temp1234";

    private ApiClient admin;

    @BeforeEach
    void signInAdmin() {
        admin = signIn(ADMIN_EMAIL);
    }

    private ApiClient.Response create(ApiClient as, String code, String name, String email) {
        return as.post("/api/consultant-accounts", """
                {"consultantCode":"%s", "fullName":"%s","email":"%s", "temporaryPassword":"%s"}"""
                .formatted(code, name, email, TEMP_PASSWORD));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void adminCreatesConsultantWithLoginThatCanSignIn() {
        ApiClient.Response response = create(admin, " c010 ", "Naledi Test", "Naledi@Test.Local");

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.text("$.consultant.consultantCode")).isEqualTo("C010");
        assertThat(response.text("$.consultant.status")).isEqualTo("ACTIVE");
        assertThat(response.text("$.user.email")).isEqualTo("naledi@test.local");
        assertThat(response.texts("$.user.roles")).containsExactly("CONSULTANT");
        assertThat(response.number("$.user.consultant.id")).isEqualTo(response.number("$.consultant.id"));

        ApiClient consultant = browser();
        assertThat(consultant.signIn("naledi@test.local", TEMP_PASSWORD).status()).isEqualTo(200);
        assertThat(consultant.get("/api/clients").status()).isEqualTo(200);

        assertThat(count("select count(*) from audit_logs where action = 'CONSULTANT_ACCOUNT_CREATED'"))
                .isEqualTo(1);
    }

    @Test
    void duplicateEmailCreatesNothing() {
        createUser("taken@test.local", null, RoleName.MANAGER);

        ApiClient.Response response = create(admin, "C011", "Duplicate Test", "taken@test.local");

        assertThat(response.status()).as(response.body()).isEqualTo(409);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:user:duplicate-email");
        assertThat(count("select count(*) from consultants where consultant_code = 'C011'")).isZero();
    }

    @Test
    void duplicateCodeIsRejected() {
        createConsultant("C012", "Existing Test");

        ApiClient.Response response = create(admin, "c012", "Another Test", "another@test.local");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:consultant:duplicate-code");
        assertThat(count("select count(*) from users where email = 'another@test.local'")).isZero();
    }

    @Test
    void shortPasswordIsRejected() {
        ApiClient.Response response = admin.post("/api/consultant-accounts",
                """
                        {"consultantCode":"C013","fullName":"Short Test","email":"short@test.local","temporaryPassword":"short"}""");

        assertThat(response.status()).isEqualTo(400);
        assertThat(count("select count(*) from consultants where consultant_code = 'C013'")).isZero();
    }

    @Test
    void managerCannotCreateConsultantAccounts() {
        createUser("manager@test.local", null, RoleName.MANAGER);

        ApiClient.Response response = create(signIn("manager@test.local"), "C014", "Nope Test", "nope@test.local");

        assertThat(response.status()).isEqualTo(403);
    }
}
