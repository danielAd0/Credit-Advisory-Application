package za.co.mafori.creditadvisory.client.importing;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.client.Client;
import za.co.mafori.creditadvisory.client.ClientRepository;
import za.co.mafori.creditadvisory.client.importing.ClientImportResult.Outcome;
import za.co.mafori.creditadvisory.common.idnumber.IdNumberProtector;
import za.co.mafori.creditadvisory.common.idnumber.ProtectedIdNumber;
import za.co.mafori.creditadvisory.common.phone.PhoneNumbers;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.consultant.ConsultantRepository;
import za.co.mafori.creditadvisory.transfer.ClientAssignmentHistory;
import za.co.mafori.creditadvisory.transfer.ClientAssignmentHistoryRepository;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * The client half of every import (FR-08 to FR-11). The import code parses the
 * file and stores rows; this class is the ONLY place an import touches the
 * clients table, so the ID-number rules (validation, keyed hash, encryption),
 * assignment history and audit stay in one place.
 *
 * <ol>
 * <li>Preview: {@link #classify} each row. It writes nothing. Store the
 * returned
 * values in import_rows.</li>
 * <li>Confirm: read each row back as a {@link StagedClient} and call
 * {@link #apply}, inside the confirm transaction.</li>
 * </ol>
 *
 * <p>
 * Rules:
 * <ul>
 * <li>A client is found by the keyed hash of the ID number, never by the plain
 * number.</li>
 * <li>Names are compared ignoring case and extra spaces ("THANDI " = "Thandi").
 * Different names are a CONFLICT and never change anything without a manager's
 * decision.</li>
 * <li>An empty email or phone cell never clears a stored value.</li>
 * <li>An import only assigns a consultant when it CREATES the client (V9:
 * assigns_consultant needs creates_client). It never moves an existing client;
 * that is what transfers are for.</li>
 * <li>If the client changed after the preview, the row is STALE and nothing is
 * changed.</li>
 * </ul>
 *
 * <p>
 * This class does not check who is calling: the import endpoints must only be
 * open to MANAGER and ADMIN.
 */
@Service
public class ClientImportSupport {

    private static final int MAX_NAME = 100;
    private static final int MAX_EMAIL = 254;
    /** The same rule as ck_clients_email_format in V2. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final ClientRepository clients;
    private final ConsultantRepository consultants;
    private final UserAccountRepository users;
    private final ClientAssignmentHistoryRepository history;
    private final IdNumberProtector protector;
    private final AuditLogWriter audit;

    public ClientImportSupport(ClientRepository clients, ConsultantRepository consultants,
            UserAccountRepository users, ClientAssignmentHistoryRepository history, IdNumberProtector protector,
            AuditLogWriter audit) {
        this.clients = clients;
        this.consultants = consultants;
        this.users = users;
        this.history = history;
        this.protector = protector;
        this.audit = audit;
    }

    // ---------- preview ----------

    /** Decides what a row would do. Writes nothing and audits nothing. */
    @Transactional(readOnly = true)
    public ClientImportPreview classify(ImportedClient row) {
        if (row.idNumber() == null) {
            return ClientImportPreview.invalid("The ID number is missing.");
        }
        String firstName = cleanName(row.firstName());
        String lastName = cleanName(row.lastName());
        if (firstName == null || lastName == null) {
            return ClientImportPreview.invalid("First name and last name are both required.");
        }
        if (firstName.length() > MAX_NAME || lastName.length() > MAX_NAME) {
            return ClientImportPreview.invalid("A name is longer than " + MAX_NAME + " characters.");
        }
        String email = cleanEmail(row.email());
        if (email != null && (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches())) {
            return ClientImportPreview.invalid("The email address is not valid.");
        }
        String phone;
        try {
            phone = PhoneNumbers.normalise(row.phone());
        } catch (PhoneNumbers.InvalidPhoneNumberException e) {
            return ClientImportPreview.invalid(e.getMessage());
        }

        ProtectedIdNumber idNumber = protector.protect(row.idNumber());
        Optional<Client> found = clients.findByIdNumberHash(idNumber.lookupHash());
        if (found.isEmpty()) {
            return new ClientImportPreview(ClientImportAction.NEW, idNumber, firstName, lastName, email, phone,
                    null, null, List.of(), null);
        }

        Client client = found.get();
        List<String> differing = new ArrayList<>();
        if (!sameName(client.getFirstName(), firstName)) {
            differing.add("firstName");
        }
        if (!sameName(client.getLastName(), lastName)) {
            differing.add("lastName");
        }
        boolean namesDiffer = !differing.isEmpty();
        if (email != null && !email.equals(client.getEmail())) {
            differing.add("email");
        }
        if (phone != null && !phone.equals(client.getPhone())) {
            differing.add("phone");
        }

        ClientImportAction action = namesDiffer ? ClientImportAction.CONFLICT
                : differing.isEmpty() ? ClientImportAction.UNCHANGED : ClientImportAction.UPDATE;
        return new ClientImportPreview(action, idNumber, firstName, lastName, email, phone, client.getId(),
                client.getVersion(), differing, null);
    }

    // ---------- confirm ----------

    /**
     * Applies one previewed row. Call it inside the confirm transaction, so a
     * failure rolls back the whole import.
     *
     * @param row          the row as stored at preview
     * @param consultantId for NEW rows: the consultant to assign, or null. Ignored
     *                     for existing clients (an import never moves a client)
     * @param decision     for CONFLICT rows: what the manager chose. Ignored for
     *                     every other action
     * @param importJobId  import_jobs.id (the row must exist), recorded on the
     *                     client, in the history and in the audit
     * @param actingUserId the user confirming the import
     */
    @Transactional
    public ClientImportResult apply(StagedClient row, Long consultantId, ConflictDecision decision,
            long importJobId, long actingUserId) {
        return switch (row.action()) {
            case NEW -> applyNew(row, consultantId, importJobId, actingUserId);
            case UPDATE -> applyToExisting(row, false, importJobId);
            case UNCHANGED -> applyToExisting(row, false, importJobId);
            case CONFLICT -> switch (decision == null ? ConflictDecision.SKIP : decision) {
                case SKIP -> ClientImportResult.of(Outcome.SKIPPED, null);
                case KEEP_EXISTING_NAMES -> linkOnly(row);
                case USE_IMPORTED_NAMES -> applyToExisting(row, true, importJobId);
            };
            case INVALID -> throw new IllegalArgumentException("INVALID rows are never applied");
        };
    }

    private ClientImportResult applyNew(StagedClient row, Long consultantId, long importJobId, long actingUserId) {
        Optional<Client> meanwhile = clients.findByIdNumberHash(row.idNumber().lookupHash());
        if (meanwhile.isPresent()) {
            // Created by an earlier row of this same import (a client who appears twice),
            // or by
            // someone else since the preview. The same names mean the same person.
            Client client = meanwhile.get();
            if (sameName(client.getFirstName(), row.firstName()) && sameName(client.getLastName(), row.lastName())) {
                return ClientImportResult.of(Outcome.LINKED, client.getId());
            }
            return ClientImportResult.stale(
                    "A client with this ID number but different names was created after the preview.");
        }

        Client client = new Client(row.idNumber().lookupHash(), row.idNumber().encrypted(), row.idNumber().last4(),
                row.firstName(), row.lastName());
        client.updateContactDetails(row.email(), row.phone());
        client.markCreatedByImport(importJobId);

        Consultant consultant = consultantId == null ? null
                : consultants.findById(consultantId).filter(c -> c.getStatus().isActive()).orElse(null);
        client.assignTo(consultant);
        clients.saveAndFlush(client);

        if (consultant != null) {
            history.save(ClientAssignmentHistory.imported(client, consultant, importJobId,
                    users.getReferenceById(actingUserId)));
        }
        audit.record("CLIENT_CREATED", "CLIENT", client.getId(),
                meta("consultantId", consultant == null ? null : consultant.getId(),
                        "source", "IMPORT", "importJobId", importJobId));

        String note = consultantId != null && consultant == null
                ? "The consultant does not exist or is not active, so the client was left unassigned."
                : null;
        return new ClientImportResult(Outcome.CREATED, client.getId(), consultant != null, note);
    }

    /**
     * CONFLICT, KEEP_EXISTING_NAMES: attach the row to the client without changing
     * anything.
     */
    private ClientImportResult linkOnly(StagedClient row) {
        return findSameClient(row)
                .map(client -> ClientImportResult.of(Outcome.LINKED, client.getId()))
                .orElseGet(() -> ClientImportResult.stale("The client no longer exists or has another ID number."));
    }

    /**
     * UPDATE, UNCHANGED and CONFLICT with USE_IMPORTED_NAMES. Empty email / phone
     * in the row keep the stored values.
     */
    private ClientImportResult applyToExisting(StagedClient row, boolean takeNames, long importJobId) {
        Optional<Client> found = findSameClient(row);
        if (found.isEmpty()) {
            return ClientImportResult.stale("The client no longer exists or has another ID number.");
        }
        Client client = found.get();

        String firstName = takeNames ? row.firstName() : client.getFirstName();
        String lastName = takeNames ? row.lastName() : client.getLastName();
        String email = row.email() != null ? row.email() : client.getEmail();
        String phone = row.phone() != null ? row.phone() : client.getPhone();

        // Field names only, never values (contract section 1).
        List<String> changed = new ArrayList<>();
        if (!firstName.equals(client.getFirstName())) {
            changed.add("firstName");
        }
        if (!lastName.equals(client.getLastName())) {
            changed.add("lastName");
        }
        if (!Objects.equals(email, client.getEmail())) {
            changed.add("email");
        }
        if (!Objects.equals(phone, client.getPhone())) {
            changed.add("phone");
        }
        if (changed.isEmpty()) {
            // Already as the file says (e.g. an earlier row of this import did it).
            return ClientImportResult.of(Outcome.LINKED, client.getId());
        }
        if (client.getVersion() != row.existingClientVersion()) {
            return ClientImportResult.stale("The client was changed after the preview. Preview the file again.");
        }

        client.rename(firstName, lastName);
        client.updateContactDetails(email, phone);
        clients.flush();
        audit.record("CLIENT_UPDATED", "CLIENT", client.getId(),
                meta("fields", changed, "source", "IMPORT", "importJobId", importJobId));
        return ClientImportResult.of(Outcome.UPDATED, client.getId());
    }

    /** The client the preview saw - only if it still has the same ID number. */
    private Optional<Client> findSameClient(StagedClient row) {
        return clients.findById(row.existingClientId())
                .filter(client -> Arrays.equals(client.getIdNumberHash(), row.idNumber().lookupHash()));
    }

    // ---------- cleaning ----------

    /** Trimmed, inner spaces collapsed; null if empty. */
    private static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.strip().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Trimmed and lower-cased; null if empty. */
    private static String cleanEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.strip().toLowerCase(Locale.ROOT);
    }

    /** "THANDI Mokoena" and "thandi mokoena" are the same name. */
    private static boolean sameName(String stored, String imported) {
        return cleanName(stored).equalsIgnoreCase(cleanName(imported));
    }
}
