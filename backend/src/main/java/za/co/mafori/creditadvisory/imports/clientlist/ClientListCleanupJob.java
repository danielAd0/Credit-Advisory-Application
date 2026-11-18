package za.co.mafori.creditadvisory.imports.clientlist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires client list previews after 24 hours and deletes their staged
 * personal data, on the same schedule as the weekly register's clean-up
 * (imports.cleanup-interval, default every hour). Off in the tests:
 * app.scheduling.enabled=false (SchedulingConfig).
 */
@Component
public class ClientListCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ClientListCleanupJob.class);

    private final ClientListImportService imports;

    public ClientListCleanupJob(ClientListImportService imports) {
        this.imports = imports;
    }

    @Scheduled(initialDelayString = "${imports.cleanup-initial-delay:PT2M}", fixedDelayString = "${imports.cleanup-interval:PT1H}")
    public void expireOldPreviews() {
        int expired = imports.expirePreviews();
        if (expired > 0) {
            log.info("Expired {} client list preview(s) and deleted their personal data", expired);
        }
    }
}
