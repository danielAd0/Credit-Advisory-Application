package za.co.mafori.creditadvisory.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * Create, view (full ID), lookup and edit a client (contract section 4). Every
 * ID
 * number is made up with fakeIdNumber.
 */
class ClientApiTest extends ApiTestBase {

    private static final String ID_NUMBER = fakeIdNumber(501);
    private static final String OTHER_ID_NUMBER = fakeIdNumber(502);

    private long thandiId;
    private long siphoId;
    private long suspendedId;
    private ApiClient thandi;
    private ApiClient sipho;
    private ApiClient manager;

    @BeforeEach
    void createPeople() {
        thandiId = createConsultant("C001", "Thandi Test");
        siphoId = createConsultant("C002", "Sipho Test");
        suspendedId = createConsultant("C003", "Lerato Test");
        setConsultantStatus(suspendedId, ConsultantStatus.SUSPENDED);
        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("sipho@test.local", siphoId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
        thandi = signIn("thandi@test.local");
        sipho = signIn("sipho@test.local");
        manager = signIn("manager@test.local");
    }

    private ApiClient.Response create(ApiClient as, String idNumber, String extraJson) {
        return as.post("/api/clients", """
                {"idNumber":"%s","firstName":"Lerato","lastName":"Test"%s}""".formatted(idNumber, extraJson));
    }

    private long createdBy(ApiClient as) {
        ApiClient.Response created = create(as, ID_NUMBER, "");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return created.number("$.id");
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ?", Long.class, action);
    }

    private String latestAuditResult() {
        return jdbc.queryForObject("""
                select metadata ->> 'result' from audit_logs where action = 'CLIENT_LOOKUP' order by id desc limit 1
                """, String.class);
    }

    private List<String> allAuditMetadata() {
        return jdbc.queryForList("select metadata::text from audit_logs", String.class);
    }

    // ---------- create ----------

    @Test
    void consultantCreatesAClientForThemselves() {
        ApiClient.Response created = create(thandi, ID_NUMBER,
                ",\"email\":\" lerato@example.com \",\"phone\":\"082 123 4567\"");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.text("$.idNumber")).isEqualTo(ID_NUMBER);
        assertThat(created.text("$.idNumberMasked")).isEqualTo("*********" + ID_NUMBER.substring(9));
        assertThat(created.number("$.consultant.id")).isEqualTo(thandiId);
        assertThat(created.text("$.email")).isEqualTo("lerato@example.com");
        assertThat(created.text("$.phone")).isEqualTo("+27821234567");

        long clientId = created.number("$.id");
        ApiClient.Response history = manager.get("/api/clients/" + clientId + "/assignments");
        assertThat(history.text("$[0].changeType")).isEqualTo("MANUAL");
        assertThat(history.text("$[0].reason")).isEqualTo("Client created");
        assertThat(history.text("$[0].changedBy.email")).isEqualTo("thandi@test.local");

        assertThat(auditCount("CLIENT_CREATED")).isEqualTo(1);
        assertThat(thandi.get("/api/clients").numbers("$.items[*].id")).containsExactly(clientId);
    }

