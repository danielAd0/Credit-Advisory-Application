package za.co.mafori.creditadvisory.dashboard;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.client.ClientRepository;
import za.co.mafori.creditadvisory.consultant.ConsultantRepository;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
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

    public DashboardService(ClientRepository clients, ConsultantRepository consultants) {
        this.clients = clients;
        this.consultants = consultants;
    }

    @Transactional(readOnly = true)
    public DashboardSummary summary(AppUserPrincipal caller) {
        if (caller.hasRole(RoleName.MANAGER) || caller.hasRole(RoleName.ADMIN)) {
            return new DashboardSummary("ALL",
                    clients.count(),
                    clients.countByCurrentConsultantIsNull(),
                    clients.countAssignedToConsultantNotIn(ConsultantStatus.ACTIVE),
                    consultantCounts());
        }
        long own = caller.consultantId() == null ? 0 : consultants.countClients(caller.consultantId());
        return new DashboardSummary("OWN", own, null, null, null);
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
}
