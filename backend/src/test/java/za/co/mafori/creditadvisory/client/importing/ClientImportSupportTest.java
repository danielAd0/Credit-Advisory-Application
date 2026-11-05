package za.co.mafori.creditadvisory.client.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.mafori.creditadvisory.client.importing.ClientImportResult.Outcome;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/**
 * The client half of an import: classify at preview, apply at confirm. Every ID
 * number is made up with fakeIdNumber.
 */
class ClientImportSupportTest extends ApiTestBase {

    @Autowired
    private ClientImportSupport support;

    private long thandiId;
    private long importJobId;

    @BeforeEach
    void createConsultantAndImportJob() {
        thandiId = createConsultant("C001", "Thandi Test");
        // History and clients point at a real import_jobs row (foreign keys, V3).
        importJobId = jdbc.queryForObject(
                """
                        insert into import_jobs (file_name, file_digest, file_size_bytes, state, uploaded_by_user_id, expires_at)
                        values ('fake.xlsx', repeat('a', 64), 1, 'PREVIEWED', ?, now() + interval '1 hour')
                        returning id
                        """,
                Long.class, adminId);
    }

    /** createClient in ApiTestBase gives its n-th client fakeIdNumber(n). */
    private static SaIdNumber id(int n) {
        return SaIdNumber.parse(fakeIdNumber(n));
    }

    private ClientImportResult apply(ClientImportPreview preview, Long consultantId, ConflictDecision decision) {
        return support.apply(preview.staged(), consultantId, decision, importJobId, adminId);
    }

