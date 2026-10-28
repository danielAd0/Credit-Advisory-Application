package za.co.mafori.creditadvisory.consultant;

/**
 * Lifecycle of a consultant (FR-06). Must match ck_consultatnts_status in V1
 */
public enum ConsultantStatus {
    ACTIVE,
    INACTIVE,
    SUSPENDED;

    /** Only ACTIVE consultants may sign in or receive new clients (FR-17, FR-18) */
    public boolean isActive() {
        return this == ACTIVE;
    }
}
