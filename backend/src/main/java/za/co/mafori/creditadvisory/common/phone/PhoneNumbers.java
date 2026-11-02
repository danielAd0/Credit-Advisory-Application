package za.co.mafori.creditadvisory.common.phone;

/**
 * Normalises phone numbers to international format, e.g. "082 123 4567" becomes
 * "+27821234567". Shared by the client endpoints and the Excel import, so the
 * same
 * number is always stored the same way. The result matches the clients table
 * check
 * {@code ^\+[1-9][0-9]{7,14}$}.
 *
 * <ul>
 * <li>Spaces, hyphens, dots and brackets are ignored.</li>
 * <li>SA local: 10 digits starting with 0 (082 123 4567) becomes +27 and the
 * last 9 digits.</li>
 * <li>SA without plus: 11 digits starting with 27 (27821234567).</li>
 * <li>International: + or 00 followed by 8 to 15 digits. A +27 number must have
 * exactly 9 digits after the 27.</li>
 * </ul>
 */
public final class PhoneNumbers {

    /**
     * The text is not a phone number we can store. The message never contains the
     * number.
     */
    public static class InvalidPhoneNumberException extends RuntimeException {
        public InvalidPhoneNumberException() {
            super("Not a valid phone number. Use a South African number such as 082 123 4567, "
                    + "or an international number starting with +.");
        }
    }

    private PhoneNumbers() {
    }

    /**
     * @return the international form, or null if the input is null or blank (no
     *         phone)
     * @throws InvalidPhoneNumberException if there is text but it is not a phone
     *                                     number
     */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String compact = raw.replaceAll("[\\s.()\\-]", "");
        if (compact.startsWith("00")) {
            compact = "+" + compact.substring(2);
        }

        String international;
        if (compact.startsWith("+")) {
            international = compact;
        } else if (compact.matches("0\\d{9}")) {
            international = "+27" + compact.substring(1);
        } else if (compact.matches("27\\d{9}")) {
            international = "+" + compact;
        } else {
            throw new InvalidPhoneNumberException();
        }

        if (!international.matches("\\+[1-9]\\d{7,14}")) {
            throw new InvalidPhoneNumberException();
        }
        if (international.startsWith("+27") && international.length() != 12) {
            throw new InvalidPhoneNumberException();
        }
        return international;
    }
}
