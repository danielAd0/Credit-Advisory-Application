package za.co.mafori.creditadvisory.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Encrypting the authenticator secret, and the recovery codes. */
class MfaSecretCipherTest {

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private final MfaSecretCipher cipher = new MfaSecretCipher(randomBytes(32));

    @Test
    void encryptsAndDecryptsWithAFreshNonceEveryTime() {
        byte[] secret = randomBytes(20);

        byte[] first = cipher.encrypt(secret);
        byte[] second = cipher.encrypt(secret);

        assertThat(first).hasSize(49).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(secret);
        assertThat(cipher.decrypt(second)).isEqualTo(secret);
    }

    @Test
    void aChangedByteOrAnotherKeyIsRefused() {
        byte[] stored = cipher.encrypt(randomBytes(20));
        byte[] changed = stored.clone();
        changed[20] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(changed)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MfaSecretCipher(randomBytes(32)).decrypt(stored))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutAKeyItOnlyFailsWhenUsed() {
        MfaSecretCipher noKey = new MfaSecretCipher(null);

        assertThatThrownBy(() -> noKey.encrypt(randomBytes(20)))
                .hasMessageContaining("app.mfa.encryption-key is not set");
        assertThatThrownBy(() -> new MfaSecretCipher(randomBytes(16)))
                .hasMessageContaining("32 bytes");
    }

    @Test
    void recoveryCodesAreTenEasyToTypeCodesHashedTheSameHoweverTyped() {
        List<String> codes = RecoveryCodes.generate();

        assertThat(codes).hasSize(10).doesNotHaveDuplicates()
                .allMatch(code -> code.matches("[A-HJKMNP-Z2-9]{5}-[A-HJKMNP-Z2-9]{5}"));
        String code = codes.get(0);
        assertThat(RecoveryCodes.hash(code)).matches("[0-9a-f]{64}")
                .isEqualTo(RecoveryCodes.hash(code.toLowerCase().replace("-", " ")))
                .isNotEqualTo(RecoveryCodes.hash(codes.get(1)));
    }
}
