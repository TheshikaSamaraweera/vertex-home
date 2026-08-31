package com.democode.mlmsittu.identity.internal.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Argon2id with the parameters from architecture §7.3: 16-byte salt, 32-byte hash,
     * parallelism 1, 64 MiB memory, 3 iterations. Requires BouncyCastle on the classpath.
     *
     * <p>P1-02 says decide now, not later — changing this after users exist means every one of
     * them resets their password, because a hash cannot be converted between schemes.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 1 << 16, 3);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Who implies whom (architecture §8.1, extended for the client's {@code admin} role).
     *
     * <pre>
     *   SUPER_ADMIN
     *        └── ADMIN
     *              ├── KYC_REVIEWER
     *              ├── INVENTORY_CLERK
     *              ├── PROCUREMENT_OFFICER
     *              ├── FINANCE_OFFICER
     *              └── SUPPORT_AGENT
     * </pre>
     *
     * <h2>What separates admin from super admin</h2>
     *
     * Exactly one thing: <b>user and role management</b>. {@code UserAdminController} asks for
     * {@code SUPER_ADMIN}, and {@code ADMIN} does not imply it, so an admin cannot grant a role —
     * to anybody, including themselves. That is deliberate and it is the whole point of the
     * distinction: the power to assign roles is the power to acquire every other power, so a role
     * that stopped short of everything <em>except</em> that would stop short of nothing.
     *
     * <h2>Why a hierarchy and not more annotations</h2>
     *
     * Without this, a super admin genuinely could not create an item or place a purchase order,
     * because those endpoints ask for {@code hasRole('INVENTORY_CLERK')} and a super admin does not
     * hold that role. The alternative fix — adding the privileged roles to every
     * {@code @PreAuthorize} in the codebase — was rejected: it is thirty edits today and one
     * forgotten edit on the next endpoint somebody adds, and the forgotten one fails as a confusing
     * 403 rather than as anything a test would catch. A hierarchy also means the new {@code ADMIN}
     * role needed no endpoint changes at all.
     *
     * <h2>What this deliberately does not weaken</h2>
     *
     * The separation-of-duties rules are about <b>identity, not role</b>, and none of them go
     * through this:
     *
     * <ul>
     *   <li>A reviewer cannot approve their own registration — checked against the submitter's user
     *       id ({@code SELF_REVIEW_FORBIDDEN}).
     *   <li>Whoever recorded a payment cannot verify it — checked against {@code recorded_by}, and
     *       again by {@code chk_payment_four_eyes} in the database
     *       ({@code SELF_VERIFICATION_FORBIDDEN}).
     * </ul>
     *
     * An admin and a super admin are both subject to both, exactly as §8.1 requires.
     *
     * <h2>STAFF, which exists here and in no database table</h2>
     *
     * Most read endpoints were written when every authenticated caller was staff, so they carried
     * no {@code @PreAuthorize} at all: being signed in <em>was</em> the authorisation. Distributors
     * broke that assumption. Without a way to say "anyone who works here", a distributor's session
     * could read the item catalogue, the customer list, and — worst of all —
     * {@code /distributors/roots}, which would hand them the entire referral tree the portal exists
     * to keep private.
     *
     * <p>Naming all seven roles on every open endpoint would have been the same mistake in a new
     * form: correct today, wrong the first time somebody adds a role and forgets one.
     * {@code hasRole('STAFF')} says what is meant, and a future staff role joins by implying it.
     * {@code DISTRIBUTOR} implies nothing, which is the whole point.
     */
    @Bean
    public org.springframework.security.access.hierarchicalroles.RoleHierarchy roleHierarchy() {
        return org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl.withDefaultRolePrefix()
                .role("SUPER_ADMIN")
                .implies("ADMIN")
                .role("ADMIN")
                .implies(
                        "KYC_REVIEWER",
                        "INVENTORY_CLERK",
                        "PROCUREMENT_OFFICER",
                        "FINANCE_OFFICER",
                        "SUPPORT_AGENT")
                // Every staff role implies STAFF; DISTRIBUTOR implies nothing. See below.
                .role("KYC_REVIEWER")
                .implies("STAFF")
                .role("INVENTORY_CLERK")
                .implies("STAFF")
                .role("PROCUREMENT_OFFICER")
                .implies("STAFF")
                .role("FINANCE_OFFICER")
                .implies("STAFF")
                .role("SUPPORT_AGENT")
                .implies("STAFF")
                .build();
    }


    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CSRF tokens are omitted deliberately. Session cookies are SameSite=Lax, so a
                // browser will not attach them to a cross-site POST, PUT or DELETE — which is the
                // whole attack. Nothing here changes state on a GET. Revisit in Phase 9 (P9-03)
                // if the deployment ever needs SameSite=None for a separate frontend origin,
                // because that removes the protection this decision rests on.
                .csrf(AbstractHttpConfigurer::disable)

                // Login is a JSON endpoint, not a redirect-driven form.
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)

                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/login/totp",
                                                "/api/v1/auth/logout",
                                                // Public by necessity: nobody has a session yet.
                                                // Each is rate limited and answers identically
                                                // whether or not the address exists.
                                                "/api/v1/auth/register",
                                                "/api/v1/auth/verify-email",
                                                "/api/v1/auth/resend-verification")
                                        .permitAll()
                                        .requestMatchers("/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers("/error")
                                        .permitAll()
                                        // The API description and Swagger UI. The frontend build
                                        // reads /v3/api-docs to generate its TypeScript types.
                                        // Turn both off in production with
                                        // springdoc.api-docs.enabled=false — an unauthenticated
                                        // map of every endpoint is a gift to anyone probing.
                                        .requestMatchers(
                                                "/v3/api-docs",
                                                "/v3/api-docs/**",
                                                "/swagger-ui.html",
                                                "/swagger-ui/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())

                // Without these, an unauthenticated API call gets Spring's HTML error page and a
                // 403 where a 401 belongs. Both must be problem+json like every other error.
                .exceptionHandling(
                        handling ->
                                handling.authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        writeProblem(
                                                                response,
                                                                request,
                                                                HttpStatus.UNAUTHORIZED,
                                                                "UNAUTHENTICATED",
                                                                "Authentication is required."))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        writeProblem(
                                                                response,
                                                                request,
                                                                HttpStatus.FORBIDDEN,
                                                                "FORBIDDEN",
                                                                "Your role does not permit this"
                                                                    + " action.")));

        return http.build();
    }

    /**
     * Mirrors {@code GlobalExceptionHandler}'s body shape.
     *
     * <p>Duplicated on purpose: these two run in different places. The filter chain rejects a
     * request before any controller is reached, so {@code @RestControllerAdvice} never sees it.
     * The client must not be able to tell the difference.
     */
    private void writeProblem(
            HttpServletResponse response,
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String detail)
            throws IOException {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "type",
                URI.create(
                                "https://mlmsittu.lk/problems/"
                                        + code.toLowerCase().replace('_', '-'))
                        .toString());
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code);
        body.put("instance", request.getRequestURI());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
