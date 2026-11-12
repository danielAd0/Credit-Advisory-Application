package za.co.mafori.creditadvisory.imports.register;

public final class IssueCodes {

    public static final String SHEET_MISSING_DAY = "sheet:missing-day";
    public static final String SHEET_DUPLICATE_DAY = "sheet:duplicate-day";
    public static final String SHEET_HIDDEN_DAY = "sheet:hidden-day";
    public static final String SHEET_UNEXPECTED_DATA = "sheet:unexpected-data";
    public static final String SHEET_NO_HEADER_ROW = "sheet:no-header-row";
    public static final String SHEET_MISSING_HEADER = "sheet:missing-header";
    public static final String SHEET_DUPLICATE_COLUMN = "sheet:duplicate-column";
    public static final String SHEET_UNKNOWN_COLUMN = "sheet:unknown-column";
    public static final String SHEET_INACTIVE_PRODUCT = "sheet:inactive-product";
    public static final String SHEET_QUOTATION_RULE_DIFFERS = "sheet:quotation-rule-differs";
    public static final String SHEET_BANKED_RULE_DIFFERS = "sheet:banked-rule-differs";
    public static final String SHEET_EMPTY = "sheet:empty";

    public static final String ROW_MISSING_NAME = "row:missing-name";
    public static final String ROW_VALUE_TOO_LONG = "row:value-too-long";
    public static final String ROW_MISSING_ID = "row:missing-id-number";
    public static final String ROW_INVALID_ID = "row:invalid-id-number";
    public static final String ROW_ID_LEADING_ZERO = "row:id-leading-zero-restored";
    public static final String ROW_REPEAT_CLIENT = "row:repeat-client";
    public static final String ROW_INVALID_AMOUNT = "row:invalid-amount";
    public static final String ROW_NEGATIVE_AMOUNT = "row:negative-amount";
    public static final String ROW_AMOUNT_WAS_TEXT = "row:amount-was-text";
    public static final String ROW_FORMULA_ERROR = "row:formula-error";
    public static final String ROW_FORMULA_ERROR_IGNORED = "row:formula-error-ignored";
    public static final String ROW_INVALID_DATE = "row:invalid-date";
    public static final String ROW_DATE_OUTSIDE_DAY = "row:date-outside-day";
    public static final String ROW_NO_CONSULTANT = "row:no-consultant";
    public static final String ROW_INVALID_CONSULTANT_EMAIL = "row:invalid-consultant-email";
    public static final String ROW_EMAIL_WITHOUT_CONSULTANT = "row:email-without-consultant";
    public static final String ROW_NO_CLIENT = "row:no-client";
    public static final String ROW_DUPLICATE_PRODUCT_COLUMN = "row:duplicate-product-column";
    public static final String ROW_EXTRA_LOOKS_LIKE_ID = "row:extra-looks-like-id";

    private IssueCodes() {
    }
}
