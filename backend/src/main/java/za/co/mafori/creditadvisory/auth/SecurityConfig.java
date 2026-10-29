package za.co.mafori.creditadvisory.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Session-based security for a React frontend (see
 * docs/api-auth-consultants-clients.md section 1).
 *
 * Sign-in through POST /api/auth/login; the session is kept in a JSESSIONID
 * cookie.
 * CSRF: XSRF-TOKEN cookie, echoed back in the X-XSRF-TOKEN header (csrf.spa()).
 * 401 / 403 are JSON ProblemDetail, never a browser login popup or redirect.
 *
 * @PreAuthorize is enabled here (method security).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt by default; stored hashes start with {bcrypt} so the algorithm can be
        // changed later.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(DatabaseAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, SecurityContextRepository contextRepository)
            throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.spa())
                .securityContext(context -> context.securityContextRepository(contextRepository))
                // An API never redirects to a login page, so don't remember "where they were
                // going".
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        // Not signed in -> 401
                        .authenticationEntryPoint((request, response, e) -> writeProblem(response,
                                HttpStatus.UNAUTHORIZED, "Not signed in",
                                "Sign in to continue.", "auth:unauthenticated"))
                        // Signed in but not allowed, or bad CSRF token -> 403
                        .accessDeniedHandler((request, response, e) -> writeProblem(response,
                                HttpStatus.FORBIDDEN, "Forbidden",
                                "You do not have permission to do this.", "auth:forbidden")))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies("JSESSIONID"));

        return http.build();
    }

    /** CORS for the Vite dev server. Origins come from the local profile only. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.corsAllowedOriginsOrEmpty());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        config.setAllowCredentials(true);
        config.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /**
     * Writes a fixed ProblemDetail body. Only constant strings go in, so no
     * escaping is needed.
     */
    private static void writeProblem(HttpServletResponse response, HttpStatus status, String title, String detail,
            String type)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"urn:credit-advisory:" + type + "\","
                + "\"title\":\"" + title + "\","
                + "\"status\":" + status.value() + ","
                + "\"detail\":\"" + detail + "\"}");
    }
}
