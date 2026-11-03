package za.co.mafori.creditadvisory.dashboard;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.client.ClientRepository;
import za.co.mafori.creditadvisory.consultant.ConsultantRepository;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.creditcase.CaseStatus;
import za.co.mafori.creditadvisory.creditcase.CreditCaseRepository;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * Dashboard counts (FR-20), computed in the database so they stay right however
 * many clients there are. Scoped like the client list: a CONSULTANT only ever
 * sees numbers about their own clients.
 */
@Service
public class DashboardService {

    private final ClientRepository clients;
    private final ConsultantRepository consultants;
    private final CreditCaseRepository cases;

    public DashboardService(ClientRepository clients, ConsultantRepository consultants,
            CreditCaseRepository cases) {
        this.clients = clients;
        this.consultants = consultants;
        this.cases = cases;
    }

    @Transactional(readOnly = true)
    public DashboardSummary summary(AppUserPrincipal caller) {
        if (caller.hasRole(RoleName.MANAGER) || caller.hasRole(RoleName.ADMIN)) {
            return new DashboardSummary("ALL",
                    clients.count(),
                    clients.countByCurrentConsultantIsNull(),
                    clients.countAssignedToConsultantNotIn(ConsultantStatus.ACTIVE),
                    consultantCounts(),
                    caseCounts(null));
        }
        if (caller.consultantId() == null) {
            // A consultant-role user without a consultant record has no clients and no
            // cases.
            return new DashboardSummary("OWN", 0, null, null, null, DashboardSummary.CaseCounts.NONE);
        }
        return new DashboardSummary("OWN", consultants.countClients(caller.consultantId()), null, null, null,
                caseCounts(caller.consultantId()));
    }

    private DashboardSummary.ConsultantCounts consultantCounts() {
        Map<ConsultantStatus, Long> byStatus = new EnumMap<>(ConsultantStatus.class);
        for (Object[] row : consultants.countByStatus()) {
            byStatus.put((ConsultantStatus) row[0], ((Number) row[1]).longValue());
        }
        return new DashboardSummary.ConsultantCounts(
                byStatus.getOrDefault(ConsultantStatus.ACTIVE, 0L),
                byStatus.getOrDefault(ConsultantStatus.INACTIVE, 0L),
                byStatus.getOrDefault(ConsultantStatus.SUSPENDED, 0L));
    }

    /**
     * @param consultantId null = every case; otherwise the cases of that
     *                     consultant's current clients
     */
    private DashboardSummary.CaseCounts caseCounts(Long consultantId) {
        Map<CaseStatus, Long> byStatus = new EnumMap<>(CaseStatus.class);
        for (Object[] row : cases.countByStatus(consultantId)) {
            byStatus.put((CaseStatus) row[0], ((Number) row[1]).longValue());
        }
        return new DashboardSummary.CaseCounts(
                byStatus.getOrDefault(CaseStatus.OPEN, 0L),
                byStatus.getOrDefault(CaseStatus.IN_PROGRESS, 0L),
                byStatus.getOrDefault(CaseStatus.CLOSED, 0L));
    }
}
