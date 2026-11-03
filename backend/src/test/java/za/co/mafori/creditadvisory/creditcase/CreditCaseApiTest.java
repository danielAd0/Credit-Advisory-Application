package za.co.mafori.creditadvisory.creditcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * Credit cases and case notes (contract section 9, FR-19), and that they
 * survive
 * a client transfer (FR-15). All data is made up.
 */
class CreditCaseApiTest extends ApiTestBase {

    private long thandiId;
    private long siphoId;
    private long thandisClient;
    private long siphosClient;
    private ApiClient thandi;
    private ApiClient sipho;
    private ApiClient manager;

    @BeforeEach
    void createPeople() {
        thandiId = createConsultant("C001", "Thandi Test");
        siphoId = createConsultant("C002", "Sipho Test");
        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("sipho@test.local", siphoId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
        thandisClient = createClient("Lerato", "Test", thandiId);
        siphosClient = createClient("Bongani", "Test", siphoId);
        thandi = signIn("thandi@test.local");
        sipho = signIn("sipho@test.local");
        manager = signIn("manager@test.local");
    }

    private ApiClient.Response openCase(ApiClient as, long clientId, String title) {
        return as.post("/api/cases", """
                {"clientId":%d,"title":"%s"}""".formatted(clientId, title));
    }

    private long openCaseAs(ApiClient as, long clientId) {
        ApiClient.Response created = openCase(as, clientId, "Debt review application");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return created.number("$.id");
    }

    private ApiClient.Response addNote(ApiClient as, long caseId, String note) {
        return as.post("/api/cases/" + caseId + "/notes", """
                {"note":"%s"}""".formatted(note));
    }

    private ApiClient.Response update(ApiClient as, long caseId, String title, String status, long version) {
        return as.patch("/api/cases/" + caseId, """
                {"title":"%s","status":"%s","version":%d}""".formatted(title, status, version));
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ?", Long.class, action);
    }

    @Test
    void consultantOpensACaseForTheirOwnClientAndAddsNotes() {
        ApiClient.Response created = openCase(thandi, thandisClient, "  Debt review application  ");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        long caseId = created.number("$.id");
        assertThat(created.text("$.title")).isEqualTo("Debt review application");
        assertThat(created.text("$.status")).isEqualTo("OPEN");
        assertThat(created.value("$.closedAt")).isNull();
        assertThat(created.number("$.client.id")).isEqualTo(thandisClient);
        assertThat(created.text("$.client.idNumberMasked")).startsWith("*********");
        assertThat(created.text("$.createdBy.email")).isEqualTo("thandi@test.local");
        assertThat(created.text("$.createdBy.consultantName")).isEqualTo("Thandi Test");
        assertThat(created.size("$.notes")).isZero();

        ApiClient.Response note = addNote(thandi, caseId, "Called the client, documents to follow.");
        assertThat(note.status()).as(note.body()).isEqualTo(201);
        assertThat(note.text("$.author.email")).isEqualTo("thandi@test.local");
        addNote(thandi, caseId, "Payslip received.");

        ApiClient.Response detail = thandi.get("/api/cases/" + caseId);
        assertThat(detail.status()).isEqualTo(200);
        // Newest first
        assertThat(detail.texts("$.notes[*].note"))
                .containsExactly("Payslip received.", "Called the client, documents to follow.");

        ApiClient.Response list = thandi.get("/api/cases");
        assertThat(list.numbers("$.items[*].id")).containsExactly(caseId);
        assertThat(list.body()).doesNotContain("notes");
    }

    @Test
    void consultantsCannotSeeOrTouchOtherConsultantsCases() {
        long caseId = openCaseAs(thandi, thandisClient);

        assertThat(sipho.get("/api/cases/" + caseId).status()).isEqualTo(404);
        assertThat(update(sipho, caseId, "Taken over", "IN_PROGRESS", 0).status()).isEqualTo(404);
        assertThat(addNote(sipho, caseId, "Not my client").status()).isEqualTo(404);
        assertThat(sipho.get("/api/cases").size("$.items")).isZero();

        // Opening a case for someone else's client looks exactly like a missing client.
        ApiClient.Response foreign = openCase(sipho, thandisClient, "Not my client");
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.text("$.type")).isEqualTo("urn:credit-advisory:client:not-found");

        // The case is untouched.
        ApiClient.Response detail = thandi.get("/api/cases/" + caseId);
        assertThat(detail.text("$.status")).isEqualTo("OPEN");
        assertThat(detail.size("$.notes")).isZero();
    }

    @Test
    void managerSeesAllCasesAndCanFilterThem() {
        long thandisCase = openCaseAs(thandi, thandisClient);
        long siphosCase = openCaseAs(sipho, siphosClient);
        update(sipho, siphosCase, "Debt review application", "IN_PROGRESS", 0);

        assertThat(manager.get("/api/cases").numbers("$.items[*].id"))
                .containsExactlyInAnyOrder(thandisCase, siphosCase);
        assertThat(manager.get("/api/cases?consultantId=" + thandiId).numbers("$.items[*].id"))
                .containsExactly(thandisCase);
        assertThat(manager.get("/api/cases?clientId=" + siphosClient).numbers("$.items[*].id"))
                .containsExactly(siphosCase);
        assertThat(manager.get("/api/cases?status=IN_PROGRESS").numbers("$.items[*].id"))
                .containsExactly(siphosCase);
        assertThat(manager.get("/api/cases?status=DONE").status()).isEqualTo(400);

        // A consultant's consultantId filter is ignored: still only their own cases.
        assertThat(thandi.get("/api/cases?consultantId=" + siphoId).numbers("$.items[*].id"))
                .containsExactly(thandisCase);

        // A manager can open a case for any client and work on it.
        long managersCase = openCaseAs(manager, thandisClient);
        assertThat(addNote(manager, managersCase, "Escalated by the manager.").status()).isEqualTo(201);
        assertThat(thandi.get("/api/cases/" + managersCase).text("$.createdBy.email"))
                .isEqualTo("manager@test.local");
    }

