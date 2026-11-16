package za.co.mafori.creditadvisory.keys;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.common.idnumber.IdNumberProtector;
import za.co.mafori.creditadvisory.onboarding.SealedPasswords;

/**
 * Rotating the ID-number encryption key (docs/it-handover.md section 4.1):
 * re-encrypts everything stored with an older key version under the current
 * one. The first byte of every stored value is its key version, so "older" is
 * simply "first byte is not the current version".
 *
 * Three places hold values made with that key:
 * <ul>
 * <li>clients.id_number_encrypted (every client);</li>
 * <li>import_rows.id_number_encrypted (previews not yet confirmed or expired;
 * cleared when they are);</li>
 * <li>consultant_login_invites.password_sealed (temporary passwords waiting to
 * be emailed; cleared once sent).</li>
 * </ul>
 *
 * Safe to run again and alongside normal use: each value is decrypted with its
 * old key and re-encrypted in small batches, each in its own transaction, and
 * written only if it has not changed in between. A client's version goes up, so
 * a client edit that was open during the run gets the usual "someone else
 * changed it first" instead of writing the old value back. A value that cannot
 * be decrypted (its key is missing, or the bytes are damaged) is left as it is,
 * counted as failed and logged by table and row id; never with its contents.
 *
 * The lookup key is not rotated here: every stored hash depends on it.
 */
@Service
public class IdNumberKeyRotation {

    private static final Logger log = LoggerFactory.getLogger(IdNumberKeyRotation.class);
    static final int BATCH_SIZE = 200;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IdNumberProtector protector;
    private final AuditLogWriter audit;

    public IdNumberKeyRotation(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            IdNumberProtector protector, AuditLogWriter audit) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.protector = protector;
        this.audit = audit;
    }

    /**
     * What is still stored with an older key.
     *
     * @param oldKeyVersions the older versions still in use: each needs its key
     *                       configured until it is gone
     * @param remaining      per place: clients, importRows, loginInvites
     * @param totalRemaining 0 means the previous key can be removed
     */
    public record Status(int currentKeyVersion, List<Integer> oldKeyVersions, Map<String, Long> remaining,
            long totalRemaining) {
    }

    /**
     * One run.
     *
     * @param reEncrypted    per place, how many values now use the current key
     * @param failed         values that could not be decrypted (left as they are)
     * @param totalRemaining what still uses an older key after the run (failed
     *                       ones, and any that changed while it ran)
     */
    public record Result(int currentKeyVersion, Map<String, Integer> reEncrypted, int failed, long totalRemaining) {
    }

    /** One column that holds values made with the encryption key. */
    private record Place(String name, String table, String column, boolean bumpVersion,
            UnaryOperator<byte[]> reEncrypt) {
    }

    private record Stored(long id, byte[] value) {
    }

    private record Batch(int reEncrypted, int failed) {
    }

    /** The table and column names are fixed here, never user input. */
    private List<Place> places() {
        return List.of(
                new Place("clients", "clients", "id_number_encrypted", true,
                        stored -> protector.encrypt(protector.decrypt(stored))),
                new Place("importRows", "import_rows", "id_number_encrypted", false,
                        stored -> protector.encrypt(protector.decrypt(stored))),
                new Place("loginInvites", "consultant_login_invites", "password_sealed", false,
                        stored -> protector.sealText(SealedPasswords.PURPOSE,
                                protector.openText(SealedPasswords.PURPOSE, stored))));
    }

    private static String oldValues(Place place) {
        return place.column() + " is not null and octet_length(" + place.column() + ") > 0 and get_byte("
                + place.column() + ", 0) <> ?";
    }

    public Status status() {
        int current = protector.currentKeyVersion();
        Map<String, Long> remaining = new LinkedHashMap<>();
        Set<Integer> oldVersions = new TreeSet<>();
        long total = 0;
        for (Place place : places()) {
            long count = jdbc.queryForObject(
                    "select count(*) from " + place.table() + " where " + oldValues(place), Long.class, current);
            remaining.put(place.name(), count);
            total += count;
            if (count > 0) {
                oldVersions.addAll(jdbc.queryForList("select distinct get_byte(" + place.column() + ", 0) from "
                        + place.table() + " where " + oldValues(place), Integer.class, current));
            }
        }
        return new Status(current, new ArrayList<>(oldVersions), remaining, total);
    }

    /**
     * Re-encrypts everything with an older key version. Audited as
     * ID_NUMBERS_REENCRYPTED when it changed or failed anything; the actor is the
     * admin who asked, or nobody for the background job.
     */
    public Result run() {
        int current = protector.currentKeyVersion();
        Map<String, Integer> reEncrypted = new LinkedHashMap<>();
        int failed = 0;
        for (Place place : places()) {
            int done = 0;
            long lastId = 0;
            while (true) {
                List<Stored> batch = jdbc.query("select id, " + place.column() + " from " + place.table()
                        + " where " + oldValues(place) + " and id > ? order by id limit ?",
                        (rs, n) -> new Stored(rs.getLong(1), rs.getBytes(2)), current, lastId, BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }
                Batch outcome = transactions.execute(status -> reEncrypt(place, batch));
                done += outcome.reEncrypted();
                failed += outcome.failed();
                lastId = batch.get(batch.size() - 1).id();
            }
            reEncrypted.put(place.name(), done);
        }

        long remaining = status().totalRemaining();
        int total = reEncrypted.values().stream().mapToInt(Integer::intValue).sum();
        if (total > 0 || failed > 0) {
            audit.record("ID_NUMBERS_REENCRYPTED", null, null, meta("keyVersion", current, "clients",
                    reEncrypted.get("clients"), "importRows", reEncrypted.get("importRows"), "loginInvites",
                    reEncrypted.get("loginInvites"), "failed", failed, "remaining", remaining));
            log.info("Re-encrypted {} value(s) with ID-number key version {}; {} failed, {} still on an older key",
                    total, current, failed, remaining);
        }
        return new Result(current, reEncrypted, failed, remaining);
    }

    private Batch reEncrypt(Place place, List<Stored> batch) {
        String update = "update " + place.table() + " set " + place.column() + " = ?"
                + (place.bumpVersion() ? ", version = version + 1" : "")
                + " where id = ? and " + place.column() + " = ?";
        int reEncrypted = 0;
        int failed = 0;
        for (Stored stored : batch) {
            byte[] fresh;
            try {
                fresh = place.reEncrypt().apply(stored.value());
            } catch (IllegalStateException e) {
                // The protector's messages never contain the value.
                log.warn("Could not re-encrypt {} row {}: {}", place.table(), stored.id(), e.getMessage());
                failed++;
                continue;
            }
            // 0 rows: it changed meanwhile (corrected, sent, purged); the next run looks
            // again.
            reEncrypted += jdbc.update(update, fresh, stored.id(), stored.value());
        }
        return new Batch(reEncrypted, failed);
    }
}
