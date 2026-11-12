package za.co.mafori.creditadvisory.imports.weekly;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.time.Instant;

public final class WeeklyImportViews {

        private WeeklyImportViews() {

        }

        public record ImportSummary(long id, String fileName, String fileDigest, String state, LocalDate weekStarting,
                        LocalDate weekEnding, String uploadedBy, Instant createdAt, Instant expiresAt,
                        Instant committedAt,
                        int totalRows, int newClients, int existingClients, int repeatRows, int rejectedRows,
                        int appliedRows,
                        long errors, long warnings, long infos, BigDecimal quotationTotal, BigDecimal bankedTotal,
                        List<DaySummary> days, List<UnmappedConsultant> unmappedConsultants, Long weekImportedByJobId,
                        boolean replaceRequired, boolean canConfirm, List<String> blockers,
                        String reconciliationStatus, int reportDifferences,
                        int nameDifferences, int typedInSales, ConsultantRegistration consultantRegistration,
                        AssignmentPreview assignment) {
        }

        /**
         * What confirm will do with the consultants in the sheet
         * (docs/consultant-onboarding.md). Only filled while the import is PREVIEWED.
         *
         * @param namesInSheet       distinct consultant names in the sheet
         * @param matchedByName      names that match a consultant by name or alias (no email used)
         * @param matchedByEmail     names matched through their email
         * @param toRegister         new consultants confirm will create
         * @param emailsToAdd        existing consultants without an email who get the sheet's email
         * @param conflicts          names that cannot be matched safely: they block confirm
         */
        public record ConsultantRegistration(int namesInSheet, int matchedByName, int matchedByEmail,
                        List<ConsultantToRegister> toRegister, List<ConsultantEmailToAdd> emailsToAdd,
                        List<ConsultantConflict> conflicts) {

                public static final ConsultantRegistration NONE = new ConsultantRegistration(0, 0, 0, List.of(),
                                List.of(), List.of());
        }

        public record ConsultantToRegister(String fullName, String email, List<String> otherSpellings, int rows,
                        List<SimilarConsultant> similarConsultants) {
        }

        public record ConsultantEmailToAdd(long consultantId, String consultantCode, String fullName, String email,
                        int rows) {
        }

        public record ConsultantConflict(String nameInSheet, String email, String reason, int rows) {
        }

        /**
         * New clients in the sheet and who they will be given to on confirm.
         * Existing clients keep their consultant.
         *
         * @param newClientRows              rows that create a client
         * @param willBeAssigned             to an active consultant (existing or registered by this import)
         * @param noConsultant               the row has no consultant: the client stays unassigned
         * @param consultantNotActive        the consultant is inactive or suspended: unassigned
         * @param waitingForConsultant       the name is unmapped or in conflict: resolve it first
         * @param existingClientsWithOtherConsultant existing clients assigned to someone else (not changed)
         */
        public record AssignmentPreview(int newClientRows, int willBeAssigned, int noConsultant,
                        int consultantNotActive, int waitingForConsultant, long existingClientsWithOtherConsultant) {

                public static final AssignmentPreview NONE = new AssignmentPreview(0, 0, 0, 0, 0, 0);
        }

        public record DaySummary(String sheetName, int dayOfWeek, LocalDate date, int rows, int rejected,
                        BigDecimal quotation, BigDecimal banked) {
        }

        /**
         * A consultant name in the sheet that matches nobody.
         * {@code similarConsultants}: up to 3
         * existing consultants that look like it (a typo?), to map it to instead of
         * creating one.
         */
        public record UnmappedConsultant(String nameInSheet, int rows, List<SimilarConsultant> similarConsultants) {
        }

        public record SimilarConsultant(long consultantId, String consultantCode, String fullName, String status) {
        }

        /** POST /api/imports/{id}/new-consultants. */
        public record NewConsultantsRequest(
                        // 1-100 names; each is checked by the service (blank, not in the import,
                        // already mapped)
                        @jakarta.validation.constraints.NotEmpty @jakarta.validation.constraints.Size(max = 100) List<String> namesInSheet) {
        }

        public record NewConsultant(long consultantId, String consultantCode, String fullName) {
        }

        public record NewConsultantsResult(List<NewConsultant> created, ImportSummary summary) {
        }

        public record StagedRowView(long id, String sheetName, int rowNumber, LocalDate date, String action,
                        String outcome, String idNumberMasked, String firstName, String lastName, String consultantName,
                        String employerName, BigDecimal quotation, BigDecimal banked, Map<String, BigDecimal> products,
                        int warnings) {
        }

        public record IssueFile(String fileName, byte[] content) {
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
                        int supersededTransactions, int replacedTypedInSales, int consultantsRegistered,
                        int consultantEmailsAdded, int consultantAliasesAdded, int newClientsUnassigned) {
        }

        public record ConfirmRequest(
                        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "^[0-9a-f]{64}$") String fileDigest,
                        @jakarta.validation.constraints.Size(max = 500) String replaceReason,
                        Boolean acceptNameDifferences) {
        }

        public record ConsultantMappingRequest(
                        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String nameInSheet,
                        @jakarta.validation.constraints.NotNull Long consultantId) {
        }

        public record WeekGuess(LocalDate weekStarting) {
        }

}