    @Test
    void closingACaseStopsNewNotesUntilItIsReopened() {
        long caseId = openCaseAs(thandi, thandisClient);

        ApiClient.Response closed = update(thandi, caseId, "Debt review application", "CLOSED", 0);
        assertThat(closed.status()).as(closed.body()).isEqualTo(200);
        assertThat(closed.text("$.status")).isEqualTo("CLOSED");
        assertThat(closed.value("$.closedAt")).isNotNull();
        long version = closed.number("$.version");
        assertThat(version).isEqualTo(1);

        ApiClient.Response refused = addNote(thandi, caseId, "Too late");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.text("$.type")).isEqualTo("urn:credit-advisory:case:closed");

        ApiClient.Response reopened = update(thandi, caseId, "Debt review application", "OPEN", version);
        assertThat(reopened.text("$.status")).isEqualTo("OPEN");
        assertThat(reopened.value("$.closedAt")).isNull();
        assertThat(addNote(thandi, caseId, "Client came back.").status()).isEqualTo(201);
    }

    @Test
    void anOutOfDateVersionIsRejected() {
        long caseId = openCaseAs(thandi, thandisClient);
        update(thandi, caseId, "Renamed once", "OPEN", 0);

        ApiClient.Response stale = update(manager, caseId, "Renamed again", "OPEN", 0);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.text("$.type")).isEqualTo("urn:credit-advisory:conflict:stale-version");
        assertThat(thandi.get("/api/cases/" + caseId).text("$.title")).isEqualTo("Renamed once");
    }

    @Test
    void casesAndNotesMoveWithTheClientWhenTheyAreTransferred() {
        long caseId = openCaseAs(thandi, thandisClient);
        addNote(thandi, caseId, "Written by Thandi before the handover.");

        ApiClient.Response transfer = manager.post("/api/client-transfers", """
                {"fromConsultantId":%d,"toConsultantId":%d,"clientIds":[%d],"reason":"Test handover"}"""
                .formatted(thandiId, siphoId, thandisClient));
        assertThat(transfer.status()).as(transfer.body()).isEqualTo(200);

        // The new consultant has the whole case, and the note keeps its original
        // author.
        ApiClient.Response detail = sipho.get("/api/cases/" + caseId);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.texts("$.notes[*].note")).containsExactly("Written by Thandi before the handover.");
        assertThat(detail.text("$.notes[0].author.email")).isEqualTo("thandi@test.local");
        assertThat(detail.text("$.createdBy.email")).isEqualTo("thandi@test.local");
        assertThat(addNote(sipho, caseId, "Picked up by Sipho.").status()).isEqualTo(201);

        // The old consultant no longer sees it.
        assertThat(thandi.get("/api/cases/" + caseId).status()).isEqualTo(404);
        assertThat(thandi.get("/api/cases").size("$.items")).isZero();
    }

    @Test
    void notesCannotBeChangedAndCasesCannotBeDeleted() {
        long caseId = openCaseAs(thandi, thandisClient);
        addNote(thandi, caseId, "Original wording.");

        assertThatThrownBy(() -> jdbc.update("update case_notes set note = 'Rewritten'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from case_notes"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from credit_cases"))
                .isInstanceOf(DataAccessException.class);

        assertThat(thandi.get("/api/cases/" + caseId).texts("$.notes[*].note")).containsExactly("Original wording.");
    }

    @Test
    void theAuditLogRecordsWhatHappenedButNeverWhatTheCaseSays() {
        long caseId = openCaseAs(thandi, thandisClient);
        addNote(thandi, caseId, "Client owes Edgars R5000.");
        thandi.get("/api/cases/" + caseId);
        update(thandi, caseId, "Debt review application", "IN_PROGRESS", 0);
        // Saving without a change writes no entry.
        update(thandi, caseId, "Debt review application", "IN_PROGRESS", 1);

        assertThat(auditCount("CASE_CREATED")).isEqualTo(1);
        assertThat(auditCount("CASE_NOTE_ADDED")).isEqualTo(1);
        assertThat(auditCount("CASE_VIEWED")).isEqualTo(1);
        assertThat(auditCount("CASE_UPDATED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select metadata ->> 'toStatus' from audit_logs where action = 'CASE_UPDATED'
                """, String.class)).isEqualTo("IN_PROGRESS");

        List<String> metadata = jdbc.queryForList("select metadata::text from audit_logs", String.class);
        assertThat(String.join(" ", metadata)).doesNotContain("Edgars").doesNotContain("Debt review");
    }

    @Test
    void badRequestsAreRejected() {
        long caseId = openCaseAs(thandi, thandisClient);

        assertThat(openCase(thandi, thandisClient, "   ").status()).isEqualTo(400);
        assertThat(thandi.post("/api/cases", "{\"title\":\"No client\"}").status()).isEqualTo(400);
        assertThat(addNote(thandi, caseId, "  ").status()).isEqualTo(400);
        assertThat(addNote(thandi, caseId, "x".repeat(4001)).status()).isEqualTo(400);
        assertThat(update(thandi, caseId, "Debt review application", "DONE", 0).status()).isEqualTo(400);
        assertThat(openCase(thandi, 999_999, "Nobody").status()).isEqualTo(404);
        assertThat(thandi.get("/api/cases/999999").text("$.type")).isEqualTo("urn:credit-advisory:case:not-found");
    }
}
