package za.co.mafori.creditadvisory.imports.weekly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ImportCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ImportCleanupJob.class);

    private final WeeklyImportService imports;

    public ImportCleanupJob(WeeklyImportService imports) {
        this.imports = imports;
    }

    @Scheduled(initialDelayString = "${imports.cleanup-initial-delay:PT2M}", fixedDelayString = "${imports.cleanup-interval:PT1H}")
    public void expireOldPreviews() {
        int expired = imports.expirePreviews();
        if (expired > 0) {
            log.info("Expired {} import preview(s) and deleted their personal data", expired);
        }
    }
}
