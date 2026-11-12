package za.co.mafori.creditadvisory.onboarding;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;


@Component
public class TemporaryPasswordGenerator {

    public static final int LENGTH = 16;

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!#$%&*+-=?@^_~";
    private static final String ALL = UPPER + LOWER + DIGITS + SYMBOLS;

    private final SecureRandom random = new SecureRandom();

    public String next() {
        char[] password = new char[LENGTH];
        password[0] = pick(UPPER);
        password[1] = pick(LOWER);
        password[2] = pick(DIGITS);
        password[3] = pick(SYMBOLS);
        for (int i = 4; i < LENGTH; i++) {
            password[i] = pick(ALL);
        }
        // Shuffle (Fisher-Yates), so the guaranteed characters are not always first.
        for (int i = LENGTH - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char t = password[i];
            password[i] = password[j];
            password[j] = t;
        }
        return new String(password);
    }

    private char pick(String from) {
        return from.charAt(random.nextInt(from.length()));
    }
}
