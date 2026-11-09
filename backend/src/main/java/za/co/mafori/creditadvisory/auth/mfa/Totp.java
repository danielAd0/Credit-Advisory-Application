package za.co.mafori.creditadvisory.auth.mfa;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time codes (TOTP, RFC 6238) - what Google and Microsoft
 * Authenticator show: HMAC-SHA1, a new 6-digit code every 30 seconds. Checked
 * against the RFC's own test values in TotpTest.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;

    /** One step either side: the phone's clock may be up to ~30 seconds off. */
    private static final int ALLOWED_DRIFT_STEPS = 1;

    private Totp() {
    }

    /** The 30-second step a moment falls in. */
    public static long stepAt(Instant moment) {
        return Math.floorDiv(moment.getEpochSecond(), STEP_SECONDS);
    }

    /** The code for one step, with leading zeros ("005924"). */
    public static String code(byte[] secret, long step) {
        return truncate(hmac(secret, step), DIGITS);
    }

    /**
     * The step whose code this is (now, one step before or one after), or empty.
     * Compares in constant time, so timing reveals nothing about the right code.
     */
    public static OptionalLong matchingStep(byte[] secret, String code, Instant now) {
        if (code == null || code.length() != DIGITS || !code.chars().allMatch(Character::isDigit)) {
            return OptionalLong.empty();
        }
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long current = stepAt(now);
        long found = Long.MIN_VALUE;
        for (long step = current - ALLOWED_DRIFT_STEPS; step <= current + ALLOWED_DRIFT_STEPS; step++) {
            byte[] expected = code(secret, step).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(given, expected)) {
                found = step; // keep looping: same time for every outcome
            }
        }
        return found == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(found);
    }

    /** RFC 4226 dynamic truncation. Package-private for the RFC test vectors. */
    static String truncate(byte[] hash, int digits) {
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int modulo = (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", binary % modulo);
    }

    static byte[] hmac(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            return mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is not available", e);
        }
    }
}
