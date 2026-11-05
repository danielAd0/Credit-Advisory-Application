package za.co.mafori.creditadvisory.client.importing;

/**
 * What ClientImportSupport.apply did with one row.
 *
 * @param outcome  see Outcome
 * @param clientId the client the row now belongs to; null for SKIPPED and STALE
 * @param assigned true when a NEW client was assigned to the row's consultant
 * @param note     a message safe to show (no ID number), or null
 */
public record ClientImportResult(Outcome outcome, Long clientId, boolean assigned, String note) {

    public enum Outcome {
        /** A new client was created. */
        CREATED,
        /** An existing client's names or contact details were changed. */
        UPDATED,
        /** An existing client was found and nothing needed changing. */
        LINKED,
        /** A CONFLICT row the manager chose to skip. Nothing was changed. */
        SKIPPED,
        /** The client changed after the preview. Nothing was changed; preview again. */
        STALE;

        /** CREATED, UPDATED and LINKED give a client the row can be attached to. */
        public boolean hasClient() {
            return this == CREATED || this == UPDATED || this == LINKED;
        }
    }

    static ClientImportResult of(Outcome outcome, Long clientId) {
        return new ClientImportResult(outcome, clientId, false, null);
    }

    static ClientImportResult stale(String note) {
        return new ClientImportResult(Outcome.STALE, null, false, note);
    }
}
