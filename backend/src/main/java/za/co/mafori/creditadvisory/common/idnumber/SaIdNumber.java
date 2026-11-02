package za.co.mafori.creditadvisory.common.idnumber;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A validated South African ID number (FR-04).
 *
 * <p>
 * Layout: {@code YYMMDD SSSS C A Z} - date of birth, sequence (gender),
 * citizenship
 * (0 = SA citizen, 1 = permanent resident, 2 = refugee), a digit with no
 * meaning
 * today, and a Luhn check digit.
 *
 * <p>
 * The full number is only available through {@link #value()}.
 * {@link #toString()}
 * returns the MASKED form, so printing or logging one of these by accident
 * never
 * leaks the number.
 */
public final class SaIdNumber {

    private final String digits;

    private SaIdNumber(String digits) {
        this.digits = digits;
    }

    /**
     * Validates and normalises user or spreadsheet input. Spaces and hyphens are
     * ignored ("800101 5009 087" is fine).
     *
     * @throws InvalidIdNumberException with the reason; the message never contains
     *                                  the number
     */
    public static SaIdNumber parse(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{13}")) {
            throw new InvalidIdNumberException(InvalidIdNumberException.Reason.NOT_13_DIGITS);
        }
        if (!hasRealDateOfBirth(digits)) {
            throw new InvalidIdNumberException(InvalidIdNumberException.Reason.INVALID_DATE_OF_BIRTH);
        }
        char citizenship = digits.charAt(10);
        if (citizenship != '0' && citizenship != '1' && citizenship != '2') {
            throw new InvalidIdNumberException(InvalidIdNumberException.Reason.INVALID_CITIZENSHIP);
        }
        if (!passesLuhn(digits)) {
            throw new InvalidIdNumberException(InvalidIdNumberException.Reason.INVALID_CHECK_DIGIT);
        }
        return new SaIdNumber(digits);
    }

    /** True if {@link #parse(String)} would accept this text. */
    public static boolean isValid(String raw) {
        try {
            parse(raw);
            return true;
        } catch (InvalidIdNumberException e) {
            return false;
        }
    }

    /**
     * The full 13 digits. Only for encrypting, hashing, or an authorised, audited
     * view.
     */
    public String value() {
        return digits;
    }

    public String last4() {
        return digits.substring(9);
    }

    /** "*********9087" - the form used in lists, reports and logs. */
    public String masked() {
        return mask(last4());
    }

    /**
     * The masked form for given last four digits (the database stores only these).
     */
    public static String mask(String last4) {
        return "*********" + last4;
    }

    /** Masked on purpose: see the class comment. */
    @Override
    public String toString() {
        return masked();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SaIdNumber id && id.digits.equals(digits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(digits);
    }

    /**
     * The century is not stored, so YYMMDD counts as real if it is a valid date in
     * either 19YY or 20YY (e.g. 000229 is valid because 2000 was a leap year).
     */
    private static boolean hasRealDateOfBirth(String digits) {
        int yy = Integer.parseInt(digits.substring(0, 2));
        int mm = Integer.parseInt(digits.substring(2, 4));
        int dd = Integer.parseInt(digits.substring(4, 6));
        return isDate(1900 + yy, mm, dd) || isDate(2000 + yy, mm, dd);
    }

    private static boolean isDate(int year, int month, int day) {
        try {
            LocalDate.of(year, month, day);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    /** Luhn: from the right, double every second digit; the total must end in 0. */
    private static boolean passesLuhn(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }
}
