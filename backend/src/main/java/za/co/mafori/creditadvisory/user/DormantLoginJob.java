package za.co.mafori.creditadvisory.user;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs DormantLoginService once a day (app.auth.dormant-check-interval). Does
 * nothing while app.auth.dormant-after is 0, the default. Off in the tests:
 * app.scheduling.enabled=false (SchedulingConfig).
 */
@Component
public class DormantLoginJob {

    private static final Logger log = LoggerFactory.getLogger(DormantLoginJob.class);

    private final DormantLoginService dormant;

    public DormantLoginJob(DormantLoginService dormant) {
        this.dormant = dormant;
    }

    @Scheduled(initialDelayString = "${app.auth.dormant-check-initial-delay:PT10M}", fixedDelayString = "${app.auth.dormant-check-interval:PT24H}")
    public void disableDormantLogins() {
        if (!dormant.isOn()) {
            return;
        }
        int disabled = dormant.disableDormant(Instant.now());
        if (disabled > 0) {
            log.info("Disabled {} login(s) nobody had used for a long time (USER_DISABLED_DORMANT)", disabled);
        }
    }
}
