package com.codelens.config;

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

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        List<ClientRegistration> registrations = new ArrayList<>();

        // Add Google if configured
        if (isNotEmpty(googleClientId) && isNotEmpty(googleClientSecret)) {
            registrations.add(googleClientRegistration());
            log.info("Google OAuth2 client registered");
        } else {
            log.info("Google OAuth2 client NOT registered (credentials not configured)");
        }

        // Add GitHub if configured
        if (isNotEmpty(githubClientId) && isNotEmpty(githubClientSecret)) {
            registrations.add(githubClientRegistration());
            log.info("GitHub OAuth2 client registered");
        } else {
            log.info("GitHub OAuth2 client NOT registered (credentials not configured)");
        }

        if (registrations.isEmpty()) {
            throw new IllegalStateException("At least one OAuth2 provider must be configured. " +
                    "Set GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET or GITHUB_OAUTH_CLIENT_ID/GITHUB_OAUTH_CLIENT_SECRET");
        }

        return new InMemoryClientRegistrationRepository(registrations);
    }

    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    private ClientRegistration googleClientRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId(googleClientId)
                .clientSecret(googleClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(googleRedirectUri)
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

    private boolean isNotEmpty(String value) {
        return value != null && !value.isBlank();
    }
}
