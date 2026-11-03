package za.co.mafori.creditadvisory.creditcase;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/cases/{id}/notes. */
public record AddCaseNoteRequest(@NotBlank @Size(max = 4000) String note) {

    /** Never print the note: it can hold personal and financial details. */
    @Override
    public String toString() {
        return "AddCaseNoteRequest[note=****]";
    }
}
