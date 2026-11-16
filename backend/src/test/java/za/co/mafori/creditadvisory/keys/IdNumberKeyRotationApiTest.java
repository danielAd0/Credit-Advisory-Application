package za.co.mafori.creditadvisory.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import za.co.mafori.creditadvisory.common.idnumber.IdNumberConfig;
import za.co.mafori.creditadvisory.common.idnumber.IdNumberProperties;
import za.co.mafori.creditadvisory.common.idnumber.IdNumberProtector;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;
import za.co.mafori.creditadvisory.onboarding.SealedPasswords;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * Rotating the ID-number encryption key: GET /api/id-number-keys and POST
 * /api/id-number-keys/re-encrypt, and the background job. This test runs as if
 * a rotation had just happened: key version 2 is current, version 1 is the
 * previous key. "Old" values are made with a protector that only knows version
 * 1. Made-up ID numbers and TEST-ONLY keys, used nowhere else.
 */
@TestPropertySource(properties = {
        "app.idnumber.encryption-key=" + IdNumberKeyRotationApiTest.NEW_KEY,
        "app.idnumber.encryption-key-version=2",
        "app.idnumber.previous-encryption-key=" + IdNumberKeyRotationApiTest.OLD_KEY,
        "app.idnumber.previous-encryption-key-version=1",
        "app.idnumber.re-encrypt-initial-delay=PT24H" })
class IdNumberKeyRotationApiTest extends ApiTestBase {

    /** Test-only keys. */
    static final String OLD_KEY = "IN1fQqCXFTZVSSIs0WfWqHwNqsDef+IdWXnLOQxbcXE=";
    static final String NEW_KEY = "Tgr9FzW3QmHGqVeRmEtHOKmBu+wNe/cJe0TZbuMnuVo=";
    private static final String LOOKUP_KEY = "kS3qgK4AJjVF1mC+kxNBVMIQFozzL2zCBZKqT862dCM=";
    private static final String TEMPORARY_PASSWORD = "Temp-Walkthrough-Password-1";

    /**
     * Knows only the old key: makes values the way the server did before the
     * rotation.
     */
    private final IdNumberProtector beforeRotation = new IdNumberProtector(Base64.getDecoder().decode(LOOKUP_KEY),
            1, Map.of(1, Base64.getDecoder().decode(OLD_KEY)));

    @Autowired
    private IdNumberKeyRotationJob job;

    private ApiClient admin;
    private long consultant;

    @BeforeEach
    void signInAdmin() {
        admin = signIn(ADMIN_EMAIL);
        consultant = createConsultant("C001", "Thandi Test");
    }

    // ---------- helpers ----------

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private byte[] stored(long clientId) {
        return jdbc.queryForObject("select id_number_encrypted from clients where id = ?", byte[].class, clientId);
    }

    /**
     * A client whose ID number is still stored with the old key, as before the
     * rotation.
     */
    private long clientOnTheOldKey(String firstName) {
        long id = createClient(firstName, "Test", consultant);
        SaIdNumber number = idNumberProtector.decrypt(stored(id));
        jdbc.update("update clients set id_number_encrypted = ? where id = ?", beforeRotation.encrypt(number), id);
        return id;
    }

    private long importRowOnTheOldKey(SaIdNumber number) {
        long job = jdbc.queryForObject("""
                insert into import_jobs (file_name, file_digest, file_size_bytes, state, uploaded_by_user_id,
                                         expires_at, import_type, week_starting)
                values ('fake.xlsx', repeat('b', 64), 10, 'PREVIEWED', ?, now() + interval '1 hour',
                        'WEEKLY_REGISTER', date '2026-09-14')
                returning id
                """, Long.class, adminId);
        return jdbc.queryForObject("""
                insert into import_rows (import_job_id, row_number, action, id_number_encrypted)
                values (?, 2, 'NEW', ?)
                returning id
                """, Long.class, job, beforeRotation.encrypt(number));
    }

    private long inviteOnTheOldKey() {
        long user = createUser("thandi@test.local", consultant, RoleName.CONSULTANT);
        return jdbc.queryForObject("""
                insert into consultant_login_invites (consultant_id, user_id, email, password_sealed, status,
                                                      generated_by_user_id)
                values (?, ?, 'thandi@test.local', ?, 'PENDING', ?)
                returning id
                """, Long.class, consultant, user, beforeRotation.sealText(SealedPasswords.PURPOSE,
                TEMPORARY_PASSWORD), adminId);
    }

    // ---------- tests ----------

