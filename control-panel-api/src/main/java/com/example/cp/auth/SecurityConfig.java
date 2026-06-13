package com.example.cp.auth;

import com.example.cp.apikeys.ApiKeyAuthFilter;
import com.example.cp.common.RateLimitFilter;
import com.example.cp.common.TrustedProxyResolver;
import com.example.cp.rbac.AuthoritiesLoader;
import com.example.cp.sso.SsoSuccessHandler;
import com.example.cp.users.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final SessionTokenService sessionTokenService;
    private final AuthoritiesLoader authoritiesLoader;
    private final ObjectMapper objectMapper;
    private final SessionRevocationStore sessionRevocationStore;
    private final UserRepository userRepository;
    private final TrustedProxyResolver trustedProxyResolver;
    private final boolean revocationEnabled;
    private final List<String> corsAllowedOrigins;

    public SecurityConfig(SessionTokenService sessionTokenService,
                          AuthoritiesLoader authoritiesLoader,
                          ObjectMapper objectMapper,
                          SessionRevocationStore sessionRevocationStore,
                          UserRepository userRepository,
                          TrustedProxyResolver trustedProxyResolver,
                          @Value("${app.auth.revocation.enabled:true}") boolean revocationEnabled,
                          @Value("${app.cors.allowed-origins:http://localhost:5173}") List<String> corsAllowedOrigins) {
        this.sessionTokenService = sessionTokenService;
        this.authoritiesLoader = authoritiesLoader;
        this.objectMapper = objectMapper;
        this.sessionRevocationStore = sessionRevocationStore;
        this.userRepository = userRepository;
        this.trustedProxyResolver = trustedProxyResolver;
        this.revocationEnabled = revocationEnabled;
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter() {
        return new JwtAuthFilter(sessionTokenService, authoritiesLoader, objectMapper,
                sessionRevocationStore, userRepository, revocationEnabled, trustedProxyResolver);
    }

    /**
     * Prevent the Boot servlet auto-registration of {@link JwtAuthFilter}; it is only used
     * inside the Spring Security chain.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterDisabledAuto(JwtAuthFilter jwtAuthFilter) {
        FilterRegistrationBean<JwtAuthFilter> reg = new FilterRegistrationBean<>(jwtAuthFilter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public RateLimitFilter rateLimitFilter(ObjectMapper objectMapper,
                                           TrustedProxyResolver trustedProxyResolver,
                                           @Value("${app.ratelimit.auth.capacity:10}") int capacity,
                                           @Value("${app.ratelimit.auth.refill-per-minute:10}") int refill) {
        return new RateLimitFilter(objectMapper, trustedProxyResolver, capacity, refill);
    }

    /**
     * Prevent the Boot servlet auto-registration of {@link RateLimitFilter}; it is only used inside
     * the Spring Security chain (it self-restricts to auth paths via {@code shouldNotFilter}).
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterDisabledAuto(RateLimitFilter rateLimitFilter) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(rateLimitFilter);
        reg.setEnabled(false);
        return reg;
    }

    /**
     * Browser SSO chain (highest precedence). Scoped to ONLY the OAuth2/SAML login + callback paths,
     * so the {@code oauth2Login}/{@code saml2Login} entry points (which redirect to the IdP) are
     * installed here and NOT on the stateless API chain. This keeps credential-less {@code /api/**}
     * and {@code /actuator/**} calls returning a 401 JSON ProblemDetail rather than a 302 to the
     * OIDC authorization endpoint. Registered only when an {@link SsoSuccessHandler} is present.
     */
    @Bean
    @org.springframework.core.annotation.Order(1)
    public SecurityFilterChain ssoFilterChain(
            HttpSecurity http,
            ObjectProvider<SsoSuccessHandler> ssoSuccessHandlerProvider) throws Exception {
        SsoSuccessHandler ssoHandler = ssoSuccessHandlerProvider.getIfAvailable();
        http
                .securityMatcher(
                        "/oauth2/authorization/**",
                        "/login/oauth2/**",
                        "/saml2/authenticate/**",
                        "/login/saml2/**",
                        "/saml2/service-provider-metadata/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        if (ssoHandler != null) {
            http.oauth2Login(o -> o.successHandler(ssoHandler));
            http.saml2Login(s -> s.successHandler(ssoHandler));
        }
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthFilter jwtAuthFilter,
            RateLimitFilter rateLimitFilter,
            ObjectProvider<ApiKeyAuthFilter> apiKeyAuthFilterProvider) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/api/v1/licenses/revoked",
                                "/api/v1/licenses/crl",
                                "/.well-known/jwks.json",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info"
                        ).permitAll()
                        // Sensitive actuator endpoints (metrics, prometheus, env, ...) expose the
                        // full URI inventory / latencies / JVM internals — restrict to super-admins,
                        // not "any authenticated principal" (a tenant user or customer API key).
                        .requestMatchers("/actuator/**").hasAuthority("SUPER_ADMIN")
                        // The OpenAPI spec + Swagger UI enumerate the entire API surface; require a
                        // session rather than handing it to anonymous clients.
                        .requestMatchers(
                                "/swagger",
                                "/swagger/**",
                                "/swagger-ui",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs",
                                "/v3/api-docs/**"
                        ).authenticated()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                // Credential-less protected requests get a 401 (JSON) — never a 302 to an IdP. The
                // SSO redirect entry points live only on the dedicated ssoFilterChain above.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new org.springframework.security.web.authentication
                                .HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new StaticHeadersWriter(
                                "Content-Security-Policy",
                                "default-src 'none'; frame-ancestors 'none'")))
                // Add jwtAuthFilter first so JwtAuthFilter.class gets a registered order, then
                // anchor the rate limiter relative to it (rateLimit runs before JWT auth).
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, JwtAuthFilter.class)
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable());

        ApiKeyAuthFilter apiKeyFilter = apiKeyAuthFilterProvider.getIfAvailable();
        if (apiKeyFilter != null) {
            http.addFilterBefore(apiKeyFilter, JwtAuthFilter.class);
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Origins come from app.cors.allowed-origins (comma-separated env var binds to List<String>).
        // allowCredentials(true) is incompatible with a wildcard origin, so never set "*".
        config.setAllowedOrigins(corsAllowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "X-Requested-With", "Idempotency-Key"));
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", config);
        return src;
    }
}
