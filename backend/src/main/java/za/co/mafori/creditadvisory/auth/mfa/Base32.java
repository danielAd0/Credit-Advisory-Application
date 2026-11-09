package za.co.mafori.creditadvisory.auth.mfa;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Base32 (RFC 4648, no padding): how authenticator apps expect the secret,
 * e.g. JBSWY3DPEHPK3PXP. Only letters A-Z and digits 2-7, so it is easy to type
 * if the QR code cannot be scanned.
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String encode(byte[] data) {
        char[] out = new char[(data.length * 8 + 4) / 5];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out[index++] = ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out[index++] = ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F);
        }
        return new String(out, 0, index);
    }

    /** Ignores spaces, dashes, "=" padding and lower case. */
    public static byte[] decode(String text) {
        String clean = text.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char c : clean.toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Not a base32 character");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}
