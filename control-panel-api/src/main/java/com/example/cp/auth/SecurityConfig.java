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
                                           @Value("${app.ratelimit.auth.capacity:10}") int capacity,
                                           @Value("${app.ratelimit.auth.refill-per-minute:10}") int refill) {
        return new RateLimitFilter(objectMapper, capacity, refill);
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

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthFilter jwtAuthFilter,
            RateLimitFilter rateLimitFilter,
            ObjectProvider<ApiKeyAuthFilter> apiKeyAuthFilterProvider,
            ObjectProvider<SsoSuccessHandler> ssoSuccessHandlerProvider) throws Exception {
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
                                "/actuator/info",
                                "/swagger",
                                "/swagger/**",
                                "/swagger-ui",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/login/oauth2/**",
                                "/login/saml2/**",
                                "/oauth2/authorization/**",
                                "/saml2/authenticate/**",
                                "/saml2/service-provider-metadata/**"
                        ).permitAll()
                        // All other actuator endpoints (metrics, prometheus, env, ...) require auth.
                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
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
                .addFilterBefore(rateLimitFilter, JwtAuthFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable());

        ApiKeyAuthFilter apiKeyFilter = apiKeyAuthFilterProvider.getIfAvailable();
        if (apiKeyFilter != null) {
            http.addFilterBefore(apiKeyFilter, JwtAuthFilter.class);
        }

        SsoSuccessHandler ssoHandler = ssoSuccessHandlerProvider.getIfAvailable();
        if (ssoHandler != null) {
            http.oauth2Login(o -> o.successHandler(ssoHandler));
            http.saml2Login(s -> s.successHandler(ssoHandler));
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
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", config);
        return src;
    }
}
