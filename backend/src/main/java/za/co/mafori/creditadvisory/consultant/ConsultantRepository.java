package za.co.mafori.creditadvisory.consultant;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;;

public interface ConsultantRepository extends JpaRepository<Consultant, Long> {

    Optional<Consultant> findByConsultantCode(String consultantCode);

    boolean existsByConsultantCode(String consultantCode);
}
