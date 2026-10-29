package za.co.mafori.creditadvisory.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/auth/login. */
public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 200) String password) {

    /** Never print the password, even by accident in a log line */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
