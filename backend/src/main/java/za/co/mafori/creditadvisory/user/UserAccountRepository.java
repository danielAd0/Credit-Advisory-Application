package za.co.mafori.creditadvisory.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /**
     * Finds a user by email, case-insensitively, with roles and consultant loaded -
     * everything
     * sign-in needs. User lower(...) so the uq_users_email_ci index is used.
     */
    @EntityGraph(attributePaths = { "roles", "consultant" })
    @Query("select u from UserAccount u where lower(u.email) = lower(:email)")
    Optional<UserAccount> findByEmailForSignIn(@Param("email") String email);

    @Query("select count(u) > 0 from UserAccount u where lower(u.email) = lower(:email)")
    boolean existsByEmail(@Param("email") String email);

    /**
     * The login belonging to a consultant, if any - used to end sessions when a
     * consultant is suspended.
     */
    Optional<UserAccount> findByConsultantId(Long consultantId);

    /** One user with roles and consultant loaded - used by GET /api/auth/me. */
    @EntityGraph(attributePaths = { "roles", "consultant" })
    Optional<UserAccount> findWithRolesAndConsultantById(Long id);
}
