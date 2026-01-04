package com.codelens.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtService jwtService;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final RestTemplate restTemplate;

    @Value("${codelens.security.oauth2.success-redirect-url:http://localhost:5174/auth/callback}")
    private String successRedirectUrl;

    @Value("${codelens.security.github.required-org:}")
    private String requiredGitHubOrg;

    public OAuth2SuccessHandler(JwtService jwtService, OAuth2AuthorizedClientService authorizedClientService) {
        this.jwtService = jwtService;
        this.authorizedClientService = authorizedClientService;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

        // Detect provider from authentication token
        String provider = "google"; // default
        if (authentication instanceof OAuth2AuthenticationToken) {
            provider = ((OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId();
        }

        String email;
        String name;
        String picture;
        String providerId;

        if ("github".equals(provider)) {
            // GitHub returns different attributes
            email = oAuth2User.getAttribute("email");
            name = oAuth2User.getAttribute("name");
            if (name == null) {
                name = oAuth2User.getAttribute("login"); // fallback to username
            }
            picture = oAuth2User.getAttribute("avatar_url");
            Object id = oAuth2User.getAttribute("id");
            providerId = id != null ? id.toString() : null;

            // Check GitHub organization membership if required
            if (requiredGitHubOrg != null && !requiredGitHubOrg.isBlank()) {
                String username = oAuth2User.getAttribute("login");
                OAuth2AuthenticationToken authToken = (OAuth2AuthenticationToken) authentication;
                OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient(
                        authToken.getAuthorizedClientRegistrationId(),
                        authToken.getName()
                );

                if (authorizedClient != null) {
                    String accessToken = authorizedClient.getAccessToken().getTokenValue();
                    if (!isOrgMember(accessToken, username, requiredGitHubOrg)) {
                        log.warn("GitHub user {} is not a member of required org: {}", username, requiredGitHubOrg);
                        String errorUrl = UriComponentsBuilder.fromUriString(successRedirectUrl.replace("/auth/callback", ""))
                                .queryParam("error", URLEncoder.encode(
                                        "Access denied. You must be a member of the " + requiredGitHubOrg + " organization.",
                                        StandardCharsets.UTF_8))
                                .build()
                                .toUriString();
                        getRedirectStrategy().sendRedirect(request, response, errorUrl);
                        return;
                    }
                    log.info("GitHub user {} verified as member of org: {}", username, requiredGitHubOrg);
                }
            }
        } else {
            // Google (default)
            email = oAuth2User.getAttribute("email");
            name = oAuth2User.getAttribute("name");
            picture = oAuth2User.getAttribute("picture");
            providerId = oAuth2User.getAttribute("sub");
        }

        log.info("OAuth2 login successful for user: {} via {}", email, provider);

        String accessToken = jwtService.generateAccessToken(email, name, picture, providerId);
        String refreshToken = jwtService.generateRefreshToken(email);

        // Redirect to frontend with tokens
        String redirectUrl = UriComponentsBuilder.fromUriString(successRedirectUrl)
                .queryParam("access_token", accessToken)
                .queryParam("refresh_token", refreshToken)
                .build()
                .toUriString();

        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }

    /**
     * Check if a GitHub user is a member of the specified organization.
     * Uses the /user/orgs endpoint to list organizations the user belongs to.
     */
    private boolean isOrgMember(String accessToken, String username, String orgName) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            headers.set("Accept", "application/vnd.github+json");
            headers.set("X-GitHub-Api-Version", "2022-11-28");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            // Get user's organizations
            ResponseEntity<List> orgResponse = restTemplate.exchange(
                    "https://api.github.com/user/orgs",
                    HttpMethod.GET,
                    entity,
                    List.class
            );

            if (orgResponse.getBody() != null) {
                for (Object org : orgResponse.getBody()) {
                    if (org instanceof Map) {
                        Object login = ((Map<?, ?>) org).get("login");
                        if (orgName.equalsIgnoreCase(String.valueOf(login))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.error("Error checking GitHub org membership for user {}: {}", username, e.getMessage());
            return false;
        }
    }
}
