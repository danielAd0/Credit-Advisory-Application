package za.co.mafori.creditadvisory.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/auth/reauthenticate: the signed-in user's own password. */
public record ReauthenticateRequest(@NotBlank @Size(max = 200) String password) {

    /** Never print the password, for example in a log line. */
    @Override
    public String toString() {
        return "ReauthenticateRequest[password=****]";
    }
}
