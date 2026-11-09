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
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * Session-based security for a React frontend (see
 * docs/api-auth-consultants-clients.md section 1).
 *
 * <ul>
 * <li>Sign-in through POST /api/auth/login; the session is kept in a JSESSIONID
 * cookie.</li>
 * <li>CSRF: XSRF-TOKEN cookie, echoed back in the X-XSRF-TOKEN header
 * (csrf.spa()).</li>
 * <li>401 / 403 are JSON ProblemDetail, never a browser login popup or
 * redirect.</li>
 * <li>Disabled users and non-ACTIVE consultants are signed out on their next
 * request (FR-18).</li>
 * <li>Users with an admin-set password must change it before doing anything
 * else.</li>
 * <li>@PreAuthorize is enabled here (method security).</li>
 * <li>Security headers on every response, errors included: CSP,
 * Referrer-Policy,
 * Permissions-Policy, no framing, no caching (see README "Running in
 * production").</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

        /**
         * The API only ever returns JSON, so it never needs to load scripts, styles,
         * images or frames, and no page may frame it.
         */
        static final String API_CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

        /**
         * Nothing we serve may use the camera, microphone, location, payments or USB.
         */
        static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

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
        public SecurityFilterChain apiSecurity(HttpSecurity http,
                        SecurityContextRepository contextRepository,
                        UserAccountRepository users) throws Exception {
                http
                                .cors(Customizer.withDefaults())
                                .csrf(csrf -> csrf.spa())
                                .securityContext(context -> context.securityContextRepository(contextRepository))
                                // An API never redirects to a login page, so don't remember "where they were
                                // going".
                                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                                // Spring's defaults stay on (nosniff, no-store caching, HSTS over HTTPS);
                                // these add the rest.
                                .headers(headers -> headers
                                                .contentSecurityPolicy(csp -> csp
                                                                .policyDirectives(API_CONTENT_SECURITY_POLICY))
                                                // URLs contain client and case ids: never send them to another site.
                                                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                                                .permissionsPolicyHeader(
                                                                permissions -> permissions.policy(PERMISSIONS_POLICY))
                                                .frameOptions(frame -> frame.deny()))
                                // FR-18: re-check the account on every request, just before access is decided.
                                .addFilterBefore(new AccountStillActiveFilter(users), AuthorizationFilter.class)
                                // Password right but authenticator code not entered yet: only the MFA
                                // endpoints, me, csrf and logout. Runs after AccountStillActiveFilter.
                                .addFilterBefore(new MfaRequiredFilter(), AuthorizationFilter.class)
                                // Admin-set password: only change-password, me, csrf and logout until it is
                                // changed.
                                // Added after AccountStillActiveFilter, so it runs after it.
                                .addFilterBefore(new PasswordChangeRequiredFilter(), AuthorizationFilter.class)
                                .authorizeHttpRequests(auth -> auth
                                                .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                                                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                                                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                                                .requestMatchers("/error").permitAll()
                                                .anyRequest().authenticated())
                                .exceptionHandling(errors -> errors
                                                // Not signed in -> 401
                                                .authenticationEntryPoint((request, response, e) -> writeProblem(
                                                                response,
                                                                HttpStatus.UNAUTHORIZED, "Not signed in",
                                                                "Sign in to continue.", "auth:unauthenticated"))
                                                // Signed in but not allowed, or bad CSRF token -> 403
                                                .accessDeniedHandler((request, response, e) -> writeProblem(response,
                                                                HttpStatus.FORBIDDEN, "Forbidden",
                                                                "You do not have permission to do this.",
                                                                "auth:forbidden")))
                                .logout(logout -> logout
                                                .logoutUrl("/api/auth/logout")
                                                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(
                                                                HttpStatus.NO_CONTENT))
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
        private static void writeProblem(HttpServletResponse response, HttpStatus status, String title,
                        String detail, String type) throws IOException {
                response.setStatus(status.value());
                response.setContentType("application/problem+json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"type\":\"urn:credit-advisory:" + type + "\","
                                + "\"title\":\"" + title + "\","
                                + "\"status\":" + status.value() + ","
                                + "\"detail\":\"" + detail + "\"}");
        }
}
