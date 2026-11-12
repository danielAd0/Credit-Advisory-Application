package za.co.mafori.creditadvisory.common.idnumber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hashing and encryption of ID numbers. Fresh random keys per test; made-up
 * numbers.
 */
class IdNumberProtectorTest {

    private static final SaIdNumber ID = SaIdNumber.parse("9001014800089");
    private static final SaIdNumber OTHER_ID = SaIdNumber.parse("8506150123089");

    private final byte[] lookupKey = randomKey();
    private final byte[] encryptionKey = randomKey();
    private final IdNumberProtector protector = new IdNumberProtector(lookupKey, 1, Map.of(1, encryptionKey));

    @Test
    void protectGivesWhatTheClientsTableExpects() {
        ProtectedIdNumber stored = protector.protect(ID);

        assertThat(stored.lookupHash()).hasSize(32); // ck_clients_id_hash_length
        assertThat(stored.encrypted().length).isGreaterThanOrEqualTo(29); // ck_clients_id_encrypted_length
        assertThat(stored.last4()).isEqualTo("0089");
        assertThat(stored.toString()).doesNotContain("900101");
    }

    @Test
    void encryptedBytesDoNotContainTheNumber() {
        String asText = new String(protector.encrypt(ID), StandardCharsets.ISO_8859_1);

        assertThat(asText).doesNotContain("9001014800089").doesNotContain("900101");
    }

    @Test
    void decryptGivesTheNumberBack() {
        assertThat(protector.decrypt(protector.encrypt(ID))).isEqualTo(ID);
    }

    @Test
    void sameNumberAlwaysGivesTheSameHashButADifferentCiphertext() {
        assertThat(protector.lookupHash(ID)).isEqualTo(protector.lookupHash(SaIdNumber.parse("900101 4800 089")));
        assertThat(protector.lookupHash(ID)).isNotEqualTo(protector.lookupHash(OTHER_ID));
        assertThat(protector.encrypt(ID)).isNotEqualTo(protector.encrypt(ID)); // fresh nonce every time
    }

    @Test
    void aDifferentLookupKeyGivesADifferentHash() {
        IdNumberProtector otherKeys = new IdNumberProtector(randomKey(), 1, Map.of(1, encryptionKey));

        assertThat(otherKeys.lookupHash(ID)).isNotEqualTo(protector.lookupHash(ID));
    }

    @Test
    void tamperedBytesAreRejected() {
        byte[] stored = protector.encrypt(ID);
        stored[stored.length - 1] ^= 1;

        assertThatThrownBy(() -> protector.decrypt(stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("9001014800089");
    }

    @Test
    void theWrongEncryptionKeyCannotDecrypt() {
        IdNumberProtector wrongKey = new IdNumberProtector(lookupKey, 1, Map.of(1, randomKey()));

        assertThatThrownBy(() -> wrongKey.decrypt(protector.encrypt(ID))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void afterKeyRotationOldDataStillDecryptsAndHashesStayTheSame() {
        byte[] oldCiphertext = protector.encrypt(ID);
        IdNumberProtector rotated = new IdNumberProtector(lookupKey, 2, Map.of(1, encryptionKey, 2, randomKey()));

        assertThat(rotated.decrypt(oldCiphertext)).isEqualTo(ID);
        assertThat(rotated.needsReEncryption(oldCiphertext)).isTrue();
        assertThat(rotated.needsReEncryption(rotated.encrypt(ID))).isFalse();
        assertThat(rotated.lookupHash(ID)).isEqualTo(protector.lookupHash(ID));
    }

    @Test
    void sealedTextOpensOnlyForTheSamePurpose() {
        byte[] sealed = protector.sealText("temp-password", "Tmp-Pa55word&<x>");

        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("Pa55word");
        assertThat(protector.openText("temp-password", sealed)).isEqualTo("Tmp-Pa55word&<x>");
        assertThat(protector.sealText("temp-password", "same")).as("fresh nonce every time")
                .isNotEqualTo(protector.sealText("temp-password", "same"));
        assertThatThrownBy(() -> protector.openText("another-purpose", sealed))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> protector.decrypt(sealed)).as("never readable as an ID number")
                .isInstanceOf(IllegalStateException.class);
        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> protector.openText("temp-password", tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void badKeysAreRefused() {
        assertThatThrownBy(() -> new IdNumberProtector(new byte[16], 1, Map.of(1, encryptionKey)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdNumberProtector(lookupKey, 1, Map.of(1, lookupKey)))
                .hasMessageContaining("must be different");
        assertThatThrownBy(() -> new IdNumberProtector(lookupKey, 2, Map.of(1, encryptionKey)))
                .hasMessageContaining("current version");
    }

    private static byte[] randomKey() {
        byte[] key = new byte[IdNumberProtector.KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
