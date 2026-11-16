package za.co.mafori.creditadvisory.keys;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * After a key rotation, re-encrypts in the background whatever still uses the
 * previous key, so IT only has to change the settings and restart. Does nothing
 * (one count per table) when everything is already on the current key.
 */
@Component
public class IdNumberKeyRotationJob {

    private final IdNumberKeyRotation rotation;

    public IdNumberKeyRotationJob(IdNumberKeyRotation rotation) {
        this.rotation = rotation;
    }

    @Scheduled(initialDelayString = "${app.idnumber.re-encrypt-initial-delay:PT1M}", fixedDelayString = "${app.idnumber.re-encrypt-interval:PT15M}")
    public void reEncryptOldValues() {
        if (rotation.status().totalRemaining() > 0) {
            rotation.run();
        }
    }
}
