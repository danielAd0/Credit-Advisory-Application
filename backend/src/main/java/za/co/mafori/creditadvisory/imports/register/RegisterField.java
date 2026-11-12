package za.co.mafori.creditadvisory.imports.register;


public enum RegisterField {
    DATE,
    NAME,
    SURNAME,
    ID_NUMBER,
    QUOTATION,
    BANKED,
    AMOUNT_TO_CLIENT,
    THIRD_PARTY_SETTLEMENT,
    PAYMENT_TERM,
    EMPLOYER,
    STRUCTURE,
    CONSULTANT,
    /** Optional. When present it is how the import recognises (or registers) the consultant. */
    CONSULTANT_EMAIL,
    PRIMARY_BUDGET_LIST,
    PRIMARY_LOAN_AMOUNT,
    SECONDARY_BUDGET_LIST,
    SECONDARY_LOAN_AMOUNT;

    /** A day sheet with data rows must have all of these (design 2.1). */
    public static final java.util.Set<RegisterField> REQUIRED =
            java.util.EnumSet.of(NAME, SURNAME, ID_NUMBER, CONSULTANT, BANKED, QUOTATION);
}
