package za.co.mafori.creditadvisory.client;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.common.idnumber.IdNumberProtector;
import za.co.mafori.creditadvisory.common.idnumber.InvalidIdNumberException;
import za.co.mafori.creditadvisory.common.idnumber.ProtectedIdNumber;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;
import za.co.mafori.creditadvisory.common.phone.PhoneNumbers;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.consultant.ConsultantRepository;
import za.co.mafori.creditadvisory.employer.Employer;
import za.co.mafori.creditadvisory.employer.EmployerRepository;
import za.co.mafori.creditadvisory.productquotation.ProductQuotation;
import za.co.mafori.creditadvisory.productquotation.ProductQuotationRepository;
import za.co.mafori.creditadvisory.transfer.ClientAssignmentHistory;
import za.co.mafori.creditadvisory.transfer.ClientAssignmentHistoryRepository;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * Creating, viewing, finding and editing single clients (contract section 4,
 * FR-03 / FR-04).
 *
 * <p>
 * Record-level access: a CONSULTANT may only touch clients assigned to their
 * own
 * consultant record. Any other client is treated as if it did not exist (the
 * controller answers 404), so its existence is never revealed.
 *
 * <p>
 * View, lookup and update return Optional instead of throwing "not found", so
 * the
 * audit entry for a failed lookup is still COMMITTED - throwing inside the
 * transaction would roll it back.
 */
@Service
public class ClientService {

    private final ClientRepository clients;
    private final ConsultantRepository consultants;
    private final UserAccountRepository users;
    private final ClientAssignmentHistoryRepository history;
    private final EmployerRepository employers;
    private final ProductQuotationRepository productQuotations;
    private final ClientLatestSales latestSales;
    private final IdNumberProtector protector;
    private final AuditLogWriter audit;

    public ClientService(ClientRepository clients, ConsultantRepository consultants, UserAccountRepository users,
            ClientAssignmentHistoryRepository history, EmployerRepository employers,
            ProductQuotationRepository productQuotations, ClientLatestSales latestSales, IdNumberProtector protector,
            AuditLogWriter audit) {
        this.clients = clients;
        this.consultants = consultants;
        this.users = users;
        this.history = history;
        this.employers = employers;
        this.productQuotations = productQuotations;
        this.latestSales = latestSales;
        this.protector = protector;
        this.audit = audit;
    }

    // ---------- create ----------

    @Transactional
    public ClientDetail create(AppUserPrincipal caller, CreateClientRequest request) {
        SaIdNumber idNumber = parseIdNumber(request.idNumber());
        String email = blankToNull(request.email());
        String phone = normalisePhone(request.phone());
        String workPhone = normalisePhone(request.workPhone());
        String homePhone = normalisePhone(request.homePhone());
        Employer employer = chooseEmployer(request.employerId(), null);
        ProductQuotation productQuotation = chooseProductQuotation(request.productQuotationId(), null);
        Consultant consultant = chooseConsultant(caller, request.consultantId());

        ProtectedIdNumber stored = protector.protect(idNumber);
        if (clients.existsByIdNumberHash(stored.lookupHash())) {
            // Deliberately says nothing about the existing client or who has it.
            throw ApiException.conflict("client:duplicate-id-number", "Client already exists",
                    "A client with this ID number already exists.");
        }

        Client client = new Client(stored.lookupHash(), stored.encrypted(), stored.last4(),
                request.firstName(), request.lastName());
        client.updateContactDetails(email, phone, workPhone, homePhone);
        client.updateWorkDetails(employer, productQuotation, request.fileNumber(), request.fileId(),
                request.paymentMethod());
        client.assignTo(consultant);
        clients.saveAndFlush(client);

        if (consultant != null) {
            history.save(ClientAssignmentHistory.clientCreated(client, consultant,
                    users.getReferenceById(caller.userId())));
        }
        audit.record("CLIENT_CREATED", "CLIENT", client.getId(),
                meta("consultantId", consultant == null ? null : consultant.getId()));
        // A new client has no sales yet.
        return ClientDetail.from(client, idNumber, null);
    }

    // ---------- view ----------

    /**
     * Empty if the client does not exist or the caller may not see it.
     *
     * @param includeIdNumber true only when the user asked to see the full ID
     *                        number: then it is decrypted and the view is
     *                        audited (CLIENT_VIEWED). Otherwise the ID number is
     *                        left out and nothing is audited, because nothing
     *                        sensitive was shown.
     */
    @Transactional
    public Optional<ClientDetail> view(AppUserPrincipal caller, long id, boolean includeIdNumber) {
        Optional<Client> visible = findVisible(caller, id);
        if (!includeIdNumber) {
            return visible.map(client -> ClientDetail.from(client, null, latestSales.forClient(client.getId())));
        }
        visible.ifPresent(client -> audit.record("CLIENT_VIEWED", "CLIENT", client.getId(), meta()));
        return visible.map(client -> ClientDetail.from(client, protector.decrypt(client.getIdNumberEncrypted()),
                latestSales.forClient(client.getId())));
    }

