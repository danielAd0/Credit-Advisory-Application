package za.co.mafori.creditadvisory.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything the system holds about one client, for a POPIA access request
 * (section 23): what is stored, where it came from, and who looked at it or
 * changed it. Answer of POST /api/clients/{id}/data-export
 * (docs/api-auth-consultants-clients.md, section 4).
 *
 * Contains the full ID number. Never log it or keep a copy on the server.
 */
public record ClientDataExport(
        OffsetDateTime exportedAt,
        String exportedBy,
        Details client,
        List<Assignment> assignmentHistory,
        List<Sale> sales,
        List<CreditCase> creditCases,
        List<ImportRow> importRows,
        List<Access> accessLog) {

    /** A consultant as staff know them. */
    public record Consultant(String code, String name) {
    }

    public record Details(
            long id,
            String firstName,
            String lastName,
            String idNumber,
            String email,
            String phone,
            String workPhone,
            String homePhone,
            String employer,
            String branch,
            String fileNumber,
            String fileId,
            String paymentMethod,
            Consultant currentConsultant,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Source createdByImport) {
    }

    /** A weekly spreadsheet the data came from. */
    public record Source(long importId, String fileName, LocalDate weekStarting) {
    }

    public record Assignment(
            OffsetDateTime changedAt,
            String changeType,
            Consultant from,
            Consultant to,
            String reason,
            String changedBy) {
    }

    public record Sale(
            long id,
            LocalDate transactionDate,
            Consultant consultant,
            boolean typedIn,
            boolean replaced,
            Source importedFrom,
            List<Line> lines,
            BigDecimal quotationAmount,
            BigDecimal bankedAmount,
            String employer,
            String structure,
            String paymentTerm,
            BigDecimal amountToClient,
            BigDecimal thirdPartySettlement,
            String primaryStructureBudgetList,
            BigDecimal primaryStructureLoanAmount,
            String secondaryStructureBudgetList,
            BigDecimal secondaryStructureLoanAmount,
            List<Extra> otherColumns,
            OffsetDateTime createdAt) {
    }

    public record Line(String product, BigDecimal amount) {
    }

    /** A spreadsheet column the app has no field for, kept as it was. */
    public record Extra(String column, String text, BigDecimal number) {
    }

    public record CreditCase(
            long id,
            String title,
            String status,
            OffsetDateTime openedAt,
            OffsetDateTime closedAt,
            String createdBy,
            List<Note> notes) {
    }

    public record Note(OffsetDateTime createdAt, String author, String note) {
    }

    /** A row of a weekly spreadsheet that was matched to this client. */
    public record ImportRow(Source importedFrom, String sheet, int rowNumber, String action, String outcome) {
    }

    /** One audit entry about this client: who did what, when. */
    public record Access(OffsetDateTime at, String action, String by) {
    }
}
