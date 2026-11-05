package za.co.mafori.creditadvisory.client.importing;

/** What a manager chose for a CONFLICT row. Ignored for every other action. */
public enum ConflictDecision {
    /** Do not touch the client and do not link the row to them (the default). */
    SKIP,
    /**
     * It is the same person: link the row to the client, keep the names already
     * stored.
     */
    KEEP_EXISTING_NAMES,
    /**
     * It is the same person and the file is right: take the names from the file.
     */
    USE_IMPORTED_NAMES
}
