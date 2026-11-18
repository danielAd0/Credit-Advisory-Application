package za.co.mafori.creditadvisory.imports.clientlist;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;

/**
 * Request and response shapes of /api/imports/client-list
 * (docs/api-client-list-import.md).
 */
public final class ClientListViews {

    private ClientListViews() {
    }

    /**
     * The preview, and the same import shown again later.
     *
     * @param blockers why confirm is refused now (empty = canConfirm)
     * @param result   what confirm did; null before confirm
     */
    public record ClientListSummary(long id, String state, String fileName, String fileDigest, String sheetName,
            String uploadedBy, Instant createdAt, Instant expiresAt, Instant committedAt, RowCounts rows,
            IssueCounts issues, List<ColumnView> columns, Consultants consultants, Additions additions,
            List<String> newEmployers, List<String> newProductQuotations, List<String> blockers, boolean canConfirm,
            Result result) {
    }

    /**
     * @param newClients rows that become new clients
     * @param update     rows of clients already stored that fill in missing fields
     * @param unchanged  rows of clients already stored with nothing to add
     * @param conflict   ID number stored under another name: not imported
     * @param duplicate  the same ID number again further down the sheet: not
     *                   imported
     * @param invalid    rows with an error: not imported
     */
    public record RowCounts(int total, int newClients, int update, int unchanged, int conflict, int duplicate,
            int invalid) {
    }

    public record IssueCounts(long errors, long warnings, long info) {
    }

    /** @param field null when the column is not imported */
    public record ColumnView(String header, String field) {
    }

    /**
     * Who the consultant names in the list are (the weekly register's rules,
     * ConsultantResolver). Send {@code fingerprint} back with confirm.
     */
    public record Consultants(String fingerprint, List<Matched> matched, List<ToRegister> toRegister,
            List<EmailToAdd> emailsToAdd, List<Conflict> conflicts) {
    }

    public record Matched(long consultantId, String consultantCode, String fullName, String status,
            List<String> namesInSheet, int rows) {
    }

    public record ToRegister(String fullName, String email, List<String> otherSpellings, int rows) {
    }

    public record EmailToAdd(long consultantId, String consultantCode, String fullName, String email) {
    }

    public record Conflict(String nameInSheet, String email, String reason, int rows) {
    }

    /** How many of the rows to import carry each optional field. */
    public record Additions(int debitOrders, int withCellphone, int withWorkPhone, int withHomePhone, int withEmail,
            int withFileNumber, int withFileId, int withEmployer, int withProductQuotation, int withoutConsultant) {
    }

    public record Result(int newClients, int appliedRows, int staleRows, Integer consultantsRegistered,
            Integer clientsLinked) {
    }

    public record ConfirmRequest(
            @NotNull @Pattern(regexp = "^[0-9a-f]{64}$", message = "must be the fileDigest of the preview") String fileDigest,
            @NotNull @Pattern(regexp = "^[0-9a-f]{64}$", message = "must be consultants.fingerprint of the preview") String consultantsFingerprint) {
    }

    /**
     * @param updatedClients existing clients that got at least one missing field
     * @param staleRows      rows that changed meaning since the preview (the client
     *                       was added or renamed meanwhile); skipped
     * @param skippedRows    invalid, duplicate and conflict rows: never imported
     */
    public record ConfirmResult(long importJobId, int newClients, int updatedClients, int unchangedClients,
            int assigned, int newClientsWithoutConsultant, int consultantsRegistered, int consultantEmailsAdded,
            int consultantAliasesAdded, int employersCreated, int productQuotationsCreated, int staleRows,
            int skippedRows) {
    }

    /** @param idNumber masked: only the last four digits */
    public record StagedRowView(int rowNumber, String action, String firstName, String lastName, String idNumber,
            String consultantName, boolean createsClient, boolean assignsConsultant, int warningCount,
            String outcome) {
    }

    public record IssueView(Integer rowNumber, String field, String code, String severity, String message) {
    }

    public record ListItem(long id, String state, String fileName, String sheetName, String uploadedBy,
            int totalRows, int newClients, Instant createdAt, Instant committedAt) {
    }

    public record IssueFile(String fileName, byte[] content) {
    }
}
