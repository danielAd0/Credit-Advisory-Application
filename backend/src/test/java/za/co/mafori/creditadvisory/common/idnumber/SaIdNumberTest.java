package za.co.mafori.creditadvisory.common.idnumber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.common.idnumber.InvalidIdNumberException.Reason;

/**
 * SA ID number validation (FR-04). Every number here is MADE UP; it only has a
 * correct check digit. Never use a real person's ID number in a test.
 */
class SaIdNumberTest {

    private static final String VALID = "9001014800089";

    @Test
    void acceptsValidNumbers() {
        assertThat(SaIdNumber.isValid(VALID)).isTrue();
        assertThat(SaIdNumber.isValid("8506150123089")).isTrue();
        assertThat(SaIdNumber.isValid("9203045111086")).isTrue();
    }

    @Test
    void ignoresSpacesAndHyphens() {
        assertThat(SaIdNumber.parse("900101 4800 089").value()).isEqualTo(VALID);
        assertThat(SaIdNumber.parse(" 900101-4800-089 ").value()).isEqualTo(VALID);
    }

    @Test
    void rejectsWrongLengthOrLetters() {
        assertReason("900101480008", Reason.NOT_13_DIGITS);
        assertReason("90010148000890", Reason.NOT_13_DIGITS);
        assertReason("90010148000A9", Reason.NOT_13_DIGITS);
        assertReason("", Reason.NOT_13_DIGITS);
        assertReason(null, Reason.NOT_13_DIGITS);
    }

    @Test
    void rejectsImpossibleDatesOfBirth() {
        assertReason("9913325009087", Reason.INVALID_DATE_OF_BIRTH); // month 13, day 32
        assertReason("1234567890123", Reason.INVALID_DATE_OF_BIRTH); // month 34
        assertReason("9902295000087", Reason.INVALID_DATE_OF_BIRTH); // 29 Feb in a non-leap year
    }

    @Test
    void acceptsTheLeapDayOf2000() {
        assertThat(SaIdNumber.isValid("0002295000083")).isTrue();
    }

    @Test
    void rejectsAnUnknownCitizenshipDigit() {
        assertReason("9001014800389", Reason.INVALID_CITIZENSHIP); // 11th digit is 3
    }

    @Test
    void rejectsAWrongCheckDigit() {
        assertReason("9001014800088", Reason.INVALID_CHECK_DIGIT);
        assertReason("9001014800080", Reason.INVALID_CHECK_DIGIT);
    }

    @Test
    void errorMessagesNeverContainTheNumber() {
        assertThatThrownBy(() -> SaIdNumber.parse("9001014800088"))
                .hasMessageNotContaining("9001014800088")
                .hasMessageNotContaining("900101");
    }

    @Test
    void printingItShowsOnlyTheMaskedForm() {
        SaIdNumber id = SaIdNumber.parse(VALID);

        assertThat(id.toString()).isEqualTo("*********0089").doesNotContain("900101");
        assertThat(id.masked()).isEqualTo("*********0089");
        assertThat(id.last4()).isEqualTo("0089");
    }

    @Test
    void sameNumberIsEqualHoweverItWasTyped() {
        assertThat(SaIdNumber.parse("900101 4800 089")).isEqualTo(SaIdNumber.parse(VALID));
    }

    private static void assertReason(String raw, Reason reason) {
        assertThatThrownBy(() -> SaIdNumber.parse(raw))
                .isInstanceOf(InvalidIdNumberException.class)
                .extracting(e -> ((InvalidIdNumberException) e).reason())
                .isEqualTo(reason);
    }
}
