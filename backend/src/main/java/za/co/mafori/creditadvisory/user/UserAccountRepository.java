package za.co.mafori.creditadvisory.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /**
     * Finds a user by email, case-insensitively, with roles and consultant loaded -
     * everything
     * sign-in needs. Uses lower(...) so the uq_users_email_ci index is used.
     */
    @EntityGraph(attributePaths = { "roles", "consultant" })
    @Query("select u from UserAccount u where lower(u.email) = lower(:email)")
    Optional<UserAccount> findByEmailForSignIn(@Param("email") String email);

    @Query("select count(u) > 0 from UserAccount u where lower(u.email) = lower(:email)")
    boolean existsByEmail(@Param("email") String email);

    /** The login belonging to a consultant, if any. */
    Optional<UserAccount> findByConsultantId(Long consultantId);

    /** One user with roles and consultant loaded - used by GET /api/auth/me. */
    @EntityGraph(attributePaths = { "roles", "consultant" })
    Optional<UserAccount> findWithRolesAndConsultantById(Long id);

    /**
     * True if a signed-in user may keep their session: still enabled, and either
     * not a
     * consultant or a consultant with the given (ACTIVE) status. Checked on every
     * request (FR-18).
     */
    @Query("""
            select count(u) > 0 from UserAccount u left join u.consultant c
            where u.id = :id
              and u.enabled = true
              and (c is null or c.status = :activeStatus)
            """)
    boolean mayStaySignedIn(@Param("id") long id, @Param("activeStatus") ConsultantStatus activeStatus);
}
