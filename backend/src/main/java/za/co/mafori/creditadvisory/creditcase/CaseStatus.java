package za.co.mafori.creditadvisory.creditcase;

/**
 * Where a credit case is. Must match ck_credit_cases_status in V12.
 *
 * A first set until the business defines the real stages (SRS open question
 * 63).
 * New stages can be added later; existing values must never be renamed.
 */
public enum CaseStatus {
    OPEN,
    IN_PROGRESS,
    CLOSED;

    public boolean isClosed() {
        return this == CLOSED;
    }
}
