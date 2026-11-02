package za.co.mafori.creditadvisory.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/clients/lookup. In the body, never the URL, so it is not
 * logged.
 */
public record LookupClientRequest(@NotBlank @Size(max = 30) String idNumber) {

    @Override
    public String toString() {
        return "LookupClientRequest[idNumber=****]";
    }
}
