package za.co.mafori.creditadvisory.imports.clientlist;

/**
 * The client list columns the import understands. Which header means which
 * field is data: client_list_header_aliases (V29).
 */
public enum ClientListField {
    NAME("Name"),
    SURNAME("Surname"),
    ID_NUMBER("ID number"),
    CONSULTANT("Consultant"),
    CONSULTANT_EMAIL("Consultant email"),
    CLIENT_EMAIL("Email"),
    CELLPHONE("Cellphone"),
    WORK_PHONE("Work phone"),
    HOME_PHONE("Home phone"),
    EMPLOYER("Employer"),
    PRODUCT_QUOTATION("Product quotation"),
    FILE_NUMBER("File number"),
    FILE_ID("File ID");

    private final String label;

    ClientListField(String label) {
        this.label = label;
    }

    /** How the field is named in messages and on the screen. */
    public String label() {
        return label;
    }
}