    @Test
    void everythingOnTheOldKeyIsReEncrypted() {
        long old = clientOnTheOldKey("Alpha");
        long current = createClient("Beta", "Test", consultant);
        SaIdNumber oldNumber = idNumberProtector.decrypt(stored(old));
        byte[] hashBefore = jdbc.queryForObject("select id_number_hash from clients where id = ?", byte[].class, old);
        SaIdNumber rowNumber = SaIdNumber.parse(fakeIdNumber(77));
        long row = importRowOnTheOldKey(rowNumber);
        long invite = inviteOnTheOldKey();

        ApiClient.Response before = admin.get("/api/id-number-keys");
        assertThat(before.status()).as(before.body()).isEqualTo(200);
        assertThat(before.number("$.currentKeyVersion")).isEqualTo(2);
        assertThat(before.numbers("$.oldKeyVersions")).containsExactly(1L);
        assertThat(before.number("$.remaining.clients")).isEqualTo(1);
        assertThat(before.number("$.remaining.importRows")).isEqualTo(1);
        assertThat(before.number("$.remaining.loginInvites")).isEqualTo(1);
        assertThat(before.number("$.totalRemaining")).isEqualTo(3);

        ApiClient.Response run = admin.post("/api/id-number-keys/re-encrypt", "{}");

        assertThat(run.status()).as(run.body()).isEqualTo(200);
        assertThat(run.number("$.reEncrypted.clients")).isEqualTo(1);
        assertThat(run.number("$.reEncrypted.importRows")).isEqualTo(1);
        assertThat(run.number("$.reEncrypted.loginInvites")).isEqualTo(1);
        assertThat(run.number("$.failed")).isZero();
        assertThat(run.number("$.totalRemaining")).isZero();

        byte[] after = stored(old);
        assertThat(after[0]).as("now on key version 2").isEqualTo((byte) 2);
        assertThat(idNumberProtector.decrypt(after)).isEqualTo(oldNumber);
        assertThat(jdbc.queryForObject("select id_number_hash from clients where id = ?", byte[].class, old))
                .as("the lookup hash does not change").isEqualTo(hashBefore);
        assertThat(count("select version from clients where id = ?", old))
                .as("an edit that was open during the run gets a stale-version").isEqualTo(1);
        assertThat(count("select version from clients where id = ?", current)).as("already current: untouched")
                .isZero();
        assertThat(idNumberProtector.decrypt(jdbc.queryForObject(
                "select id_number_encrypted from import_rows where id = ?", byte[].class, row))).isEqualTo(rowNumber);
        assertThat(idNumberProtector.openText(SealedPasswords.PURPOSE, jdbc.queryForObject(
                "select password_sealed from consultant_login_invites where id = ?", byte[].class, invite)))
                .isEqualTo(TEMPORARY_PASSWORD);

        assertThat(jdbc.queryForObject("select metadata ->> 'keyVersion' from audit_logs"
                + " where action = 'ID_NUMBERS_REENCRYPTED'", String.class)).isEqualTo("2");
        String audit = jdbc.queryForObject(
                "select metadata::text from audit_logs where action = 'ID_NUMBERS_REENCRYPTED'", String.class);
        assertThat(audit).as("counts only").doesNotContain(oldNumber.value(), rowNumber.value(), TEMPORARY_PASSWORD);

        ApiClient.Response again = admin.post("/api/id-number-keys/re-encrypt", "{}");
        assertThat(again.number("$.reEncrypted.clients")).as("safe to repeat").isZero();
        assertThat(count("select count(*) from audit_logs where action = 'ID_NUMBERS_REENCRYPTED'"))
                .as("nothing to do: nothing audited").isEqualTo(1);
        assertThat(admin.get("/api/id-number-keys").numbers("$.oldKeyVersions")).isEmpty();
    }

    @Test
    void theBackgroundJobDoesItOnItsOwn() {
        long old = clientOnTheOldKey("Alpha");

        job.reEncryptOldValues();

        assertThat(stored(old)[0]).isEqualTo((byte) 2);
        assertThat(admin.get("/api/id-number-keys").number("$.totalRemaining")).isZero();
        assertThat(count("select count(*) from audit_logs where action = 'ID_NUMBERS_REENCRYPTED'"
                + " and actor_user_id is null")).as("done by the system, not a person").isEqualTo(1);
    }

    @Test
    void aValueThatCannotBeReadIsLeftAsItIsAndCounted() {
        long damaged = createClient("Alpha", "Test", consultant);
        byte[] garbage = new byte[41];
        garbage[0] = 1; // claims key version 1, but no key made these bytes
        jdbc.update("update clients set id_number_encrypted = ? where id = ?", garbage, damaged);
        long old = clientOnTheOldKey("Beta");

        ApiClient.Response run = admin.post("/api/id-number-keys/re-encrypt", "{}");

        assertThat(run.number("$.reEncrypted.clients")).isEqualTo(1);
        assertThat(run.number("$.failed")).isEqualTo(1);
        assertThat(run.number("$.totalRemaining")).as("the previous key must stay for now").isEqualTo(1);
        assertThat(stored(damaged)).isEqualTo(garbage);
        assertThat(stored(old)[0]).isEqualTo((byte) 2);
    }

    @Test
    void onlyAnAdmin() {
        createUser("manager@test.local", null, RoleName.MANAGER);
        ApiClient manager = signIn("manager@test.local");

        assertThat(manager.get("/api/id-number-keys").status()).isEqualTo(403);
        assertThat(manager.post("/api/id-number-keys/re-encrypt", "{}").status()).isEqualTo(403);
    }

    @Test
    void aRotationNeedsANewKeyAndANewVersion() {
        IdNumberConfig config = new IdNumberConfig();

        assertThatThrownBy(() -> config.idNumberProtector(
                new IdNumberProperties(LOOKUP_KEY, NEW_KEY, 2, OLD_KEY, 2)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("differ");
        assertThatThrownBy(() -> config.idNumberProtector(
                new IdNumberProperties(LOOKUP_KEY, NEW_KEY, 2, NEW_KEY, 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("new key");
        assertThat(config.idNumberProtector(new IdNumberProperties(LOOKUP_KEY, NEW_KEY, 2, null, 0))
                .currentKeyVersion()).as("no rotation running").isEqualTo(2);
    }
}
