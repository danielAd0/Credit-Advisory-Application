package za.co.mafori.creditadvisory.client;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, Long> {

    /**
     * Finds a client by the lookup hash of their ID number (never by the plain
     * number)
     */
    Optional<Client> findByIdNumberHash(byte[] idNumberHash);

    boolean existsByIdNumberHash(byte[] idNumberHash);
}
