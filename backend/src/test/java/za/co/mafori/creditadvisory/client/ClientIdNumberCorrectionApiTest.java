package za.co.mafori.creditadvisory.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * POST /api/clients/{id}/id-number-correction - MANAGER / ADMIN fix a wrongly
 * captured ID number. Only made-up numbers (fakeIdNumber) are used.
 */
class ClientIdNumberCorrectionApiTest extends ApiTestBase {

    /**
     * createClient gives the first client fakeIdNumber(1), the second
     * fakeIdNumber(2).
     */
    private static final String OLD_NUMBER = fakeIdNumber(1);
    private static final String OTHER_CLIENTS_NUMBER = fakeIdNumber(2);
    private static final String NEW_NUMBER = fakeIdNumber(500);

    private long thandiId;
    private long clientId;
    private ApiClient manager;

    @BeforeEach
    void createData() {
        thandiId = createConsultant("C001", "Thandi Test");
        clientId = createClient("Test", "Alpha", thandiId);
        createClient("Test", "Bravo", null); // has OTHER_CLIENTS_NUMBER
        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
        manager = signIn("manager@test.local");
    }

    private ApiClient.Response correct(ApiClient as, long id, String idNumber, String reason, long version) {
        return as.post("/api/clients/" + id + "/id-number-correction", """
                {"idNumber":"%s","reason":"%s","version":%d}""".formatted(idNumber, reason, version));
    }

    private long version(ApiClient as) {
        return as.get("/api/clients/" + clientId).number("$.version");
    }

    private ApiClient.Response lookup(ApiClient as, String idNumber) {
        return as.post("/api/clients/lookup", """
                {"idNumber":"%s"}""".formatted(idNumber));
    }

    private long auditCount() {
        return jdbc.queryForObject("select count(*) from audit_logs where action = 'CLIENT_ID_NUMBER_CORRECTED'",
                Long.class);
    }

    @Test
    void managerCorrectsTheNumberAndOnlyTheLastFourDigitsAreAudited() {
        long before = version(manager);

        ApiClient.Response response = correct(manager, clientId, NEW_NUMBER, "Typo when captured", before);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        // Like an edit: the full number is not sent back.
        assertThat(response.value("$.idNumber")).isNull();
        assertThat(response.text("$.idNumberMasked")).endsWith(NEW_NUMBER.substring(9));
        assertThat(response.number("$.version")).isGreaterThan(before);

        // Every protected form changed: the full view, and finding by number.
        assertThat(manager.get("/api/clients/" + clientId + "?include=idNumber").text("$.idNumber"))
                .isEqualTo(NEW_NUMBER);
        assertThat(lookup(manager, NEW_NUMBER).number("$.id")).isEqualTo(clientId);
        assertThat(lookup(manager, OLD_NUMBER).status()).isEqualTo(404);

        assertThat(auditCount()).isEqualTo(1);
        String metadata = jdbc.queryForObject(
                "select metadata::text from audit_logs where action = 'CLIENT_ID_NUMBER_CORRECTED'", String.class);
        assertThat(metadata).contains(OLD_NUMBER.substring(9)).contains(NEW_NUMBER.substring(9))
                .contains("Typo when captured");
        assertThat(metadata).doesNotContain(OLD_NUMBER).doesNotContain(NEW_NUMBER);
    }

    @Test
    void aNumberTypedIntoTheReasonIsRedactedInTheAuditLog() {
        ApiClient.Response response = correct(manager, clientId, NEW_NUMBER, "Was " + OLD_NUMBER,
                version(manager));
        assertThat(response.status()).as(response.body()).isEqualTo(200);

        List<String> all = jdbc.queryForList("select metadata::text from audit_logs", String.class);
        assertThat(all).noneMatch(m -> m.contains(OLD_NUMBER) || m.contains(NEW_NUMBER));
    }

    @Test
    void adminMayCorrectButConsultantMayNotEvenForTheirOwnClient() {
        ApiClient thandi = signIn("thandi@test.local");
        ApiClient.Response refused = correct(thandi, clientId, NEW_NUMBER, "Typo", version(thandi));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(auditCount()).isZero();

        ApiClient admin = signIn(ADMIN_EMAIL);
        assertThat(correct(admin, clientId, NEW_NUMBER, "Typo", version(admin)).status()).isEqualTo(200);
    }

    @Test
    void numberOfAnotherClientIsRefusedWithoutSayingWhoHasIt() {
        ApiClient.Response response = correct(manager, clientId, OTHER_CLIENTS_NUMBER, "Typo", version(manager));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:client:duplicate-id-number");
        assertThat(response.body()).doesNotContain("Bravo");
        assertThat(manager.get("/api/clients/" + clientId + "?include=idNumber").text("$.idNumber"))
                .isEqualTo(OLD_NUMBER);
        assertThat(auditCount()).isZero();
    }

    @Test
    void sameNumberAsNowIs422() {
        ApiClient.Response response = correct(manager, clientId, OLD_NUMBER, "Typo", version(manager));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:client:id-number-unchanged");
    }

    @Test
    void invalidNumberIs400AndTheNumberIsNotEchoed() {
        String badCheckDigit = NEW_NUMBER.substring(0, 12) + ((NEW_NUMBER.charAt(12) - '0' + 1) % 10);

        ApiClient.Response response = correct(manager, clientId, badCheckDigit, "Typo", version(manager));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:validation:invalid-id-number");
        assertThat(response.body()).doesNotContain(badCheckDigit);
    }

    @Test
    void staleVersionIs409() {
        long stale = version(manager);
        manager.patch("/api/clients/" + clientId, """
                {"firstName":"Test","lastName":"Renamed","email":null,"phone":null,"version":%d}""".formatted(stale));

        ApiClient.Response response = correct(manager, clientId, NEW_NUMBER, "Typo", stale);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:conflict:stale-version");
    }

    @Test
    void blankReasonIs400() {
        assertThat(correct(manager, clientId, NEW_NUMBER, "   ", version(manager)).status()).isEqualTo(400);
        assertThat(auditCount()).isZero();
    }

    @Test
    void unknownClientIs404() {
        assertThat(correct(manager, 999999, NEW_NUMBER, "Typo", 0).status()).isEqualTo(404);
    }
}
