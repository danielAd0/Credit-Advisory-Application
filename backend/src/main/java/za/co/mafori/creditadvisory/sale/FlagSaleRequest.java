package za.co.mafori.creditadvisory.sale;

/**
 * Body of POST /api/transactions/{id}/flag: what the consultant should correct.
 * Checked in SaleEditService (not blank, at most 1000 characters after
 * trimming), so a bad note is a 422 like the other sale rules.
 */
public record FlagSaleRequest(String note) {

    /** Never print the note: it may name the client. */
    @Override
    public String toString() {
        return "FlagSaleRequest[note=****]";
    }
}