    @Test
    void theIdNumberIsNeverStoredInPlainText() {
        long clientId = createdBy(thandi);

        String storedLast4 = jdbc.queryForObject("select id_number_last4 from clients where id = ?",
                String.class, clientId);
        byte[] encrypted = jdbc.queryForObject("select id_number_encrypted from clients where id = ?",
                byte[].class, clientId);
        assertThat(storedLast4).isEqualTo(ID_NUMBER.substring(9));
        assertThat(new String(encrypted, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(ID_NUMBER);
        assertThat(allAuditMetadata()).noneMatch(m -> m.contains(ID_NUMBER));
    }

    @Test
    void consultantCannotCreateForSomeoneElse() {
        ApiClient.Response response = create(thandi, ID_NUMBER, ",\"consultantId\":" + siphoId);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:client:cannot-assign-to-others");
    }

    @Test
    void managerChoosesTheConsultantOrLeavesItUnassigned() {
        ApiClient.Response unassigned = create(manager, ID_NUMBER, "");
        assertThat(unassigned.status()).as(unassigned.body()).isEqualTo(201);
        assertThat(unassigned.value("$.consultant")).isNull();
        assertThat(manager.get("/api/clients/" + unassigned.number("$.id") + "/assignments").size("$")).isZero();

        ApiClient.Response forSipho = create(manager, OTHER_ID_NUMBER, ",\"consultantId\":" + siphoId);
        assertThat(forSipho.status()).isEqualTo(201);
        assertThat(forSipho.number("$.consultant.id")).isEqualTo(siphoId);
    }

    @Test
    void cannotAssignToAnInactiveOrMissingConsultant() {
        ApiClient.Response suspended = create(manager, ID_NUMBER, ",\"consultantId\":" + suspendedId);
        assertThat(suspended.status()).isEqualTo(422);
        assertThat(suspended.text("$.type")).isEqualTo("urn:credit-advisory:client:consultant-not-active");

        assertThat(create(manager, ID_NUMBER, ",\"consultantId\":999999").status()).isEqualTo(404);
    }

    @Test
    void theSameIdNumberCannotBeAddedTwice() {
        createdBy(thandi);
        String sameNumberTypedDifferently = ID_NUMBER.substring(0, 6) + " " + ID_NUMBER.substring(6, 10) + " "
                + ID_NUMBER.substring(10);

        ApiClient.Response duplicate = create(sipho, sameNumberTypedDifferently, "");

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.text("$.type")).isEqualTo("urn:credit-advisory:client:duplicate-id-number");
        // Says nothing about the existing client.
        assertThat(duplicate.body()).doesNotContain("Thandi").doesNotContain("Lerato");
    }

    @Test
    void invalidInputIsRejected() {
        String wrongCheckDigit = ID_NUMBER.substring(0, 12) + ((ID_NUMBER.charAt(12) - '0' + 1) % 10);
        ApiClient.Response badId = create(thandi, wrongCheckDigit, "");
        assertThat(badId.status()).isEqualTo(400);
        assertThat(badId.text("$.type")).isEqualTo("urn:credit-advisory:validation:invalid-id-number");
        assertThat(badId.body()).doesNotContain(wrongCheckDigit);

        ApiClient.Response badPhone = create(thandi, ID_NUMBER, ",\"phone\":\"12345\"");
        assertThat(badPhone.status()).isEqualTo(400);
        assertThat(badPhone.text("$.type")).isEqualTo("urn:credit-advisory:validation:invalid-phone");

        ApiClient.Response noName = thandi.post("/api/clients", """
                {"idNumber":"%s","firstName":" ","lastName":"Test"}""".formatted(ID_NUMBER));
        assertThat(noName.status()).isEqualTo(400);
        assertThat(noName.text("$.type")).isEqualTo("urn:credit-advisory:validation:invalid-request");

        assertThat(auditCount("CLIENT_CREATED")).isZero();
    }

    // ---------- view ----------

    @Test
    void viewingWithoutAskingLeavesTheIdNumberOutAndIsNotAudited() {
        long clientId = createdBy(thandi);

        ApiClient.Response mine = thandi.get("/api/clients/" + clientId);

        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.value("$.idNumber")).isNull();
        assertThat(mine.text("$.idNumberMasked")).isEqualTo("*********" + ID_NUMBER.substring(9));
        assertThat(mine.text("$.firstName")).isEqualTo("Lerato");
        assertThat(mine.body()).doesNotContain(ID_NUMBER);
        assertThat(auditCount("CLIENT_VIEWED")).isZero();
    }

    @Test
    void askingForTheIdNumberShowsItAndIsAudited() {
        long clientId = createdBy(thandi);

        ApiClient.Response mine = thandi.get("/api/clients/" + clientId + "?include=idNumber");
        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.text("$.idNumber")).isEqualTo(ID_NUMBER);
        assertThat(manager.get("/api/clients/" + clientId + "?include=idNumber").text("$.idNumber"))
                .isEqualTo(ID_NUMBER);

