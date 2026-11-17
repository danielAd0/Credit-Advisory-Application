package za.co.mafori.creditadvisory.sale;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * Flagging a typed-in sale for correction: POST and DELETE
 * /api/transactions/{id}/flag (docs/api-sales.md). A manager flags, the
 * consultant who owns the sale fixes it, and saving the fix clears the flag.
 */
class SaleFlagApiTest extends ApiTestBase {

    private static final String TUESDAY = "2026-09-15";
    private static final String NOTE = "The Insurance amount looks wrong. Check it against the client form.";

    private long thandisClient;
    private long combo;
    private ApiClient thandi;
    private ApiClient manager;

    @BeforeEach
    void createPeople() {
        long thandiId = createConsultant("C001", "Thandi Test");
        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
        thandisClient = createClient("Test", "Alpha", thandiId);
        combo = jdbc.queryForObject("select id from products where code = 'COMBO'", Long.class);
        thandi = signIn("thandi@test.local");
        manager = signIn("manager@test.local");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** Thandi's sale on TUESDAY: Combo for this amount. */
    private long thandisSale(int amount) {
        String json = """
                {"clientId":%d,"transactionDate":"%s","lines":[{"productId":%d,"amount":%d}]}"""
                .formatted(thandisClient, TUESDAY, combo, amount);
        ApiClient.Response saved = thandi.sendWithHeader("POST", "/api/transactions", json, "Idempotency-Key",
                UUID.randomUUID().toString());
        assertThat(saved.status()).as(saved.body()).isEqualTo(201);
        return saved.number("$.id");
    }

    private static ApiClient.Response flag(ApiClient as, long id, String note) {
        return as.post("/api/transactions/" + id + "/flag", note == null ? "{}" : """
                {"note":"%s"}""".formatted(note));
    }

    private static ApiClient.Response unflag(ApiClient as, long id) {
        return as.send("DELETE", "/api/transactions/" + id + "/flag", null);
    }

    /** The sale ids on the Sales history page for the week of 14 Sept. */
    private List<Long> history(ApiClient as, boolean flaggedOnly) {
        ApiClient.Response page = as.get("/api/transactions?from=2026-09-14&to=2026-09-20"
                + (flaggedOnly ? "&flagged=true" : ""));
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        return page.numbers("$.items[*].id");
    }

    /** Marks the week of 14 Sept imported, the way a confirm does. */
    private void importWeek(String monday) {
        long week = jdbc.queryForObject("""
                insert into reporting_weeks (week_starting) values (cast(? as date))
                on conflict (week_starting) do update set week_starting = excluded.week_starting
                returning id
                """, Long.class, monday);
        long job = jdbc.queryForObject("""
                insert into import_jobs (file_name, file_digest, file_size_bytes, state, uploaded_by_user_id,
                                         expires_at, committed_at, import_type, week_starting, reporting_week_id)
                values ('fake.xlsx', repeat('a', 64), 10, 'COMMITTED', ?, now(), now(),
                        'WEEKLY_REGISTER', cast(? as date), ?)
                returning id
                """, Long.class, adminId, monday, week);
        jdbc.update("""
                update client_transactions set superseded_by_import_job_id = ?
                where reporting_week_id = ? and import_job_id is null
                """, job, week);
        jdbc.update("update reporting_weeks set current_import_job_id = ? where id = ?", job, week);
    }

    // ---------- flagging ----------

    @Test
    void aManagerFlagsAndTheConsultantSeesIt() {
        long flagged = thandisSale(1500);
        long fine = thandisSale(900);

        ApiClient.Response answer = flag(manager, flagged, "  " + NOTE + "  ");

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(answer.text("$.flag.note")).as("trimmed").isEqualTo(NOTE);
        assertThat(answer.text("$.flag.flaggedBy")).isEqualTo("manager@test.local");
        assertThat(answer.value("$.flag.flaggedAt")).isNotNull();

        ApiClient.Response detail = thandi.get("/api/transactions/" + flagged);
        assertThat(detail.text("$.flag.note")).isEqualTo(NOTE);
        assertThat(detail.number("$.version")).as("a flag is not a change to the sale").isZero();
        ApiClient.Response clientSales = thandi.get("/api/transactions?clientId=" + thandisClient);
        assertThat(clientSales.texts("$[?(@.id == " + flagged + ")].flag.note")).containsExactly(NOTE);
        assertThat(thandi.get("/api/transactions/" + fine).value("$.flag")).isNull();

        assertThat(history(thandi, false)).containsExactlyInAnyOrder(flagged, fine);
        assertThat(history(thandi, true)).containsExactly(flagged);

        String audit = jdbc.queryForObject("select metadata::text from audit_logs where action = 'SALE_FLAGGED'",
                String.class);
        assertThat(audit).as("the note stays out of the audit log").doesNotContain("Insurance");
        assertThat(count("select (metadata ->> 'noteLength')::bigint from audit_logs where action = 'SALE_FLAGGED'"))
                .isEqualTo(NOTE.length());
    }

    @Test
    void flaggingAgainReplacesTheNote() {
        long id = thandisSale(1500);
        flag(manager, id, NOTE);

        ApiClient.Response again = flag(signIn(ADMIN_EMAIL), id, "Wrong date too.");

        assertThat(again.status()).as(again.body()).isEqualTo(200);
        assertThat(again.text("$.flag.note")).isEqualTo("Wrong date too.");
        assertThat(again.text("$.flag.flaggedBy")).isEqualTo(ADMIN_EMAIL);
        assertThat(count("select count(*) from audit_logs where action = 'SALE_FLAGGED'"
                + " and metadata ->> 'replacedEarlierFlag' = 'true'")).isEqualTo(1);
    }

    @Test
    void theConsultantsFixClearsTheFlag() {
        long id = thandisSale(1500);
        flag(manager, id, NOTE);

        ApiClient.Response fixed = thandi.patch("/api/transactions/" + id, """
                {"version":0,"transactionDate":"%s","lines":[{"productId":%d,"amount":1200}]}"""
                .formatted(TUESDAY, combo));

        assertThat(fixed.status()).as(fixed.body()).isEqualTo(200);
        assertThat(fixed.value("$.flag")).isNull();
        assertThat(history(manager, true)).isEmpty();
        assertThat(count("select count(*) from audit_logs where action = 'SALE_EDITED'"
                + " and metadata ->> 'flagCleared' = 'true'")).isEqualTo(1);
    }

    @Test
    void savingItUnchangedAlsoClearsTheFlag() {
        long id = thandisSale(1500);
        flag(manager, id, NOTE);

        ApiClient.Response checked = thandi.patch("/api/transactions/" + id, """
                {"version":0,"transactionDate":"%s","lines":[{"productId":%d,"amount":1500}]}"""
                .formatted(TUESDAY, combo));

        assertThat(checked.status()).as(checked.body()).isEqualTo(200);
        assertThat(checked.value("$.flag")).as("the consultant checked it and it is right").isNull();
        assertThat(checked.number("$.version")).as("the sale itself did not change").isZero();
        assertThat(count("select count(*) from audit_logs where action = 'SALE_EDITED'"
                + " and metadata ->> 'flagCleared' = 'true'")).isEqualTo(1);

        ApiClient.Response again = thandi.patch("/api/transactions/" + id, """
                {"version":0,"transactionDate":"%s","lines":[{"productId":%d,"amount":1500}]}"""
                .formatted(TUESDAY, combo));
        assertThat(again.status()).isEqualTo(200);
        assertThat(count("select count(*) from audit_logs where action = 'SALE_EDITED'"))
                .as("nothing to save and no flag: nothing audited").isEqualTo(1);
    }

    @Test
    void aManagerWithdrawsTheFlag() {
        long id = thandisSale(1500);
        flag(manager, id, NOTE);

        ApiClient.Response withdrawn = unflag(manager, id);
        ApiClient.Response again = unflag(manager, id);

        assertThat(withdrawn.status()).isEqualTo(204);
        assertThat(again.status()).as("nothing left to withdraw is fine").isEqualTo(204);
        assertThat(thandi.get("/api/transactions/" + id).value("$.flag")).isNull();
        assertThat(count("select count(*) from audit_logs where action = 'SALE_FLAG_WITHDRAWN'")).isEqualTo(1);
    }

    // ---------- the rules ----------

    @Test
    void onlyManagersAndAdminsFlag() {
        long id = thandisSale(1500);

        ApiClient.Response byConsultant = flag(thandi, id, NOTE);
        ApiClient.Response withdrawByConsultant = unflag(thandi, id);

        assertThat(byConsultant.status()).isEqualTo(403);
        assertThat(byConsultant.text("$.type")).isEqualTo("urn:credit-advisory:sale:flag-not-allowed");
        assertThat(withdrawByConsultant.status()).isEqualTo(403);
        assertThat(count("select count(*) from client_transactions where flag_note is not null")).isZero();
    }

    @Test
    void theNoteIsRequiredAndShort() {
        long id = thandisSale(1500);

        ApiClient.Response blank = flag(manager, id, "   ");
        ApiClient.Response missing = flag(manager, id, null);
        ApiClient.Response tooLong = flag(manager, id, "x".repeat(301));

        assertThat(blank.status()).isEqualTo(422);
        assertThat(blank.text("$.type")).isEqualTo("urn:credit-advisory:sale:flag-note-invalid");
        assertThat(missing.status()).isEqualTo(422);
        assertThat(tooLong.status()).isEqualTo(422);
        assertThat(flag(manager, id, "x".repeat(300)).status()).isEqualTo(200);
    }

    @Test
    void anImportedWeekCannotBeFlaggedAndOldFlagsDisappear() {
        long earlier = thandisSale(1500);
        flag(manager, earlier, NOTE);
        importWeek("2026-09-14");

        ApiClient.Response refused = flag(manager, earlier, "Too late.");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.text("$.type")).isEqualTo("urn:credit-advisory:sale:replaced");
        assertThat(manager.get("/api/transactions/" + earlier).value("$.flag"))
                .as("the spreadsheet is the record now").isNull();
    }

    @Test
    void anUnknownSaleIsNotFound() {
        assertThat(flag(manager, 999_999, NOTE).status()).isEqualTo(404);
        assertThat(unflag(manager, 999_999).status()).isEqualTo(404);
    }

}
