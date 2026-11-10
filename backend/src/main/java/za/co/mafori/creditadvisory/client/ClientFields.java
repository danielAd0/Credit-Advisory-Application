package za.co.mafori.creditadvisory.client;

/** Rules shared by the create and update requests. */
final class ClientFields {

    /**
     * File number and File ID: digits only, 1 to 20 of them (the database checks
     * the same: ck_clients_file_number_digits, ck_clients_file_id_digits). The
     * real length is set once the launch client list is loaded.
     */
    static final String FILE_REFERENCE = "^[0-9]{1,20}$";
    static final String FILE_REFERENCE_MESSAGE = "must be digits only, at most 20";

    private ClientFields() {
    }

    static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