        assertThat(auditCount("CLIENT_VIEWED")).isEqualTo(2);
        assertThat(thandi.get("/api/clients/" + clientId + "?include=everything").status()).isEqualTo(400);
    }

    @Test
    void someoneElsesClientLooksLikeItDoesNotExist() {
        long clientId = createdBy(thandi);

        ApiClient.Response response = sipho.get("/api/clients/" + clientId + "?include=idNumber");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).doesNotContain(ID_NUMBER).doesNotContain("Lerato");
        assertThat(sipho.get("/api/clients/" + clientId).status()).isEqualTo(404);
        assertThat(sipho.get("/api/clients/999999").status()).isEqualTo(404);
        assertThat(auditCount("CLIENT_VIEWED")).isZero();
    }

    // ---------- lookup ----------

    @Test
    void lookupFindsAClientByIdNumberAndIsAudited() {
        long clientId = createdBy(thandi);
        String withHyphens = ID_NUMBER.substring(0, 6) + "-" + ID_NUMBER.substring(6);

        ApiClient.Response found = manager.post("/api/clients/lookup", """
                {"idNumber":"%s"}""".formatted(withHyphens));

        assertThat(found.status()).as(found.body()).isEqualTo(200);
        assertThat(found.number("$.id")).isEqualTo(clientId);
        assertThat(found.body()).doesNotContain(ID_NUMBER); // list item: masked only
        assertThat(latestAuditResult()).isEqualTo("found");
    }

    @Test
    void lookupOfAnUnknownOrHiddenClientIs404ButStillAudited() {
        createdBy(thandi);

        ApiClient.Response unknown = manager.post("/api/clients/lookup", """
                {"idNumber":"%s"}""".formatted(OTHER_ID_NUMBER));
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(latestAuditResult()).isEqualTo("not-found");

        ApiClient.Response hidden = sipho.post("/api/clients/lookup", """
                {"idNumber":"%s"}""".formatted(ID_NUMBER));
        assertThat(hidden.status()).isEqualTo(404);
        assertThat(latestAuditResult()).isEqualTo("not-visible");

        assertThat(auditCount("CLIENT_LOOKUP")).isEqualTo(2);
        assertThat(allAuditMetadata()).noneMatch(m -> m.contains(ID_NUMBER) || m.contains(OTHER_ID_NUMBER));
    }

    // ---------- update ----------

    @Test
    void updateChangesDetailsAndAuditsOnlyTheFieldNames() {
        ApiClient.Response created = create(thandi, ID_NUMBER, ",\"phone\":\"0821234567\"");
        long clientId = created.number("$.id");

        ApiClient.Response updated = thandi.patch("/api/clients/" + clientId, """
                {"firstName":"Lerato","lastName":"Mokoena","email":"lerato@example.com","phone":null,"version":%d}"""
                .formatted(created.number("$.version")));

        assertThat(updated.status()).as(updated.body()).isEqualTo(200);
        assertThat(updated.text("$.lastName")).isEqualTo("Mokoena");
        assertThat(updated.text("$.email")).isEqualTo("lerato@example.com");
        assertThat(updated.value("$.phone")).isNull();
        // An edit does not reveal the full ID number (only ?include=idNumber does,
        // audited).
        assertThat(updated.value("$.idNumber")).isNull();
        assertThat(updated.body()).doesNotContain(ID_NUMBER);
        String fields = jdbc.queryForObject("""
                select metadata ->> 'fields' from audit_logs where action = 'CLIENT_UPDATED'
                """, String.class);
        assertThat(fields).isEqualTo("[\"lastName\", \"email\", \"phone\"]");
        assertThat(allAuditMetadata()).noneMatch(m -> m.contains("Mokoena") || m.contains("lerato@example.com"));

        ApiClient.Response stale = thandi.patch("/api/clients/" + clientId, """
                {"firstName":"Lerato","lastName":"Again","version":%d}""".formatted(created.number("$.version")));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.text("$.type")).isEqualTo("urn:credit-advisory:conflict:stale-version");
    }

    @Test
    void cannotEditSomeoneElsesClient() {
        ApiClient.Response created = create(thandi, ID_NUMBER, "");

        ApiClient.Response response = sipho.patch("/api/clients/" + created.number("$.id"), """
                {"firstName":"Changed","lastName":"Test","version":%d}""".formatted(created.number("$.version")));

        assertThat(response.status()).isEqualTo(404);
        assertThat(thandi.get("/api/clients/" + created.number("$.id")).text("$.firstName")).isEqualTo("Lerato");
    }
}
