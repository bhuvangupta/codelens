package com.codelens.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
public class OAuth2ClientConfig {

    @Value("${GOOGLE_CLIENT_ID:}")
    private String googleClientId;

    @Value("${GOOGLE_CLIENT_SECRET:}")
    private String googleClientSecret;

    @Value("${OAUTH2_CALLBACK_URL:http://localhost:5174/auth/callback/google}")
    private String googleRedirectUri;

    @Value("${GITHUB_OAUTH_CLIENT_ID:}")
    private String githubClientId;

    @Value("${GITHUB_OAUTH_CLIENT_SECRET:}")
    private String githubClientSecret;

    @Value("${GITHUB_OAUTH_CALLBACK_URL:http://localhost:5174/auth/callback/github}")
    private String githubRedirectUri;

    @Value("${GOOGLE_OAUTH_DOMAINS:}")
    private String googleOAuthDomains;

    @Value("${FRONTEND_URL:http://localhost:5174}")
    private String frontendUrl;

    // Store registered domain providers for API endpoint
    @Getter
    private final List<DomainProvider> domainProviders = new ArrayList<>();

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        List<ClientRegistration> registrations = new ArrayList<>();
        domainProviders.clear();

        // Add default Google if configured (and no domain-specific config)
        if (isNotEmpty(googleClientId) && isNotEmpty(googleClientSecret)) {
            registrations.add(googleClientRegistration("google", googleClientId, googleClientSecret, googleRedirectUri));
            domainProviders.add(new DomainProvider("google", "Google", null));
            log.info("Google OAuth2 client registered (default)");
        }

        // Add domain-specific Google OAuth clients
        if (isNotEmpty(googleOAuthDomains)) {
            for (String domain : googleOAuthDomains.split(",")) {
                domain = domain.trim();
                if (domain.isEmpty()) continue;

                String domainKey = domainToKey(domain);
                String clientId = System.getenv("GOOGLE_CLIENT_ID_" + domainKey);
                String clientSecret = System.getenv("GOOGLE_CLIENT_SECRET_" + domainKey);

                if (isNotEmpty(clientId) && isNotEmpty(clientSecret)) {
                    String registrationId = "google-" + domain.replace(".", "-");
                    String redirectUri = frontendUrl + "/auth/callback/" + registrationId;
                    registrations.add(googleClientRegistration(registrationId, clientId, clientSecret, redirectUri));

                    // Get org name from DOMAIN_ORG_MAPPING if available
                    String displayName = getOrgDisplayName(domain);
                    domainProviders.add(new DomainProvider(registrationId, displayName, domain));
                    log.info("Google OAuth2 client registered for domain: {} (registrationId: {})", domain, registrationId);
                } else {
                    log.warn("Google OAuth2 credentials not found for domain: {} (expected GOOGLE_CLIENT_ID_{} and GOOGLE_CLIENT_SECRET_{})",
                            domain, domainKey, domainKey);
                }
            }
        }

        // Add GitHub if configured
        if (isNotEmpty(githubClientId) && isNotEmpty(githubClientSecret)) {
            registrations.add(githubClientRegistration());
            domainProviders.add(new DomainProvider("github", "GitHub", null));
            log.info("GitHub OAuth2 client registered");
        }

        if (registrations.isEmpty()) {
            throw new IllegalStateException("At least one OAuth2 provider must be configured. " +
                    "Set GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET, GOOGLE_OAUTH_DOMAINS with per-domain credentials, " +
                    "or GITHUB_OAUTH_CLIENT_ID/GITHUB_OAUTH_CLIENT_SECRET");
        }

        // Print summary of loaded providers
        log.info("=== OAuth2 Providers Loaded ===");
        for (DomainProvider provider : domainProviders) {
            if (provider.domain() != null) {
                log.info("  - {} ({}) -> domain: {}", provider.providerId(), provider.displayName(), provider.domain());
            } else {
                log.info("  - {} ({})", provider.providerId(), provider.displayName());
            }
        }
        log.info("Total providers: {}", domainProviders.size());

        return new InMemoryClientRegistrationRepository(registrations);
    }

    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    private ClientRegistration googleClientRegistration(String registrationId, String clientId, String clientSecret, String redirectUri) {
        return ClientRegistration.withRegistrationId(registrationId)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope("openid", "email", "profile")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://www.googleapis.com/oauth2/v4/token")
                .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .clientName("Google")
                .build();
    }

    private ClientRegistration githubClientRegistration() {
        return ClientRegistration.withRegistrationId("github")
                .clientId(githubClientId)
                .clientSecret(githubClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(githubRedirectUri)
                .scope("user:email", "read:user", "read:org")
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri("https://api.github.com/user")
                .userNameAttributeName("login")
                .clientName("GitHub")
                .build();
    }

    /**
     * Convert domain to environment variable key format.
     * Example: ofbusiness.in -> OFBUSINESS_IN
     */
    private String domainToKey(String domain) {
        return domain.replace(".", "_").toUpperCase();
    }

    /**
     * Get organization display name for a domain from DOMAIN_ORG_MAPPING.
     */
    private String getOrgDisplayName(String domain) {
        String mapping = System.getenv("DOMAIN_ORG_MAPPING");
        if (isNotEmpty(mapping)) {
            try {
                // Simple JSON parsing for {"domain":"OrgName"} format
                // Using substring matching to avoid adding JSON dependency
                String searchKey = "\"" + domain + "\":\"";
                int startIdx = mapping.indexOf(searchKey);
                if (startIdx >= 0) {
                    startIdx += searchKey.length();
                    int endIdx = mapping.indexOf("\"", startIdx);
                    if (endIdx > startIdx) {
                        return mapping.substring(startIdx, endIdx);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse DOMAIN_ORG_MAPPING for domain {}: {}", domain, e.getMessage());
            }
        }
        // Fallback: use domain as display name
        return domain;
    }

    private boolean isNotEmpty(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * DTO for domain provider information.
     */
    public record DomainProvider(String providerId, String displayName, String domain) {}
}
