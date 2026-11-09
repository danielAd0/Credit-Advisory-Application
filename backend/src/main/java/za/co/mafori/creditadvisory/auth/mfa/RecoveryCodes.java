package za.co.mafori.creditadvisory.auth.mfa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * One-time recovery codes, for when the phone is lost: 10 codes like
 * K7QM2-XW4PD, shown once when MFA is set up. Only their SHA-256 hashes are
 * stored. Each code is 50 random bits, far too many to guess, so a plain hash
 * (no slow password hash) is enough.
 */
public final class RecoveryCodes {

    public static final int COUNT = 10;

    /** No 0/O or 1/I/L, so a code read off paper is typed correctly. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int GROUP = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {
    }

    public static List<String> generate() {
        List<String> codes = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            char[] chars = new char[GROUP * 2 + 1];
            for (int j = 0; j < chars.length; j++) {
                chars[j] = j == GROUP ? '-' : ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
            }
            codes.add(new String(chars));
        }
        return codes;
    }

    /** SHA-256 hex of the code without dashes and spaces, in upper case. */
    public static String hash(String code) {
        String normalised = code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalised.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
