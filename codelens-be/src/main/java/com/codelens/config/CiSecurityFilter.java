package com.codelens.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Security filter for CI/CD integration endpoints.
 * Provides API key authentication and IP whitelisting.
 */
@Slf4j
@Component
public class CiSecurityFilter extends OncePerRequestFilter {

    @Value("${codelens.ci.api-keys:}")
    private List<String> validApiKeys;

    @Value("${codelens.ci.allowed-ips:}")
    private List<String> allowedIps;

    @Value("${codelens.ci.enabled:true}")
    private boolean ciEnabled;

    @Value("${codelens.ci.require-api-key:true}")
    private boolean requireApiKey;

    @Value("${codelens.ci.require-ip-whitelist:false}")
    private boolean requireIpWhitelist;

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String CI_PATH_PREFIX = "/api/ci/";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Only filter CI endpoints
        return !path.startsWith(CI_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String clientIp = getClientIp(request);

        // Store client IP for logging in controller
        request.setAttribute("clientIp", clientIp);

        // Allow health check without authentication
        if (path.equals("/api/ci/health")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check if CI integration is enabled
        if (!ciEnabled) {
            log.warn("CI integration is disabled. Request from IP: {}", clientIp);
            sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "CI integration is disabled");
            return;
        }

        // Check IP whitelist if enabled
        if (requireIpWhitelist && !isIpAllowed(clientIp)) {
            log.warn("CI request from non-whitelisted IP: {}", clientIp);
            sendError(response, HttpServletResponse.SC_FORBIDDEN,
                "IP address not whitelisted: " + clientIp);
            return;
        }

        // Check API key if required
        if (requireApiKey) {
            String apiKey = request.getHeader(API_KEY_HEADER);

            if (apiKey == null || apiKey.isBlank()) {
                log.warn("CI request missing API key from IP: {}", clientIp);
                sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Authentication failed");
                return;
            }

            if (!isValidApiKey(apiKey)) {
                log.warn("CI request with invalid API key from IP: {}", clientIp);
                sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Authentication failed");
                return;
            }
        }

        log.debug("CI request authenticated successfully from IP: {}", clientIp);
        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        // Check X-Forwarded-For header (for reverse proxy/load balancer)
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            // Take the first IP (original client) from comma-separated list
            String[] ips = xForwardedFor.split(",");
            if (ips.length > 0) {
                String firstIp = ips[0].trim();
                if (!firstIp.isEmpty()) {
                    return firstIp;
                }
            }
        }

        // Check X-Real-IP header (nginx)
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }

        // Fall back to remote address
        return request.getRemoteAddr();
    }

    private boolean isIpAllowed(String clientIp) {
        if (allowedIps == null || allowedIps.isEmpty()) {
            // If no IPs configured, allow all (when IP whitelist is not required)
            return !requireIpWhitelist;
        }

        Set<String> normalizedAllowedIps = allowedIps.stream()
            .map(String::trim)
            .filter(ip -> !ip.isEmpty())
            .collect(Collectors.toSet());

        if (normalizedAllowedIps.isEmpty()) {
            return !requireIpWhitelist;
        }

        // Check exact match
        if (normalizedAllowedIps.contains(clientIp)) {
            return true;
        }

        // Check CIDR notation (basic support for /24, /16, /8)
        for (String allowedIp : normalizedAllowedIps) {
            if (allowedIp.contains("/") && matchesCidr(clientIp, allowedIp)) {
                return true;
            }
        }

        // Check localhost variations
        if (isLocalhost(clientIp)) {
            return normalizedAllowedIps.stream().anyMatch(this::isLocalhost);
        }

        return false;
    }

    private boolean isLocalhost(String ip) {
        return "127.0.0.1".equals(ip) ||
               "0:0:0:0:0:0:0:1".equals(ip) ||
               "::1".equals(ip) ||
               "localhost".equalsIgnoreCase(ip);
    }

    private boolean matchesCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                log.debug("Invalid CIDR format (missing prefix): {}", cidr);
                return false;
            }

            String network = parts[0];
            int prefix;
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                log.debug("Invalid CIDR prefix: {}", parts[1]);
                return false;
            }

            if (prefix < 0 || prefix > 32) {
                log.debug("CIDR prefix out of range (0-32): {}", prefix);
                return false;
            }

            String[] ipParts = ip.split("\\.");
            String[] networkParts = network.split("\\.");

            if (ipParts.length != 4 || networkParts.length != 4) {
                return false;
            }

            // Validate and parse IP octets
            int ipInt = parseIpToInt(ipParts);
            int networkInt = parseIpToInt(networkParts);

            if (ipInt == -1 || networkInt == -1) {
                return false;
            }

            int mask = prefix == 0 ? 0 : (-1 << (32 - prefix));

            return (ipInt & mask) == (networkInt & mask);

        } catch (NumberFormatException e) {
            log.debug("Failed to parse IP in CIDR check: {}", e.getMessage());
            return false;
        }
    }

    private int parseIpToInt(String[] octets) {
        try {
            int result = 0;
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(octets[i]);
                if (octet < 0 || octet > 255) {
                    return -1; // Invalid octet value
                }
                result |= (octet << (24 - i * 8));
            }
            return result;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean isValidApiKey(String apiKey) {
        if (validApiKeys == null || validApiKeys.isEmpty()) {
            log.warn("No API keys configured but API key validation is required");
            return false;
        }

        // Use constant-time comparison to prevent timing attacks
        byte[] apiKeyBytes = apiKey.getBytes(StandardCharsets.UTF_8);
        return validApiKeys.stream()
            .map(String::trim)
            .filter(key -> !key.isEmpty())
            .anyMatch(key -> MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), apiKeyBytes));
    }

    private void sendError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(String.format(
            "{\"error\": \"%s\", \"status\": %d}", message, status));
    }
}
