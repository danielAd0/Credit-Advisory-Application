package za.co.mafori.creditadvisory.auth;

import java.io.Serializable;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import za.co.mafori.creditadvisory.user.RoleName;
import za.co.mafori.creditadvisory.user.UserAccount;

/**
 * The signed-in user, kept in the HTTP session. Holds ids and roles only -
 * never the password
 * hash or any client data.
 *
 * <p>
 * accountVersion is the user's version at sign-in. It goes up whenever an admin
 * changes something that matters for security (password, roles, consultant
 * link, enabled), and AccountStillActiveFilter signs the session out when it no
 * longer matches. Sign-in bookkeeping (failed attempts, lockout, last login)
 * does not change it.
 *
 * <p>
 * mustChangePassword: the password was set by an admin, so only the
 * change-password, me, csrf and logout endpoints may be used
 * (PasswordChangeRequiredFilter).
 *
 * Serializable because the servlet container may write sessions to disk.
 */
public record AppUserPrincipal(long userId, String email, Set<RoleName> roles, Long consultantId,
        long accountVersion, boolean mustChangePassword)
        implements AuthenticatedPrincipal, Serializable {

    public AppUserPrincipal {
        roles = Set.copyOf(roles);
    }

    public static AppUserPrincipal from(UserAccount user) {
        Long consultantId = user.getConsultant() == null ? null : user.getConsultant().getId();
        return new AppUserPrincipal(user.getId(), user.getEmail(), user.getRoleNames(), consultantId,
                user.getVersion(), user.mustChangePassword());
    }

    /**
     * This session after the user changed their own password: the new account
     * version, and no longer forced to change it.
     */
    public AppUserPrincipal afterOwnPasswordChange(long newVersion) {
        return new AppUserPrincipal(userId, email, roles, consultantId, newVersion, false);
    }

    /**
     * What Authentication.getName() returns - the email, which also equals
     * users.username.
     */
    @Override
    public String getName() {
        return email;
    }

    public boolean hasRole(RoleName role) {
        return roles.contains(role);
    }

    public List<GrantedAuthority> authorities() {
        return roles.stream().map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.getAuthority())).toList();
    }
}
