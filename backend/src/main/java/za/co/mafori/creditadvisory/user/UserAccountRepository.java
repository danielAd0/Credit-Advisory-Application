package za.co.mafori.creditadvisory.user;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * GET /api/users. searchPattern is a lower-case, escaped LIKE pattern, or null
     * for all.
     */
    @EntityGraph(attributePaths = { "consultant" })
    @Query(value = """
            select u from UserAccount u
            where :searchPattern is null
               or lower(u.email) like :searchPattern escape '\\'
            """, countQuery = """
            select count(u) from UserAccount u
            where :searchPattern is null
               or lower(u.email) like :searchPattern escape '\\'
            """)
    Page<UserAccount> search(@Param("searchPattern") String searchPattern, Pageable pageable);

    /**
     * How many enabled users have this role - used to protect the last
     * administrator.
     */
    @Query("""
            select count(u) from UserAccount u join u.roles r
            where r.name = :role and u.enabled = true
            """)
    long countEnabledWithRole(@Param("role") RoleName role);
}
