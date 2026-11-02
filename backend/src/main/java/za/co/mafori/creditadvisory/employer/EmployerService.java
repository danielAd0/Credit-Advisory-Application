package za.co.mafori.creditadvisory.employer;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.LikePattern;
import za.co.mafori.creditadvisory.common.web.PageResponse;

/** Employer use cases. Role checks live on the controller. */
@Service
public class EmployerService {

    private static final int MAX_PAGE_SIZE = 100;

    private final EmployerRepository employers;
    private final AuditLogWriter audit;

    public EmployerService(EmployerRepository employers, AuditLogWriter audit) {
        this.employers = employers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<EmployerResponse> search(String search, Boolean active, String employerType, int page,
            int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by("name").and(Sort.by("id")));
        String type = employerType == null || employerType.isBlank() ? null
                : employerType.strip().toLowerCase(Locale.ROOT);
        return PageResponse.of(employers.search(LikePattern.contains(search), active, type, pageable),
                EmployerResponse::from);
    }

    @Transactional(readOnly = true)
    public EmployerResponse get(long id) {
        return EmployerResponse.from(load(id));
    }

    @Transactional
    public EmployerResponse create(EmployerRequest request) {
        String name = Employer.normalizeName(request.name());
        requireNameFree(name, -1);
        Employer saved = employers.saveAndFlush(new Employer(name, request.company(), request.employerType()));
        audit.record("EMPLOYER_CREATED", "EMPLOYER", saved.getId(),
                meta("name", saved.getName(), "company", saved.getCompany(), "employerType", saved.getEmployerType()));
        return EmployerResponse.from(saved);
    }

    @Transactional
    public EmployerResponse update(long id, UpdateEmployerRequest request) {
        Employer employer = load(id);
        requireVersion(employer, request.version());
        String name = Employer.normalizeName(request.name());
        requireNameFree(name, id);
        employer.update(name, request.company(), request.employerType());
        employers.flush();
        audit.record("EMPLOYER_UPDATED", "EMPLOYER", employer.getId(),
                meta("name", employer.getName(), "company", employer.getCompany(),
                        "employerType", employer.getEmployerType()));
        return EmployerResponse.from(employer);
    }

    @Transactional
    public EmployerResponse changeActive(long id, ChangeEmployerActiveRequest request) {
        Employer employer = load(id);
        requireVersion(employer, request.version());
        if (employer.isActive() != request.active()) {
            employer.changeActive(request.active());
            employers.flush();
            audit.record(request.active() ? "EMPLOYER_ACTIVATED" : "EMPLOYER_DEACTIVATED", "EMPLOYER",
                    employer.getId(), meta("name", employer.getName()));
        }
        return EmployerResponse.from(employer);
    }

    private void requireNameFree(String name, long excludeId) {
        if (employers.nameTaken(name, excludeId)) {
            throw ApiException.conflict("employer:duplicate-name", "Duplicate employer",
                    "An employer called " + name + " already exists.");
        }
    }

    private Employer load(long id) {
        return employers.findById(id)
                .orElseThrow(() -> ApiException.notFound("employer:not-found", "Employer " + id + " does not exist."));
    }

    private static void requireVersion(Employer employer, long expectedVersion) {
        if (employer.getVersion() != expectedVersion) {
            throw ApiException.staleVersion();
        }
    }
}
