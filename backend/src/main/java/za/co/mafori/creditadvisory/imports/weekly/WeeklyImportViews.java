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
                        List<DaySummary> days, ConsultantList consultants, Long weekImportedByJobId,
                        boolean replaceRequired, boolean canConfirm, List<String> blockers,
                        String reconciliationStatus, int reportDifferences,
                        int nameDifferences, int typedInSales, long possibleDuplicateRows,
                        ConsultantRegistration consultantRegistration, AssignmentPreview assignment,
                        ImportResult result) {
        }

        /**
         * Every consultant found in the workbook, for the admin to verify before the
         * import can be confirmed (POST /api/imports/{id}/consultants/confirm). No
         * name is matched by hand: each entry says what confirm will do.
         *
         * @param fingerprint SHA-256 of this list; send it back to confirm the list.
         *                    Null when the import is no longer PREVIEWED.
         * @param confirmed   the admin confirmed exactly this list
         */
        public record ConsultantList(List<ConsultantFound> items, int identified, int existing, int toRegister,
                        int conflicts, int withoutEmail, String fingerprint, boolean confirmed,
                        Instant confirmedAt, String confirmedBy) {

                public static ConsultantList none(Instant confirmedAt, String confirmedBy) {
                        return new ConsultantList(List.of(), 0, 0, 0, 0, 0, null, confirmedAt != null, confirmedAt,
                                        confirmedBy);
                }
        }

        /**
         * One consultant of the workbook.
         *
         * @param outcome       EXISTING (matched), EMAIL_ADDED (matched, and the sheet's
         *                      email is added to them), NEW (registered at confirm) or
         *                      CONFLICT (cannot be linked safely: blocks the import)
         * @param matchedBy     EMAIL or NAME for EXISTING / EMAIL_ADDED, else null
         * @param namesInSheet  every spelling of this consultant in the sheet
         * @param email         the email they will have after the import (null: none)
         * @param rows          sales rows of this consultant in the sheet
         * @param newClients    rows that create a client for this consultant
         * @param consultantId  the existing consultant (null for NEW and CONFLICT)
         * @param attention     NO_EMAIL (no login can be made until an email is
         *                      added), NOT_ACTIVE (new clients stay unassigned),
         *                      SIMILAR_NAME (a new consultant whose name looks like
         *                      another one), CONFLICT
         * @param reason        why it is a conflict
         */
        public record ConsultantFound(String outcome, String matchedBy, List<String> namesInSheet, String email,
                        int rows, int newClients, Long consultantId, String consultantCode, String consultantName,
                        String consultantStatus, List<String> attention, String reason,
                        List<SimilarConsultant> similarConsultants) {
        }

        /** POST /api/imports/{id}/consultants/confirm. */
        public record ConfirmConsultantsRequest(
                        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "^[0-9a-f]{64}$") String fingerprint) {
        }

        /**
         * The import summary of a confirmed import (V26), also returned by confirm.
         *
         * @param consultantsMatched   existing consultants the sheet's names matched
         * @param clientsLinked        distinct clients that got a sale
         * @param transactionsLinked   sales linked to a client and a consultant
         * @param transactionsUnattributed sales linked to a client only (no
         *                             consultant in the sheet)
         * @param rowsNeedingAttention rejected rows + sales without a consultant +
         *                             possible duplicate rows
         */
        public record ImportResult(int consultantsIdentified, int consultantsRegistered, int consultantsMatched,
                        int clientsLinked, int transactionsLinked, int transactionsUnattributed,
                        int rowsNeedingAttention) {
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
         * @param waitingForConsultant       the name is in conflict: fix the workbook first
         * @param existingClientsWithOtherConsultant existing clients assigned to someone else (not changed)
         */
        public record AssignmentPreview(int newClientRows, int willBeAssigned, int noConsultant,
                        int consultantNotActive, int waitingForConsultant, long existingClientsWithOtherConsultant) {

                public static final AssignmentPreview NONE = new AssignmentPreview(0, 0, 0, 0, 0, 0);
        }

        public record DaySummary(String sheetName, int dayOfWeek, LocalDate date, int rows, int rejected,
                        BigDecimal quotation, BigDecimal banked) {
        }

        public record SimilarConsultant(long consultantId, String consultantCode, String fullName, String status) {
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
                        int consultantEmailsAdded, int consultantAliasesAdded, int newClientsUnassigned,
                        int consultantsIdentified, int consultantsMatched, int clientsLinked,
                        int transactionsLinked, int transactionsUnattributed, int rejectedRows,
                        long possibleDuplicateRows, int rowsNeedingAttention) {
        }

        public record ConfirmRequest(
                        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "^[0-9a-f]{64}$") String fileDigest,
                        @jakarta.validation.constraints.Size(max = 500) String replaceReason,
                        Boolean acceptNameDifferences) {
        }

        public record WeekGuess(LocalDate weekStarting) {
        }

}
