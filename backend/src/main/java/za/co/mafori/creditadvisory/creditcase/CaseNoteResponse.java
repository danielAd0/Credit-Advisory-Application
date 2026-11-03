package za.co.mafori.creditadvisory.creditcase;

import java.time.Instant;
import za.co.mafori.creditadvisory.creditcase.CaseListItem.UserRef;

/** The Note object (contract section 9). */
public record CaseNoteResponse(long id, String note, UserRef author, Instant createdAt) {

    /** Must be called inside a transaction (the author is lazy). */
    public static CaseNoteResponse from(CaseNote note) {
        return new CaseNoteResponse(note.getId(), note.getNote(), UserRef.from(note.getAuthor()),
                note.getCreatedAt());
    }
}
