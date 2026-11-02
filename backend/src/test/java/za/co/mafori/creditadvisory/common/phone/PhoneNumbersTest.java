package za.co.mafori.creditadvisory.common.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Phone normalisation. All numbers are made up. */
class PhoneNumbersTest {

    @Test
    void southAfricanNumbersBecomePlus27() {
        assertThat(PhoneNumbers.normalise("082 123 4567")).isEqualTo("+27821234567");
        assertThat(PhoneNumbers.normalise("0821234567")).isEqualTo("+27821234567");
        assertThat(PhoneNumbers.normalise("(082) 123-4567")).isEqualTo("+27821234567");
        assertThat(PhoneNumbers.normalise("27821234567")).isEqualTo("+27821234567");
        assertThat(PhoneNumbers.normalise("+27 82 123 4567")).isEqualTo("+27821234567");
        assertThat(PhoneNumbers.normalise("0027821234567")).isEqualTo("+27821234567");
    }

    @Test
    void internationalNumbersAreKept() {
        assertThat(PhoneNumbers.normalise("+44 7911 123456")).isEqualTo("+447911123456");
        assertThat(PhoneNumbers.normalise("00447911123456")).isEqualTo("+447911123456");
    }

    @Test
    void emptyMeansNoPhone() {
        assertThat(PhoneNumbers.normalise(null)).isNull();
        assertThat(PhoneNumbers.normalise("   ")).isNull();
    }

    @Test
    void rejectsThingsThatAreNotPhoneNumbers() {
        for (String bad : new String[] { "12345", "082123456", "abc", "+0123456789", "+2782123", "+27 82 123 45678" }) {
            assertThatThrownBy(() -> PhoneNumbers.normalise(bad))
                    .as(bad)
                    .isInstanceOf(PhoneNumbers.InvalidPhoneNumberException.class)
                    .hasMessageNotContaining(bad);
        }
    }
}
