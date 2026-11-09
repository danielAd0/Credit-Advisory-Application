package za.co.mafori.creditadvisory.employer;

public record EmployerMergeResponse(long mergedEmployerId, String mergedEmployerName, EmployerResponse target,
        int clientsMoved, int transactionsMoved, int aliasesMoved, boolean nameKeptAsAlias) {

}
