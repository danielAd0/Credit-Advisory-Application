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
     * Checked on every request (AccountStillActiveFilter). Returns the user's
     * current version if they may keep their session - still enabled, and either
     * not a consultant or a consultant with the given (ACTIVE) status (FR-18).
     * Empty if they must be signed out. The caller compares the version with the
     * one stored in the session at sign-in.
     */
    @Query("""
            select u.version from UserAccount u left join u.consultant c
            where u.id = :id
              and u.enabled = true
              and (c is null or c.status = :activeStatus)
            """)
    Optional<Long> findVersionIfMayStaySignedIn(@Param("id") long id,
            @Param("activeStatus") ConsultantStatus activeStatus);

    /**
     * GET /api/users. searchPattern is a lower-case, escaped LIKE pattern, or null
     * for all. role limits the result to users who have that role, or null for all.
     */
    @EntityGraph(attributePaths = { "consultant" })
    @Query(value = """
            select u from UserAccount u
            where (:searchPattern is null
                   or lower(u.email) like :searchPattern escape '\\')
              and (:role is null
                   or exists (select 1 from UserAccount u2 join u2.roles r
                              where u2 = u and r.name = :role))
            """, countQuery = """
            select count(u) from UserAccount u
            where (:searchPattern is null
                   or lower(u.email) like :searchPattern escape '\\')
              and (:role is null
                   or exists (select 1 from UserAccount u2 join u2.roles r
                              where u2 = u and r.name = :role))
            """)
    Page<UserAccount> search(@Param("searchPattern") String searchPattern, @Param("role") RoleName role,
            Pageable pageable);

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
