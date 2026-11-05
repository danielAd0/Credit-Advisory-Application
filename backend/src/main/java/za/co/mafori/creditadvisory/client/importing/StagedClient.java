package za.co.mafori.creditadvisory.client.importing;

import za.co.mafori.creditadvisory.common.idnumber.ProtectedIdNumber;

/**
 * An import row as it was stored at preview time (import_rows), read back at
 * confirm - the input to ClientImportSupport.apply. Build it from the
 * import_rows columns, or with ClientImportPreview.staged() when there is no
 * round trip through the database.
 *
 * @param action                what preview decided (import_rows.action)
 * @param idNumber              new ProtectedIdNumber(id_number_hash,
 *                              id_number_encrypted, id_number_last4)
 * @param existingClientId      import_rows.existing_client_id; null for NEW
 * @param existingClientVersion import_rows.existing_client_version; null for
 *                              NEW
 */
public record StagedClient(
        ClientImportAction action,
        ProtectedIdNumber idNumber,
        String firstName,
        String lastName,
        String email,
        String phone,
        Long existingClientId,
        Long existingClientVersion) {

    public StagedClient {
        if (action == null || action == ClientImportAction.INVALID) {
            throw new IllegalArgumentException("INVALID rows are never applied");
        }
        if (idNumber == null || firstName == null || lastName == null) {
            throw new IllegalArgumentException("A staged client needs an ID number and both names");
        }
        boolean isNew = action == ClientImportAction.NEW;
        if (isNew != (existingClientId == null) || isNew != (existingClientVersion == null)) {
            throw new IllegalArgumentException(
                    "NEW rows have no existing client; every other action needs its id and version");
        }
    }

    /** Never prints the ID number or the names. */
    @Override
    public String toString() {
        return "StagedClient[" + action + ", " + idNumber + ", existingClientId=" + existingClientId + "]";
    }
}
