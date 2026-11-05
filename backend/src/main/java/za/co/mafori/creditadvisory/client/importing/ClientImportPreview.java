package za.co.mafori.creditadvisory.client.importing;

import java.util.List;
import za.co.mafori.creditadvisory.common.idnumber.ProtectedIdNumber;

/**
 * What ClientImportSupport.classify found for one row. Store these values in
 * import_rows; at confirm, read them back into a StagedClient.
 *
 * @param action                NEW, UPDATE, UNCHANGED, CONFLICT or INVALID
 * @param idNumber              the protected ID number to store (hash,
 *                              ciphertext, last four) - never the plain
 *                              number. Null for INVALID
 * @param firstName             cleaned (trimmed, single spaces); null for
 *                              INVALID
 * @param lastName              cleaned; null for INVALID
 * @param email                 cleaned and lower-cased, or null
 * @param phone                 normalised to +27..., or null
 * @param existingClientId      the client this row is about; null for NEW and
 *                              INVALID
 * @param existingClientVersion that client's version now, so confirm can tell
 *                              whether it changed in between
 * @param differingFields       field names (never values) that differ from the
 *                              stored client: firstName, lastName, email, phone
 * @param invalidReason         for INVALID: a message safe to show, without the
 *                              ID number
 */
public record ClientImportPreview(
        ClientImportAction action,
        ProtectedIdNumber idNumber,
        String firstName,
        String lastName,
        String email,
        String phone,
        Long existingClientId,
        Long existingClientVersion,
        List<String> differingFields,
        String invalidReason) {

    public ClientImportPreview {
        differingFields = List.copyOf(differingFields);
    }

    static ClientImportPreview invalid(String reason) {
        return new ClientImportPreview(ClientImportAction.INVALID, null, null, null, null, null, null, null,
                List.of(), reason);
    }

    /** True for NEW: import_rows.creates_client. */
    public boolean createsClient() {
        return action == ClientImportAction.NEW;
    }

    /** The same values as a StagedClient, for confirming without storing first. */
    public StagedClient staged() {
        return new StagedClient(action, idNumber, firstName, lastName, email, phone, existingClientId,
                existingClientVersion);
    }

    /** Never prints the ID number or the names. */
    @Override
    public String toString() {
        return "ClientImportPreview[" + action + ", " + idNumber + ", existingClientId=" + existingClientId + "]";
    }
}