    private String clientColumn(long clientId, String column) {
        return jdbc.queryForObject("select " + column + " from clients where id = ?", String.class, clientId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private List<String> allAuditMetadata() {
        return jdbc.queryForList("select metadata::text from audit_logs", String.class);
    }

    @Test
    void aNewClientIsCreatedAssignedAndRecordedAsAnImport() {
        SaIdNumber idNumber = id(801);
        ClientImportPreview preview = support.classify(ImportedClient.fromRegister(idNumber, " Lerato ", "MOKOENA"));

        assertThat(preview.action()).isEqualTo(ClientImportAction.NEW);
        assertThat(preview.createsClient()).isTrue();
        assertThat(preview.idNumber().last4()).isEqualTo(idNumber.last4());
        assertThat(preview.firstName()).isEqualTo("Lerato");
        assertThat(count("select count(*) from clients")).as("preview writes nothing").isZero();

        ClientImportResult result = apply(preview, thandiId, null);

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(result.assigned()).isTrue();
        long clientId = result.clientId();
        assertThat(clientColumn(clientId, "current_consultant_id")).isEqualTo(String.valueOf(thandiId));
        assertThat(clientColumn(clientId, "first_name")).isEqualTo("Lerato");
        // Stored exactly like a client created through the API: it decrypts to the same
        // number.
        byte[] stored = jdbc.queryForObject("select id_number_encrypted from clients where id = ?", byte[].class,
                clientId);
        assertThat(idNumberProtector.decrypt(stored)).isEqualTo(idNumber);

        assertThat(jdbc.queryForObject("""
                select change_type || ' / ' || reason || ' / ' || import_job_id
                from client_assignment_history where client_id = ?
                """, String.class, clientId))
                .isEqualTo("IMPORT / Imported from workbook (import job %d) / %d".formatted(importJobId, importJobId));
        assertThat(clientColumn(clientId, "created_by_import_job_id")).isEqualTo(String.valueOf(importJobId));
        assertThat(jdbc.queryForObject(
                "select metadata ->> 'source' from audit_logs where action = 'CLIENT_CREATED'", String.class))
                .isEqualTo("IMPORT");
        assertThat(allAuditMetadata()).noneMatch(m -> m.contains(idNumber.value()) || m.contains("Mokoena"));
    }

    @Test
    void theSamePersonWithTheSameNamesIsUnchangedAndLinked() {
        long clientId = createClient("Lerato", "Mokoena", thandiId); // fakeIdNumber(1)

        ClientImportPreview preview = support.classify(ImportedClient.fromRegister(id(1), "LERATO", " mokoena "));

        assertThat(preview.action()).isEqualTo(ClientImportAction.UNCHANGED);
        assertThat(preview.existingClientId()).isEqualTo(clientId);
        assertThat(preview.existingClientVersion()).isZero();

        ClientImportResult result = apply(preview, null, null);
        assertThat(result.outcome()).isEqualTo(Outcome.LINKED);
        assertThat(result.clientId()).isEqualTo(clientId);
        assertThat(clientColumn(clientId, "first_name")).as("stored spelling kept").isEqualTo("Lerato");
        assertThat(count("select count(*) from audit_logs where action = 'CLIENT_UPDATED'")).isZero();
    }

    @Test
    void contactDetailsInTheFileUpdateTheClientButEmptyCellsClearNothing() {
        long clientId = createClient("Lerato", "Mokoena", thandiId);
        jdbc.update("update clients set email = 'old@example.com' where id = ?", clientId);

        ClientImportPreview withPhone = support.classify(
                new ImportedClient(id(1), "Lerato", "Mokoena", null, "082 123 4567"));
        assertThat(withPhone.action()).isEqualTo(ClientImportAction.UPDATE);
        assertThat(withPhone.differingFields()).containsExactly("phone");
        assertThat(withPhone.phone()).isEqualTo("+27821234567");

        ClientImportResult result = apply(withPhone, null, null);

        assertThat(result.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(clientColumn(clientId, "phone")).isEqualTo("+27821234567");
        assertThat(clientColumn(clientId, "email")).as("empty email cell keeps the stored one")
                .isEqualTo("old@example.com");
        assertThat(jdbc.queryForObject(
                "select metadata ->> 'fields' from audit_logs where action = 'CLIENT_UPDATED'", String.class))
                .isEqualTo("[\"phone\"]");
    }

    @Test
    void differentNamesAreAConflictThatOnlyAManagersDecisionResolves() {
        long clientId = createClient("Lerato", "Mokoena", thandiId);

        ClientImportPreview preview = support.classify(ImportedClient.fromRegister(id(1), "Sipho", "Ndlovu"));
        assertThat(preview.action()).isEqualTo(ClientImportAction.CONFLICT);
        assertThat(preview.differingFields()).containsExactly("firstName", "lastName");

        assertThat(apply(preview, null, null).outcome()).as("no decision = skip").isEqualTo(Outcome.SKIPPED);
        assertThat(apply(preview, null, ConflictDecision.SKIP).clientId()).isNull();
        assertThat(clientColumn(clientId, "first_name")).isEqualTo("Lerato");

        ClientImportResult kept = apply(preview, null, ConflictDecision.KEEP_EXISTING_NAMES);
        assertThat(kept.outcome()).isEqualTo(Outcome.LINKED);
        assertThat(kept.clientId()).isEqualTo(clientId);
        assertThat(clientColumn(clientId, "first_name")).isEqualTo("Lerato");

        ClientImportResult renamed = apply(preview, null, ConflictDecision.USE_IMPORTED_NAMES);
        assertThat(renamed.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(clientColumn(clientId, "first_name")).isEqualTo("Sipho");
        assertThat(clientColumn(clientId, "last_name")).isEqualTo("Ndlovu");
    }

    @Test
    void aClientChangedAfterThePreviewIsStaleAndLeftAlone() {
        long clientId = createClient("Lerato", "Mokoena", thandiId);
        ClientImportPreview preview = support.classify(
                new ImportedClient(id(1), "Lerato", "Mokoena", "new@example.com", null));
        assertThat(preview.action()).isEqualTo(ClientImportAction.UPDATE);

        // Someone edits the client between preview and confirm (that bumps the
        // version).
        jdbc.update("update clients set email = 'edited@example.com', version = version + 1 where id = ?",
                clientId);

        ClientImportResult result = apply(preview, null, null);
        assertThat(result.outcome()).isEqualTo(Outcome.STALE);
        assertThat(result.clientId()).isNull();
        assertThat(result.note()).doesNotContain(fakeIdNumber(1));
        assertThat(clientColumn(clientId, "email")).isEqualTo("edited@example.com");
    }

    @Test
    void aClientWhoAppearsTwiceInOneImportIsCreatedOnce() {
        ClientImportPreview first = support.classify(ImportedClient.fromRegister(id(802), "Bongani", "Test"));
        ClientImportPreview again = support.classify(ImportedClient.fromRegister(id(802), "BONGANI", "Test"));
        ClientImportPreview otherNames = support.classify(ImportedClient.fromRegister(id(802), "Someone", "Else"));
        assertThat(again.action()).as("both are NEW at preview").isEqualTo(ClientImportAction.NEW);

        ClientImportResult created = apply(first, thandiId, null);
        ClientImportResult linked = apply(again, thandiId, null);
        ClientImportResult stale = apply(otherNames, thandiId, null);

        assertThat(created.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(linked.outcome()).isEqualTo(Outcome.LINKED);
        assertThat(linked.clientId()).isEqualTo(created.clientId());
        assertThat(stale.outcome()).isEqualTo(Outcome.STALE);
        assertThat(count("select count(*) from clients")).isEqualTo(1);
        assertThat(count("select count(*) from client_assignment_history")).isEqualTo(1);
    }

    @Test
    void aNewClientWhoseConsultantIsNotActiveIsLeftUnassigned() {
        setConsultantStatus(thandiId, ConsultantStatus.SUSPENDED);
        ClientImportPreview preview = support.classify(ImportedClient.fromRegister(id(803), "Lerato", "Test"));

        ClientImportResult result = apply(preview, thandiId, null);

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(result.assigned()).isFalse();
        assertThat(result.note()).contains("left unassigned");
        assertThat(clientColumn(result.clientId(), "current_consultant_id")).isNull();
        assertThat(count("select count(*) from client_assignment_history")).isZero();
    }

    @Test
    void invalidRowsGiveAReasonWithoutTheIdNumberAndAreNeverApplied() {
        ClientImportPreview noId = support.classify(ImportedClient.fromRegister(null, "Lerato", "Test"));
        ClientImportPreview noName = support.classify(ImportedClient.fromRegister(id(804), "  ", "Test"));
        ClientImportPreview badPhone = support.classify(new ImportedClient(id(804), "Lerato", "Test", null, "12"));
        ClientImportPreview badEmail = support.classify(
                new ImportedClient(id(804), "Lerato", "Test", "not-an-email", null));

        for (ClientImportPreview invalid : List.of(noId, noName, badPhone, badEmail)) {
            assertThat(invalid.action()).isEqualTo(ClientImportAction.INVALID);
            assertThat(invalid.idNumber()).isNull();
            assertThat(invalid.invalidReason()).isNotBlank().doesNotContain(fakeIdNumber(804));
            assertThatThrownBy(invalid::staged).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(count("select count(*) from clients")).isZero();
    }
}
