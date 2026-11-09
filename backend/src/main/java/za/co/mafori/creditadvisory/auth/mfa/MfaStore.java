package za.co.mafori.creditadvisory.auth.mfa;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The user_mfa and user_mfa_recovery_codes tables (V18). */
@Repository
public class MfaStore {

    private final JdbcTemplate jdbc;

    public MfaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isEnrolled(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from user_mfa where user_id = ?)", Boolean.class, userId));
    }

    public Optional<byte[]> findSecret(long userId) {
        return jdbc.queryForList("select secret_encrypted from user_mfa where user_id = ?", byte[].class, userId)
                .stream().findFirst();
    }

    public void enrol(long userId, byte[] secretEncrypted, long usedStep, List<String> recoveryCodeHashes) {
        jdbc.update("insert into user_mfa (user_id, secret_encrypted, last_used_step) values (?, ?, ?)",
                userId, secretEncrypted, usedStep);
        for (String hash : recoveryCodeHashes) {
            jdbc.update("insert into user_mfa_recovery_codes (user_id, code_hash) values (?, ?)", userId, hash);
        }
    }

    /**
     * Records that this step's code was used. False if it (or a later one) was
     * already used: one statement, so two simultaneous sign-ins with the same
     * code cannot both get through.
     */
    public boolean markStepUsed(long userId, long step) {
        return jdbc.update("""
                update user_mfa set last_used_step = ?
                where user_id = ? and (last_used_step is null or last_used_step < ?)
                """, step, userId, step) == 1;
    }

    /** True if an unused code with this hash existed; it is now used. */
    public boolean useRecoveryCode(long userId, String codeHash) {
        return jdbc.update("""
                update user_mfa_recovery_codes set used_at = now()
                where user_id = ? and code_hash = ? and used_at is null
                """, userId, codeHash) == 1;
    }

    public int unusedRecoveryCodes(long userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from user_mfa_recovery_codes where user_id = ? and used_at is null",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** Removes MFA (the recovery codes go with it). True if there was any. */
    public boolean remove(long userId) {
        return jdbc.update("delete from user_mfa where user_id = ?", userId) == 1;
    }

    /**
     * Raises the account version, so AccountStillActiveFilter signs every session
     * of this user out on its next request.
     */
    public void signOutEverywhere(long userId) {
        jdbc.update("update users set version = version + 1 where id = ?", userId);
    }
}
