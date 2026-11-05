package za.co.mafori.creditadvisory.client.importing;

import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;

/**
 * One client as an import file describes them - the input to
 * ClientImportSupport.classify.
 *
 * @param idNumber  already validated by the parser; null if the row had none
 * @param firstName as written in the file
 * @param lastName  as written in the file
 * @param email     null when the file has no email column or the cell is empty
 *                  (an empty cell never clears a stored email)
 * @param phone     the same as email; any format PhoneNumbers accepts
 */
public record ImportedClient(SaIdNumber idNumber, String firstName, String lastName, String email, String phone) {

    /** The weekly register has no email or phone columns. */
    public static ImportedClient fromRegister(SaIdNumber idNumber, String firstName, String lastName) {
        return new ImportedClient(idNumber, firstName, lastName, null, null);
    }

    /** Never prints the full ID number (SaIdNumber.toString is masked). */
    @Override
    public String toString() {
        return "ImportedClient[" + idNumber + "]";
    }
}
