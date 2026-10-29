package za.co.mafori.creditadvisory.auth;

//import javax.naming.AuthenticationException;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

@Component
public class DatabaseAuthenticationProvider implements AuthenticationProvider {

    static final String GENERIC_FAILURE = "Sign-in failed. Check your details or try again later.";

    private final SignInService signIn;

    public DatabaseAuthenticationProvider(SignInService signIn) {
        this.signIn = signIn;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getName();
        Object credentials = authentication.getCredentials();
        String password = credentials == null ? "" : credentials.toString();

        AppUserPrincipal principal;
        try {
            principal = signIn.attempt(email, password).orElseThrow(() -> new BadCredentialsException(GENERIC_FAILURE));
        } catch (OptimisticLockingFailureException e) {
            // Two sign-ins for the same account at the same instant. Treat as a failure;
            // the user retries.
            throw new BadCredentialsException(GENERIC_FAILURE);
        }

        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
