package za.co.mafori.creditadvisory.auth.mfa;

/**
 * Answer of POST /api/auth/mfa/setup.
 *
 * @param secret     the secret in base32, for typing in by hand if the QR code
 *                   cannot be scanned
 * @param otpauthUri what the QR code must contain
 */
public record MfaSetupResponse(String secret, String otpauthUri) {
}
