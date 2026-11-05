package za.co.mafori.creditadvisory.imports.weekly;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.time.Instant;

public final class WeeklyImportViews {

    private WeeklyImportViews() {
        // no instances
    }

    public record ImportSummary(long id, String fileName, String fileDigest, String state, LocalDate weekStarting,
            LocalDate weekEnding, String uploadedBy, Instant createdAt, Instant expiresAt, Instant committedAt,
            int totalRows, int newClients, int existingClients, int repeatRows, int rejectedRows, int appliedRows,
            long errors, long warnings, long infos, BigDecimal quotationTotal, BigDecimal bankedTotal,
            List<DaySummary> days, List<UnmappedConsultant> unmappedConsultants, Long weekImportedByJobId,
            boolean replaceRequired, boolean canConfirm, List<String> blockers) {
    }

    public record DaySummary(String sheetName, int dayOfWeek, LocalDate date, int rows, int rejected,
            BigDecimal quotation, BigDecimal banked) {
    }

    public record UnmappedConsultant(String nameInSheet, int rows) {
    }

    public record StagedRowView(long id, String sheetName, int rowNumber, LocalDate date, String action,
            String outcome, String idNumberMasked, String firstName, String lastName, String consultantName,
            String employerName, BigDecimal quotation, BigDecimal banked, Map<String, BigDecimal> products,
            int warnings) {
    }

    public record IssueView(String sheetName, Integer rowNumber, String field, String code, String severity,
            String message) {
    }

    public record ImportListItem(long id, String fileName, String state, LocalDate weekStarting, String uploadedBy,
            Instant createdAt, Instant committedAt, int totalRows, int rejectedRows, int appliedRows,
            Long replacesImportJobId) {
    }

    public record ConfirmResult(long importJobId, long reportingWeekId, LocalDate weekStarting, int transactions,
            int productLines, int extraValues, int newClients, int clientsAssigned, int employersCreated,
            int structuresCreated, int paymentTermsCreated, int staleRows, Long replacedImportJobId,
            int supersededTransactions) {
    }

    public record ConfirmRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "^[0-9a-f]{64}$") String fileDigest,
            @jakarta.validation.constraints.Size(max = 500) String replaceReason) {
    }

    public record ConsultantMappingRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String nameInSheet,
            @jakarta.validation.constraints.NotNull Long consultantId) {
    }

    public record WeekGuess(LocalDate weekStarting) {
    }

}