    // ---------- lookup by ID number ----------

    /**
     * Empty if no client has this ID number or the caller may not see it. Always
     * audited, with the result - but never the number that was searched for.
     */
    @Transactional
    public Optional<ClientListItem> lookup(AppUserPrincipal caller, String rawIdNumber) {
        SaIdNumber idNumber = parseIdNumber(rawIdNumber);
        Optional<Client> found = clients.findByIdNumberHash(protector.lookupHash(idNumber));

        if (found.isEmpty()) {
            audit.record("CLIENT_LOOKUP", null, null, meta("result", "not-found"));
            return Optional.empty();
        }
        Client client = found.get();
        boolean visible = isVisibleTo(client, caller);
        // "not-visible" lets an admin spot consultants searching for other people's
        // clients.
        audit.record("CLIENT_LOOKUP", "CLIENT", client.getId(),
                meta("result", visible ? "found" : "not-visible"));
        return visible ? Optional.of(ClientListItem.from(client, latestSales.forClient(client.getId())))
                : Optional.empty();
    }

    // ---------- update ----------

    /** Empty if the client does not exist or the caller may not see it. */
    @Transactional
    public Optional<ClientDetail> update(AppUserPrincipal caller, long id, UpdateClientRequest request) {
        Optional<Client> visible = findVisible(caller, id);
        if (visible.isEmpty()) {
            return Optional.empty();
        }
        Client client = visible.get();
        if (client.getVersion() != request.version()) {
            throw ApiException.staleVersion();
        }

        String firstName = request.firstName().strip();
        String lastName = request.lastName().strip();
        String email = blankToNull(request.email());
        String phone = normalisePhone(request.phone());
        String workPhone = normalisePhone(request.workPhone());
        String homePhone = normalisePhone(request.homePhone());
        Employer employer = chooseEmployer(request.employerId(), client.getEmployer());
        ProductQuotation productQuotation = chooseProductQuotation(request.productQuotationId(),
                client.getProductQuotation());

        // Which fields changed - the names only, never the values (contract section 1).
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
        if (!Objects.equals(workPhone, client.getWorkPhone())) {
            changed.add("workPhone");
        }
        if (!Objects.equals(homePhone, client.getHomePhone())) {
            changed.add("homePhone");
        }
        if (!sameEntry(employer, client.getEmployer() == null ? null : client.getEmployer().getId())) {
            changed.add("employer");
        }
        if (!sameEntry(productQuotation,
                client.getProductQuotation() == null ? null : client.getProductQuotation().getId())) {
            changed.add("productQuotation");
        }
        if (!Objects.equals(request.fileNumber(), client.getFileNumber())) {
            changed.add("fileNumber");
        }
        if (!Objects.equals(request.fileId(), client.getFileId())) {
            changed.add("fileId");
        }
        if (request.paymentMethod() != client.getPaymentMethod()) {
            changed.add("paymentMethod");
        }

        client.rename(firstName, lastName);
        client.updateContactDetails(email, phone, workPhone, homePhone);
        client.updateWorkDetails(employer, productQuotation, request.fileNumber(), request.fileId(),
                request.paymentMethod());
        clients.flush();
        if (!changed.isEmpty()) {
            audit.record("CLIENT_UPDATED", "CLIENT", client.getId(), meta("fields", changed));
        }
        // Without the full ID number: an edit is not a request to see it.
        return Optional.of(ClientDetail.from(client, null, latestSales.forClient(client.getId())));
    }

    // ---------- correct the ID number ----------

    /**
     * MANAGER / ADMIN only (checked in the controller). Empty if the client does
     * not exist.
     *
     * <p>
     * The audit entry records only the last four digits of the old and the new
     * number, never a full number.
     */
    @Transactional
    public Optional<ClientDetail> correctIdNumber(AppUserPrincipal caller, long id,
            CorrectIdNumberRequest request) {
        Optional<Client> visible = findVisible(caller, id);
        if (visible.isEmpty()) {
            return Optional.empty();
        }
        Client client = visible.get();
        if (client.getVersion() != request.version()) {
            throw ApiException.staleVersion();
        }

        SaIdNumber newIdNumber = parseIdNumber(request.idNumber());
        ProtectedIdNumber stored = protector.protect(newIdNumber);
        if (Arrays.equals(stored.lookupHash(), client.getIdNumberHash())) {
            throw ApiException.unprocessable("client:id-number-unchanged", "ID number unchanged",
                    "This is already the client's ID number.");
        }
        if (clients.existsByIdNumberHash(stored.lookupHash())) {
            // Same answer as create: says nothing about the other client or who has it.
            throw ApiException.conflict("client:duplicate-id-number", "Client already exists",
                    "A client with this ID number already exists.");
        }

        String fromLast4 = client.getIdNumberLast4();
        String reason = request.reason().strip();
        client.correctIdNumber(stored.lookupHash(), stored.encrypted(), stored.last4());
        clients.flush();

        audit.record("CLIENT_ID_NUMBER_CORRECTED", "CLIENT", client.getId(),
                meta("fromLast4", fromLast4, "toLast4", stored.last4(), "reason", reason));
        // Without the full ID number, like an edit.
        return Optional.of(ClientDetail.from(client, null, latestSales.forClient(client.getId())));
    }

