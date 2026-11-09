package za.co.mafori.creditadvisory.imports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.mafori.creditadvisory.imports.support.FakeWeeklyRegisterWorkbook;
import za.co.mafori.creditadvisory.imports.weekly.ImportCleanupJob;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

class ImportCleanupAndAliasApiTest extends ApiTestBase {

    private static final String FILE_NAME = "Credit Advisory Online Dashboard (14 - 18 Sept 2026).xlsx";

    @Autowired
    private ImportCleanupJob cleanup;

    private long one;
    private long two;
    private ApiClient manager;

    @BeforeEach
    void createStaff() {
        one = createConsultant("C001", "Test Consultant One");
        two = createConsultant("C002", "TEST CONSULTANT TWO");
        createConsultant("C003", "TEST CONSULTANT THREE");
        createConsultant("C004", "TEST CONSULTANT FOUR");
        createConsultant("C005", "TESTLABEL");
        createUser("manager@test.local", null, RoleName.MANAGER);
        manager = signIn("manager@test.local");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long preview() {
        ApiClient.Response r = manager.upload("/api/imports/weekly-register/preview", FILE_NAME,
                FakeWeeklyRegisterWorkbook.build());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.number("$.id");
    }

    @Test
    void oldPreviewsAreExpiredAndTheirPersonalDataDeleted() {
        long old = preview();
        jdbc.update("update import_jobs set expires_at = now() - interval '1 minute' where id = ?", old);

        cleanup.expireOldPreviews();

        assertThat(jdbc.queryForObject("select state from import_jobs where id = ?", String.class, old))
                .isEqualTo("EXPIRED");
        assertThat(count("select count(*) from import_rows where import_job_id = ? and (first_name is not null"
                + " or last_name is not null or id_number_hash is not null or id_number_encrypted is not null)", old))
                .isZero();
        assertThat(count("select count(*) from audit_logs where action = 'IMPORT_EXPIRED' and target_id = ?",
                String.valueOf(old))).isEqualTo(1);
        assertThat(manager.get("/api/imports/" + old).text("$.state")).isEqualTo("EXPIRED");

        long fresh = preview(); // not 24 hours old yet: untouched
        cleanup.expireOldPreviews();
        assertThat(jdbc.queryForObject("select state from import_jobs where id = ?", String.class, fresh))
                .isEqualTo("PREVIEWED");
        assertThat(count("select count(*) from audit_logs where action = 'IMPORT_EXPIRED'")).isEqualTo(1);
    }

    @Test
    void aliasesCanBeListedAddedAndRemoved() {
        long id = preview();
        assertThat(manager
                .post("/api/imports/" + id + "/consultant-mappings",
                        """
                                {"nameInSheet":"%s","consultantId":%d}"""
                                .formatted(FakeWeeklyRegisterWorkbook.UNMAPPED_CONSULTANT, one))
                .status()).isEqualTo(200);

        ApiClient.Response list = manager.get("/api/consultant-aliases");
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(list.texts("$.items[*].alias")).containsExactly(FakeWeeklyRegisterWorkbook.UNMAPPED_CONSULTANT);
        assertThat(list.text("$.items[0].createdBy")).isEqualTo("manager@test.local");
        long aliasId = list.number("$.items[0].id");

        ApiClient.Response created = manager.post("/api/consultant-aliases", """
                {"consultantId":%d,"alias":"  T.  Consultant Two "}""".formatted(two));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.text("$.alias")).isEqualTo("T. Consultant Two");
        assertThat(manager.post("/api/consultant-aliases", """
                {"consultantId":%d,"alias":"t. consultant two"}""".formatted(one)).text("$.type"))
                .isEqualTo("urn:credit-advisory:consultant-alias:taken");
        assertThat(manager.get("/api/consultant-aliases?consultantId=" + two).number("$.totalItems")).isEqualTo(1);

        assertThat(manager.send("DELETE", "/api/consultant-aliases/" + aliasId, null).status()).isEqualTo(204);
        assertThat(manager.get("/api/consultant-aliases?search=unmapped").number("$.totalItems")).isZero();
        // The open preview needs the name mapped again before it can be confirmed.
        assertThat(manager.get("/api/imports/" + id).texts("$.blockers")).contains("UNMAPPED_CONSULTANTS");

        long consultantId = createConsultant("C009", "Some Consultant");
        createUser("consultant@test.local", consultantId, RoleName.CONSULTANT);
        assertThat(signIn("consultant@test.local").get("/api/consultant-aliases").status()).isEqualTo(403);
    }
}
