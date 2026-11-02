package za.co.mafori.creditadvisory.common.idnumber;

/**
 * The three forms of an ID number that may be stored (clients table, V2): never
 * the
 * plain number.
 *
 * @param lookupHash keyed HMAC-SHA256, 32 bytes - unique index, finds
 *                   duplicates
 * @param encrypted  AES-256-GCM ciphertext - decrypted only for authorised
 *                   views
 * @param last4      last four digits - for the masked display
 */
public record ProtectedIdNumber(byte[] lookupHash, byte[] encrypted, String last4) {

    public ProtectedIdNumber {
        lookupHash = lookupHash.clone();
        encrypted = encrypted.clone();
    }

    @Override
    public byte[] lookupHash() {
        return lookupHash.clone();
    }

    @Override
    public byte[] encrypted() {
        return encrypted.clone();
    }

    /** Never prints the bytes. */
    @Override
    public String toString() {
        return "ProtectedIdNumber[" + SaIdNumber.mask(last4) + "]";
    }
}
