package com.example.cp.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.saml2.provider.service.registration.InMemoryRelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "app.sso", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SsoSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SsoSecurityConfig.class);

    private final SsoProviderRepository providerRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.sso.base-url:http://localhost:8080}")
    private String baseUrl;

    public SsoSecurityConfig(SsoProviderRepository providerRepo) {
        this.providerRepo = providerRepo;
    }

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        List<ClientRegistration> registrations = new ArrayList<>();
        try {
            for (SsoProvider p : providerRepo.findByEnabledTrue()) {
                if (p.getType() != SsoProvider.Type.OIDC) continue;
                Map<String, Object> cfg = readConfig(p);
                String issuer = (String) cfg.get("issuer");
                String clientId = (String) cfg.get("clientId");
                String clientSecret = (String) cfg.getOrDefault("clientSecret", "");
                if (issuer == null || clientId == null) continue;
                String registrationId = "oidc-" + p.getId();
                String iss = issuer.replaceAll("/$", "");
                String authUri = (String) cfg.getOrDefault("authorizationUri", iss + "/auth");
                String tokenUri = (String) cfg.getOrDefault("tokenUri", iss + "/token");
                String userInfo = (String) cfg.getOrDefault("userInfoUri", iss + "/userinfo");
                String jwksUri = (String) cfg.getOrDefault("jwkSetUri", iss + "/jwks");
                registrations.add(ClientRegistration.withRegistrationId(registrationId)
                        .clientId(clientId)
                        .clientSecret(clientSecret)
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri(baseUrl + "/login/oauth2/code/{registrationId}")
                        .scope("openid", "profile", "email")
                        .userNameAttributeName("email")
                        .authorizationUri(authUri)
                        .tokenUri(tokenUri)
                        .userInfoUri(userInfo)
                        .jwkSetUri(jwksUri)
                        .clientName(registrationId)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Failed to bootstrap OIDC client registrations: {}", e.getMessage());
        }
        if (registrations.isEmpty()) {
            registrations.add(ClientRegistration.withRegistrationId("placeholder")
                    .clientId("placeholder")
                    .clientSecret("placeholder")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(baseUrl + "/login/oauth2/code/placeholder")
                    .scope("openid")
                    .authorizationUri("https://invalid.local/auth")
                    .tokenUri("https://invalid.local/token")
                    .clientName("placeholder")
                    .build());
        }
        return new InMemoryClientRegistrationRepository(registrations);
    }

    @Bean
    public RelyingPartyRegistrationRepository relyingPartyRegistrationRepository() {
        List<RelyingPartyRegistration> regs = new ArrayList<>();
        try {
            for (SsoProvider p : providerRepo.findByEnabledTrue()) {
                if (p.getType() != SsoProvider.Type.SAML) continue;
                Map<String, Object> cfg = readConfig(p);
                String metadataUrl = (String) cfg.get("metadataUrl");
                if (metadataUrl == null) continue;
                String registrationId = "saml-" + p.getId();
                try {
                    RelyingPartyRegistration r = RelyingPartyRegistrations
                            .fromMetadataLocation(metadataUrl)
                            .registrationId(registrationId)
                            .entityId(baseUrl + "/saml2/service-provider-metadata/{registrationId}")
                            .assertionConsumerServiceLocation(baseUrl + "/login/saml2/sso/{registrationId}")
                            .build();
                    regs.add(r);
                } catch (Exception ex) {
                    log.warn("Failed to load SAML metadata for provider {}: {}", p.getId(), ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to bootstrap SAML RP registrations: {}", e.getMessage());
        }
        if (regs.isEmpty()) {
            return new EmptyRelyingPartyRegistrationRepository();
        }
        return new InMemoryRelyingPartyRegistrationRepository(regs);
    }

    private static class EmptyRelyingPartyRegistrationRepository implements RelyingPartyRegistrationRepository, Iterable<RelyingPartyRegistration> {
        @Override
        public RelyingPartyRegistration findByRegistrationId(String registrationId) {
            return null;
        }
        @Override
        public java.util.Iterator<RelyingPartyRegistration> iterator() {
            return java.util.Collections.emptyIterator();
        }
    }

    private Map<String, Object> readConfig(SsoProvider p) {
        try {
            return mapper.readValue(p.getConfigJson() == null ? "{}" : p.getConfigJson(), Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
