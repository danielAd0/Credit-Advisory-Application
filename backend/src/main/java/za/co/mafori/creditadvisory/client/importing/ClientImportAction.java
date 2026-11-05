package za.co.mafori.creditadvisory.client.importing;

/**
 * What an import row would do to the client it describes. The names match the
 * import_rows.action values in V3 (DUPLICATE, a repeat inside one file, is
 * decided by the import itself, not here).
 */
public enum ClientImportAction {
    /** No client has this ID number yet: one will be created. */
    NEW,
    /**
     * The client exists with the same names; the file adds or changes email /
     * phone.
     */
    UPDATE,
    /** The client exists and the file changes nothing. */
    UNCHANGED,
    /**
     * The client exists but the file has DIFFERENT names. Never applied
     * automatically: it may be a typing mistake in the ID number pointing at
     * someone else. A manager decides (ConflictDecision).
     */
    CONFLICT,
    /** The row cannot be imported (reason in invalidReason). Never applied. */
    INVALID
}
