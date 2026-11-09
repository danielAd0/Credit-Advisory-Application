package za.co.mafori.creditadvisory.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Authenticator codes, checked against the official RFC 6238 test values. */
class TotpTest {

    /** The RFC 6238 appendix B secret for HMAC-SHA1. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesTheRfc6238TestValues() {
        // time (seconds) -> the 8-digit code the RFC lists
        long[][] vectors = {
                { 59L, 94287082L },
                { 1111111109L, 7081804L },
                { 1111111111L, 14050471L },
                { 1234567890L, 89005924L },
                { 2000000000L, 69279037L },
                { 20000000000L, 65353130L } };
        for (long[] vector : vectors) {
            long step = vector[0] / Totp.STEP_SECONDS;
            assertThat(Totp.truncate(Totp.hmac(RFC_SECRET, step), 8))
                    .as("time %d", vector[0]).isEqualTo(String.format("%08d", vector[1]));
        }
        // Authenticator apps show the last 6 digits.
        assertThat(Totp.code(RFC_SECRET, 59L / 30)).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
    }

    @Test
    void acceptsOneStepOfClockDriftEitherWay() {
        Instant now = Instant.ofEpochSecond(1_234_567_890L);
        long step = Totp.stepAt(now);

        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step), now)).hasValue(step);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now)).hasValue(step - 1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now)).hasValue(step + 1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now)).isEmpty();
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step + 2), now)).isEmpty();
    }

    @Test
    void refusesAnythingThatIsNotSixDigits() {
        Instant now = Instant.ofEpochSecond(59);
        assertThat(Totp.matchingStep(RFC_SECRET, "28708", now)).isEmpty();
        assertThat(Totp.matchingStep(RFC_SECRET, "2870821", now)).isEmpty();
        assertThat(Totp.matchingStep(RFC_SECRET, "28708a", now)).isEmpty();
        assertThat(Totp.matchingStep(RFC_SECRET, null, now)).isEmpty();
    }

    @Test
    void base32MatchesTheStandardAndRoundTrips() {
        byte[] hello = { 'H', 'e', 'l', 'l', 'o', '!', (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF };
        assertThat(Base32.encode(hello)).isEqualTo("JBSWY3DPEHPK3PXP");
        // Typed by hand: lower case and spaces are fine.
        assertThat(Base32.decode("jbsw y3dp ehpk 3pxp")).isEqualTo(hello);
        assertThat(Base32.decode(Base32.encode(RFC_SECRET))).isEqualTo(RFC_SECRET);
    }
}
