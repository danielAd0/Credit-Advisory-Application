package za.co.mafori.creditadvisory.consultant;

import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.PageResponse;

/**
 * Consultant use cases (contract section 3). Role checks live on the
 * controller.
 */
@Service
public class ConsultantService {

    static final int MAX_PAGE_SIZE = 100;

    private final ConsultantRepository consultants;

    public ConsultantService(ConsultantRepository consultants) {
        this.consultants = consultants;
    }

    @Transactional(readOnly = true)
    public PageResponse<ConsultantResponse> search(String search, ConsultantStatus status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // Sort by name; id as a tie-breaker so paging is stable when two names are
        // equal.
        PageRequest pageable = PageRequest.of(safePage, safeSize, Sort.by("fullName").and(Sort.by("id")));

        Page<Consultant> result = consultants.search(toLikePattern(search), status, pageable);
        return PageResponse.of(result, ConsultantResponse::from);
    }

    @Transactional(readOnly = true)
    public ConsultantResponse get(long id) {
        return ConsultantResponse.from(load(id));
    }

    @Transactional
    public ConsultantResponse create(CreateConsultantRequest request) {
        String code = Consultant.normalizeCode(request.consultantCode());
        if (consultants.existsByConsultantCode(code)) {
            throw ApiException.conflict("consultant:duplicate-code", "Duplicate consultant code",
                    "A consultant with code " + code + " already exists.");
        }
        Consultant saved = consultants.save(new Consultant(code, request.fullName()));
        return ConsultantResponse.from(saved);
    }

    @Transactional
    public ConsultantResponse rename(long id, RenameConsultantRequest request) {
        Consultant consultant = load(id);
        requireVersion(consultant, request.version());
        consultant.rename(request.fullName());
        consultants.flush();
        return ConsultantResponse.from(consultant);
    }

    /**
     * Changes status. When a consultant becomes INACTIVE or SUSPENDED, their
     * signed-in session
     * ends on its next request (AccountStillActiveFilter), and they can no longer
     * sign in.
     */
    @Transactional
    public ConsultantResponse changeStatus(long id, ChangeConsultantStatusRequest request) {
        Consultant consultant = load(id);
        requireVersion(consultant, request.version());
        consultant.changeStatus(request.status());
        // TODO: write an audit entry with request.reason() once Sifiso's AuditLogWriter
        // is on main.
        consultants.flush();
        return ConsultantResponse.from(consultant);
    }

    private Consultant load(long id) {
        return consultants.findById(id)
                .orElseThrow(() -> ApiException.notFound("consultant:not-found",
                        "Consultant " + id + " does not exist."));
    }

    /**
     * Hibernate's @Version only protects against changes made while THIS request
     * runs. The client
     * may have read the record minutes ago, so compare its version with the current
     * one first.
     */
    private static void requireVersion(Consultant consultant, long expectedVersion) {
        if (consultant.getVersion() != expectedVersion) {
            throw ApiException.staleVersion();
        }
    }

    /**
     * " Thandi " becomes "%thandi%", with LIKE wildcards in the input escaped. Null
     * if blank.
     */
    static String toLikePattern(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String escaped = search.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
