package za.co.mafori.creditadvisory.user;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import za.co.mafori.creditadvisory.common.web.ApiException;

/**
 * Rules every new password must pass (admin-set temporary passwords and user
 * changes).
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    /**
     * BCrypt only uses the first 72 bytes; Spring's encoder rejects anything
     * longer.
     */
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void check(String password, String email) {
        if (password.strip().length() < MIN_LENGTH) {
            throw weak("Password must be at least " + MIN_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw weak("Password is too long. Use at most " + MAX_BYTES + " plain characters.");
        }
        if (email != null && password.strip().equalsIgnoreCase(email.strip())) {
            throw weak("Password must not be the same as the email address.");
        }
    }

    private static ApiException weak(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation:weak-password", "Password not allowed", detail);
    }
}
