package com.example.cp.auth;

import com.example.cp.apikeys.ApiKeyAuthFilter;
import com.example.cp.rbac.AuthoritiesLoader;
import com.example.cp.sso.SsoSuccessHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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

    public SecurityConfig(SessionTokenService sessionTokenService,
                          AuthoritiesLoader authoritiesLoader,
                          ObjectMapper objectMapper) {
        this.sessionTokenService = sessionTokenService;
        this.authoritiesLoader = authoritiesLoader;
        this.objectMapper = objectMapper;
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter() {
        return new JwtAuthFilter(sessionTokenService, authoritiesLoader, objectMapper);
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
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthFilter jwtAuthFilter,
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
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
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
        config.setAllowedOrigins(List.of("http://localhost:5173"));
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
