package za.co.mafori.creditadvisory.creditcase;

import java.time.Instant;
import java.util.List;
import za.co.mafori.creditadvisory.creditcase.CaseListItem.ClientRef;
import za.co.mafori.creditadvisory.creditcase.CaseListItem.UserRef;

/**
 * The single Case object (contract section 9): the list item plus all notes,
 * newest first.
 */
public record CaseDetail(
        long id,
        ClientRef client,
        String title,
        CaseStatus status,
        Instant openedAt,
        Instant closedAt,
        UserRef createdBy,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<CaseNoteResponse> notes) {

    /** Must be called inside a transaction (the links are lazy). */
    public static CaseDetail from(CreditCase creditCase, List<CaseNote> notes) {
        return new CaseDetail(creditCase.getId(), ClientRef.from(creditCase.getClient()),
                creditCase.getTitle(), creditCase.getStatus(), creditCase.getOpenedAt(),
                creditCase.getClosedAt(), UserRef.from(creditCase.getCreatedBy()), creditCase.getVersion(),
                creditCase.getCreatedAt(), creditCase.getUpdatedAt(),
                notes.stream().map(CaseNoteResponse::from).toList());
    }
}