    // ---------- rules ----------

    private Optional<Client> findVisible(AppUserPrincipal caller, long id) {
        return clients.findWithCurrentConsultantById(id).filter(client -> isVisibleTo(client, caller));
    }

    /** MANAGER / ADMIN see every client; a CONSULTANT only their own. */
    public static boolean isVisibleTo(Client client, AppUserPrincipal caller) {
        return ClientQueryService.seesAllClients(caller)
                || (caller.consultantId() != null && client.isAssignedTo(caller.consultantId()));
    }

    /**
     * Contract section 4, "Assignment rules" for POST /api/clients. Null means
     * unassigned.
     */
    private Consultant chooseConsultant(AppUserPrincipal caller, Long requestedId) {
        if (ClientQueryService.seesAllClients(caller)) {
            return requestedId == null ? null : loadActiveConsultant(requestedId);
        }
        if (caller.consultantId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "client:no-consultant-record", "Not allowed",
                    "Your login is not linked to a consultant record, so you cannot create clients.");
        }
        if (requestedId != null && !requestedId.equals(caller.consultantId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "client:cannot-assign-to-others", "Not allowed",
                    "Consultants can only create clients for themselves.");
        }
        return loadActiveConsultant(caller.consultantId());
    }

    private Consultant loadActiveConsultant(long consultantId) {
        Consultant consultant = consultants.findById(consultantId)
                .orElseThrow(() -> ApiException.notFound("consultant:not-found",
                        "Consultant " + consultantId + " does not exist."));
        if (!consultant.getStatus().isActive()) {
            throw ApiException.unprocessable("client:consultant-not-active", "Consultant not active",
                    "Clients can only be assigned to an ACTIVE consultant.");
        }
        return consultant;
    }

    /**
     * Null means no employer. A newly chosen employer must be active; the one the
     * client already has may stay even if it was deactivated since.
     */
    private Employer chooseEmployer(Long requestedId, Employer current) {
        if (requestedId == null) {
            return null;
        }
        if (current != null && requestedId.equals(current.getId())) {
            return current;
        }
        Employer employer = employers.findById(requestedId).orElseThrow(() -> ApiException.notFound(
                "employer:not-found", "Employer " + requestedId + " does not exist."));
        if (!Boolean.TRUE.equals(employer.isActive())) {
            throw ApiException.unprocessable("client:employer-not-active", "Employer not active",
                    "This employer has been deactivated. Choose another one.");
        }
        return employer;
    }

    /** Same rules as chooseEmployer, for the branch list. */
    private ProductQuotation chooseProductQuotation(Long requestedId, ProductQuotation current) {
        if (requestedId == null) {
            return null;
        }
        if (current != null && requestedId.equals(current.getId())) {
            return current;
        }
        ProductQuotation quotation = productQuotations.findById(requestedId).orElseThrow(() -> ApiException.notFound(
                "product-quotation:not-found", "Branch " + requestedId + " does not exist."));
        if (!quotation.isActive()) {
            throw ApiException.unprocessable("client:product-quotation-not-active", "Branch not active",
                    "This branch has been deactivated. Choose another one.");
        }
        return quotation;
    }

    /** True if both are "none", or both are the same entry. */
    private static boolean sameEntry(Employer chosen, Long currentId) {
        return Objects.equals(chosen == null ? null : chosen.getId(), currentId);
    }

    private static boolean sameEntry(ProductQuotation chosen, Long currentId) {
        return Objects.equals(chosen == null ? null : chosen.getId(), currentId);
    }

    private static SaIdNumber parseIdNumber(String raw) {
        try {
            return SaIdNumber.parse(raw);
        } catch (InvalidIdNumberException e) {
            // The reason only - never the number.
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation:invalid-id-number", "Invalid ID number",
                    "Not a valid SA ID number: " + e.reason().message() + ".");
        }
    }

    private static String normalisePhone(String raw) {
        try {
            return PhoneNumbers.normalise(raw);
        } catch (PhoneNumbers.InvalidPhoneNumberException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation:invalid-phone", "Invalid phone number",
                    e.getMessage());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
