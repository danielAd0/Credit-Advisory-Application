package za.co.mafori.creditadvisory.client;

import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.LikePattern;
import za.co.mafori.creditadvisory.common.web.PageResponse;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.user.RoleName;

/** Read side of clients, with record-level access (contract section 4). */
@Service
public class ClientQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ClientRepository clients;
    private final ClientLatestSales latestSales;

    public ClientQueryService(ClientRepository clients, ClientLatestSales latestSales) {
        this.clients = clients;
        this.latestSales = latestSales;
    }

    /**
     * MANAGER / ADMIN see all clients and may filter. A CONSULTANT only ever sees
     * their own
     * clients; their consultantId / consultantStatus / unassigned filters are
     * ignored.
     */
    @Transactional(readOnly = true)
    public PageResponse<ClientListItem> search(AppUserPrincipal caller, String search,
            Long consultantId, ConsultantStatus consultantStatus,
            boolean unassigned, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Long effectiveConsultantId;
        ConsultantStatus effectiveStatus;
        boolean effectiveUnassigned;

        if (seesAllClients(caller)) {
            if (unassigned && (consultantId != null || consultantStatus != null)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation:invalid-filter", "Invalid filter",
                        "unassigned=true cannot be combined with consultantId or consultantStatus.");
            }
            effectiveConsultantId = consultantId;
            effectiveStatus = consultantStatus;
            effectiveUnassigned = unassigned;
        } else {
            if (caller.consultantId() == null) {
                // A consultant-role user without a consultant record has no clients.
                return new PageResponse<>(List.of(), safePage, safeSize, 0, 0);
            }
            effectiveConsultantId = caller.consultantId();
            effectiveStatus = null;
            effectiveUnassigned = false;
        }

        PageRequest pageable = PageRequest.of(safePage, safeSize,
                Sort.by("lastName").and(Sort.by("firstName")).and(Sort.by("id")));
        Page<Client> found = clients.search(LikePattern.contains(search), fileReference(search),
                effectiveConsultantId, effectiveStatus, effectiveUnassigned, pageable);
        // Payment term and structure come from each client's latest sale: one query
        // for the whole page.
        Map<Long, LatestSale> latest = latestSales.forClients(found.getContent().stream().map(Client::getId).toList());
        return PageResponse.of(found, client -> ClientListItem.from(client, latest.get(client.getId())));
    }

    /**
     * A search of digits only may be a File number or File ID: those are matched
     * exactly, next to the name search.
     */
    static String fileReference(String search) {
        if (search == null) {
            return null;
        }
        String trimmed = search.strip();
        return trimmed.matches("[0-9]{1,20}") ? trimmed : null;
    }

    public static boolean seesAllClients(AppUserPrincipal caller) {
        return caller.hasRole(RoleName.MANAGER) || caller.hasRole(RoleName.ADMIN);
    }
}
