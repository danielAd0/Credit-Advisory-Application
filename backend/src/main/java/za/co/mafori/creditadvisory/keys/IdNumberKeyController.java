package za.co.mafori.creditadvisory.keys;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Following a key rotation (docs/api-auth-consultants-clients.md, "ID-number
 * key rotation"). Only counts and key versions: never a key or a stored value.
 */
@RestController
@RequestMapping("/api/id-number-keys")
@PreAuthorize("hasRole('ADMIN')")
public class IdNumberKeyController {

    private final IdNumberKeyRotation rotation;

    public IdNumberKeyController(IdNumberKeyRotation rotation) {
        this.rotation = rotation;
    }

    /**
     * What still uses an older key. 0 everywhere: the previous key can be removed.
     */
    @GetMapping
    public IdNumberKeyRotation.Status status() {
        return rotation.status();
    }

    /**
     * Re-encrypts now instead of waiting for the background job. Safe to repeat.
     */
    @PostMapping("/re-encrypt")
    public IdNumberKeyRotation.Result reEncrypt() {
        return rotation.run();
    }
}
