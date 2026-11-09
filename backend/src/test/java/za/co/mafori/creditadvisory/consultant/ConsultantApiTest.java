package za.co.mafori.creditadvisory.consultant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/**
 * Consultant codes are generated (C0001, C0002, ...) when the admin leaves the
 * code out.
 */
class ConsultantCodeApiTest extends ApiTestBase {

    private ApiClient admin;

    @BeforeEach
    void signInAsAdmin() {
        admin = signIn(ADMIN_EMAIL);
    }

    private ApiClient.Response add(String body) {
        return admin.post("/api/consultants", body);
    }

    @Test
    void aCodeIsGeneratedWhenLeftOut() {
        ApiClient.Response first = add("""
                {"fullName":"First Generated"}""");
        assertThat(first.status()).as(first.body()).isEqualTo(201);
        assertThat(first.text("$.consultantCode")).matches("C\\d{4}");

        ApiClient.Response blank = add("""
                {"consultantCode":"  ","fullName":"Second Generated"}""");
        assertThat(blank.status()).as(blank.body()).isEqualTo(201);
        int a = Integer.parseInt(first.text("$.consultantCode").substring(1));
        int b = Integer.parseInt(blank.text("$.consultantCode").substring(1));
        assertThat(b).as("the next number").isEqualTo(a + 1);

        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'CONSULTANT_CREATED'"
                + " and metadata ->> 'codeGenerated' = 'true'", Long.class)).isEqualTo(2);
    }

    @Test
    void aTakenCodeIsSkipped() {
        String next = "C" + String.format("%04d",
                jdbc.queryForObject("select last_value + (case when is_called then 1 else 0 end)"
                        + " from consultant_code_seq", Long.class));
        createConsultant(next, "Typed By Hand");

        ApiClient.Response generated = add("""
                {"fullName":"Generated After"}""");
        assertThat(generated.status()).isEqualTo(201);
        assertThat(generated.text("$.consultantCode")).isNotEqualTo(next).matches("C\\d{4}");
    }

    @Test
    void aCodeCanStillBeTypedIn() {
        ApiClient.Response typed = add("""
                {"consultantCode":" c777 ","fullName":"Existing Staff Code"}""");
        assertThat(typed.status()).isEqualTo(201);
        assertThat(typed.text("$.consultantCode")).isEqualTo("C777");
        assertThat(add("""
                {"consultantCode":"C777","fullName":"Again"}""").status()).isEqualTo(409);
        assertThat(add("""
                {"consultantCode":"not valid!","fullName":"Bad"}""").status()).isEqualTo(400);
    }

    @Test
    void aConsultantLoginGetsAGeneratedCodeToo() {
        ApiClient.Response created = admin.post("/api/consultant-accounts", """
                {"fullName":"With Login","email":"withlogin@test.local","temporaryPassword":"Temp-Password-2026"}""");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.text("$.consultant.consultantCode")).matches("C\\d{4}");
    }
}
