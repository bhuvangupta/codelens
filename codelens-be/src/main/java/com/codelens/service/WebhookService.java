package com.codelens.service;

import com.codelens.model.entity.Review;
import com.codelens.model.entity.WebhookConfig;
import com.codelens.model.entity.WebhookDelivery;
import com.codelens.repository.WebhookConfigRepository;
import com.codelens.repository.WebhookDeliveryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Service for delivering webhooks to external endpoints.
 *
 * <h2>Webhook Headers</h2>
 * Each webhook delivery includes the following headers:
 * <ul>
 *   <li>{@code X-CodeLens-Event} - The event type (e.g., "review.completed")</li>
 *   <li>{@code X-CodeLens-Delivery} - Unique delivery ID (UUID)</li>
 *   <li>{@code X-CodeLens-Signature} - HMAC-SHA256 signature (if secret configured)</li>
 * </ul>
 *
 * <h2>Signature Verification</h2>
 * The signature header format is: {@code sha256=<hex-encoded-hmac>}
 * <p>
 * To verify the signature on your server:
 * <pre>{@code
 * // Node.js example:
 * const crypto = require('crypto');
 * const signature = request.headers['x-codelens-signature'];
 * const payload = JSON.stringify(request.body);
 * const expected = 'sha256=' + crypto
 *     .createHmac('sha256', webhookSecret)
 *     .update(payload, 'utf8')
 *     .digest('hex');
 * const valid = crypto.timingSafeEqual(
 *     Buffer.from(signature),
 *     Buffer.from(expected)
 * );
 *
 * // Java example:
 * Mac mac = Mac.getInstance("HmacSHA256");
 * mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
 * String expected = "sha256=" + Hex.encodeHexString(mac.doFinal(payload.getBytes(UTF_8)));
 * boolean valid = MessageDigest.isEqual(signature.getBytes(), expected.getBytes());
 * }</pre>
 */
@Slf4j
@Service
public class WebhookService {

    private final WebhookConfigRepository webhookConfigRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    // Webhook header names (documented for consumers)
    public static final String HEADER_EVENT = "X-CodeLens-Event";
    public static final String HEADER_DELIVERY = "X-CodeLens-Delivery";
    public static final String HEADER_SIGNATURE = "X-CodeLens-Signature";
    public static final String SIGNATURE_PREFIX = "sha256=";

    // Configurable settings with defaults
    private final int maxFailures;
    private final int maxPayloadBytes;
    private final int[] backoffHours;
    private final int maxRetryCount;

    public WebhookService(
            WebhookConfigRepository webhookConfigRepository,
            WebhookDeliveryRepository webhookDeliveryRepository,
            RateLimitService rateLimitService,
            @Value("${codelens.webhooks.max-failures:5}") int maxFailures,
            @Value("${codelens.webhooks.max-payload-kb:256}") int maxPayloadKb,
            @Value("${codelens.webhooks.backoff-hours:1,2,4,8,24}") int[] backoffHours,
            @Value("${codelens.webhooks.max-retry-count:10}") int maxRetryCount) {
        this.webhookConfigRepository = webhookConfigRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.rateLimitService = rateLimitService;
        this.maxFailures = maxFailures;
        this.maxPayloadBytes = maxPayloadKb * 1024;
        this.backoffHours = backoffHours;
        this.maxRetryCount = maxRetryCount;
    }

    // SSRF Protection: Allowed webhook domains (null = allow all public URLs)
    // Set this to restrict webhooks to specific services only
    private static final Set<String> ALLOWED_DOMAINS = Set.of(
            // Slack
            "hooks.slack.com",
            // Discord
            "discord.com",
            "discordapp.com",
            // Microsoft Teams
            "webhook.office.com",
            // Google Chat
            "chat.googleapis.com",
            // Generic webhook testing
            "webhook.site",
            // PagerDuty
            "events.pagerduty.com",
            // Opsgenie
            "api.opsgenie.com"
    );

    // Set to true to enforce domain allowlist, false to just block internal IPs
    private static final boolean ENFORCE_DOMAIN_ALLOWLIST = false;

    // Blocked hostnames and patterns for SSRF protection
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost",
            "127.0.0.1",
            "0.0.0.0",
            "::1",
            "[::1]",
            "169.254.169.254",  // AWS/GCP metadata
            "metadata.google.internal",  // GCP metadata
            "metadata.internal"  // Generic cloud metadata
    );

    /**
     * Get all webhooks for an organization
     */
    public List<WebhookConfig> getWebhooksForOrganization(UUID organizationId) {
        return webhookConfigRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
    }

    /**
     * Get a webhook by ID
     */
    public Optional<WebhookConfig> getWebhook(UUID webhookId) {
        return webhookConfigRepository.findById(webhookId);
    }

    /**
     * Create a new webhook
     */
    @Transactional
    public WebhookConfig createWebhook(CreateWebhookRequest request, com.codelens.model.entity.Organization organization) {
        // SECURITY: Validate URL to prevent SSRF attacks
        validateWebhookUrl(request.url());

        WebhookConfig webhook = WebhookConfig.builder()
                .organization(organization)
                .name(request.name())
                .url(request.url())
                .secret(request.secret())
                .events(request.events())
                .enabled(request.enabled() != null ? request.enabled() : true)
                .build();

        log.info("Created webhook '{}' for organization {}", webhook.getName(), organization.getName());
        return webhookConfigRepository.save(webhook);
    }

    /**
     * Update an existing webhook
     */
    @Transactional
    public WebhookConfig updateWebhook(UUID webhookId, UpdateWebhookRequest request) {
        WebhookConfig webhook = webhookConfigRepository.findById(webhookId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook not found: " + webhookId));

        // SECURITY: Validate URL if it's being updated
        if (request.url() != null) {
            validateWebhookUrl(request.url());
            webhook.setUrl(request.url());
        }

        if (request.name() != null) webhook.setName(request.name());
        if (request.secret() != null) webhook.setSecret(request.secret());
        if (request.events() != null) webhook.setEvents(request.events());
        if (request.enabled() != null) webhook.setEnabled(request.enabled());

        // Reset failure/retry state if re-enabled
        if (Boolean.TRUE.equals(request.enabled())) {
            webhook.setFailureCount(0);
            webhook.setRetryCount(0);
            webhook.setRetryAt(null);
            webhook.setDisabledAt(null);
        }

        log.info("Updated webhook '{}'", webhook.getName());
        return webhookConfigRepository.save(webhook);
    }

    /**
     * Delete a webhook
     */
    @Transactional
    public void deleteWebhook(UUID webhookId) {
        webhookConfigRepository.deleteById(webhookId);
        log.info("Deleted webhook {}", webhookId);
    }

    /**
     * Scheduled task to re-enable webhooks that are ready for retry.
     * Runs every 15 minutes to check for webhooks past their retry time.
     */
    @Scheduled(fixedRate = 15 * 60 * 1000) // Every 15 minutes
    @Transactional
    public void processWebhookRetries() {
        List<WebhookConfig> readyForRetry = webhookConfigRepository.findWebhooksReadyForRetry(LocalDateTime.now());

        if (readyForRetry.isEmpty()) {
            return;
        }

        log.info("Found {} webhooks ready for retry", readyForRetry.size());

        for (WebhookConfig webhook : readyForRetry) {
            reEnableForRetry(webhook);
        }
    }

    /**
     * Re-enable a webhook for retry after backoff period.
     * Uses atomic increment to prevent lost updates when multiple retries occur.
     */
    private void reEnableForRetry(WebhookConfig webhook) {
        // Use atomic update to increment retry count and enable in one operation
        // This prevents lost updates if multiple threads try to re-enable simultaneously
        int updated = webhookConfigRepository.incrementRetryCountAndEnable(webhook.getId());
        if (updated > 0) {
            // Refresh to get the new retry count for logging
            webhookConfigRepository.findById(webhook.getId()).ifPresent(w ->
                log.info("Auto-enabled webhook '{}' for retry attempt {}/{}",
                    w.getName(), w.getRetryCount(), maxRetryCount));
        }
    }

    /**
     * Trigger webhooks for an event
     */
    @Async("webhookTaskExecutor")
    public void triggerWebhooks(UUID organizationId, String eventType, Review review) {
        // Rate limit check per organization
        if (!rateLimitService.allowWebhookDelivery(organizationId)) {
            log.warn("Webhook rate limit exceeded for organization {}. Event '{}' will not be delivered.",
                    organizationId, eventType);
            return;
        }

        List<WebhookConfig> webhooks = webhookConfigRepository.findByOrganizationIdAndEnabledTrue(organizationId);

        for (WebhookConfig webhook : webhooks) {
            if (webhook.getEvents() != null && webhook.getEvents().contains(eventType)) {
                deliverWebhook(webhook, eventType, review);
            }
        }
    }

    /**
     * Deliver a webhook
     */
    private void deliverWebhook(WebhookConfig webhook, String eventType, Review review) {
        long startTime = System.currentTimeMillis();

        try {
            // Build payload
            Map<String, Object> payload = buildPayload(eventType, review);
            String payloadJson = objectMapper.writeValueAsString(payload);

            // Validate payload size
            int payloadSize = payloadJson.getBytes(StandardCharsets.UTF_8).length;
            if (payloadSize > maxPayloadBytes) {
                log.warn("Webhook payload too large ({} bytes, max {}). Truncating for webhook {}",
                        payloadSize, maxPayloadBytes, webhook.getName());
                // Create minimal payload with size warning
                payload = buildMinimalPayload(eventType, review, payloadSize);
                payloadJson = objectMapper.writeValueAsString(payload);
            }

            // Build headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HEADER_EVENT, eventType);
            headers.set(HEADER_DELIVERY, UUID.randomUUID().toString());

            // Add HMAC-SHA256 signature if secret is configured
            // Format: "sha256=<hex-encoded-hmac>" (similar to GitHub webhook signatures)
            if (webhook.getSecret() != null && !webhook.getSecret().isEmpty()) {
                String signature = computeHmacSignature(payloadJson, webhook.getSecret());
                headers.set(HEADER_SIGNATURE, SIGNATURE_PREFIX + signature);
            }

            // Send request
            HttpEntity<String> request = new HttpEntity<>(payloadJson, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    webhook.getUrl(), HttpMethod.POST, request, String.class);

            int durationMs = (int) (System.currentTimeMillis() - startTime);

            // Record successful delivery
            recordDelivery(webhook, eventType, payloadJson, response.getStatusCode().value(), true, durationMs, null);

            // Reset failure and retry state on success
            if (webhook.getFailureCount() > 0 || webhook.getRetryCount() > 0) {
                webhook.setFailureCount(0);
                webhook.setRetryCount(0);
                webhook.setRetryAt(null);
                webhook.setDisabledAt(null);
                webhookConfigRepository.save(webhook);
            }

            log.info("Webhook {} delivered successfully for event {}", webhook.getName(), eventType);

        } catch (Exception e) {
            int durationMs = (int) (System.currentTimeMillis() - startTime);
            String payloadJson = "";
            try {
                payloadJson = objectMapper.writeValueAsString(buildPayload(eventType, review));
            } catch (Exception ignored) {}

            // Record failed delivery
            recordDelivery(webhook, eventType, payloadJson, null, false, durationMs, e.getMessage());

            // Increment failure count and apply exponential backoff
            webhook.setFailureCount(webhook.getFailureCount() + 1);
            if (webhook.getFailureCount() >= maxFailures) {
                disableWithBackoff(webhook);
            }
            webhookConfigRepository.save(webhook);

            log.error("Webhook {} delivery failed for event {}: {}", webhook.getName(), eventType, e.getMessage());
        }
    }

    /**
     * Disable webhook with exponential backoff for auto-retry.
     * Backoff sequence is configurable (default: 1h, 2h, 4h, 8h, 24h).
     * After maxRetryCount retries, webhook stays disabled (no auto-retry).
     */
    private void disableWithBackoff(WebhookConfig webhook) {
        webhook.setEnabled(false);
        webhook.setDisabledAt(LocalDateTime.now());

        int retryCount = webhook.getRetryCount() != null ? webhook.getRetryCount() : 0;

        if (retryCount >= maxRetryCount) {
            // Too many retries, disable permanently until manual intervention
            webhook.setRetryAt(null);
            log.warn("Webhook {} permanently disabled after {} retry attempts. Manual re-enable required.",
                    webhook.getName(), retryCount);
        } else {
            // Calculate backoff hours based on retry count
            int backoffIndex = Math.min(retryCount, backoffHours.length - 1);
            int backoffHoursValue = backoffHours[backoffIndex];
            webhook.setRetryAt(LocalDateTime.now().plusHours(backoffHoursValue));
            log.warn("Webhook {} disabled after {} failures. Will auto-retry in {} hour(s) (attempt {}/{})",
                    webhook.getName(), maxFailures, backoffHoursValue, retryCount + 1, maxRetryCount);
        }
    }

    private Map<String, Object> buildPayload(String eventType, Review review) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", eventType);

        Map<String, Object> reviewData = new LinkedHashMap<>();
        reviewData.put("id", review.getId().toString());
        reviewData.put("prNumber", review.getPrNumber());
        reviewData.put("prTitle", review.getPrTitle());
        reviewData.put("prUrl", review.getPrUrl());
        reviewData.put("status", review.getStatus().name());
        reviewData.put("issuesFound", review.getIssuesFound());
        reviewData.put("criticalIssues", review.getCriticalIssues());
        reviewData.put("highIssues", review.getHighIssues());
        reviewData.put("mediumIssues", review.getMediumIssues());
        reviewData.put("lowIssues", review.getLowIssues());
        payload.put("review", reviewData);

        payload.put("timestamp", LocalDateTime.now().atOffset(ZoneOffset.UTC).toString());

        return payload;
    }

    /**
     * Build a minimal payload when the full payload exceeds size limits.
     * Includes only essential fields and a warning about truncation.
     */
    private Map<String, Object> buildMinimalPayload(String eventType, Review review, int originalSize) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", eventType);
        payload.put("truncated", true);
        payload.put("originalSizeBytes", originalSize);

        Map<String, Object> reviewData = new LinkedHashMap<>();
        reviewData.put("id", review.getId().toString());
        reviewData.put("prNumber", review.getPrNumber());
        reviewData.put("prUrl", review.getPrUrl());
        reviewData.put("status", review.getStatus().name());
        reviewData.put("issuesFound", review.getIssuesFound());
        reviewData.put("criticalIssues", review.getCriticalIssues());
        payload.put("review", reviewData);

        payload.put("timestamp", LocalDateTime.now().atOffset(ZoneOffset.UTC).toString());
        payload.put("message", "Payload truncated due to size limit. Fetch full details via API.");

        return payload;
    }

    private void recordDelivery(WebhookConfig webhook, String eventType, String payload,
                                  Integer statusCode, boolean success, int durationMs, String errorMessage) {
        webhook.setLastDeliveryAt(LocalDateTime.now());
        webhookConfigRepository.save(webhook);

        WebhookDelivery delivery = WebhookDelivery.builder()
                .webhookConfig(webhook)
                .eventType(eventType)
                .payload(payload)
                .statusCode(statusCode)
                .success(success)
                .durationMs(durationMs)
                .errorMessage(errorMessage)
                .build();

        webhookDeliveryRepository.save(delivery);
    }

    private String computeHmacSignature(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : hmacBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to compute HMAC signature", e);
            return "";
        }
    }

    /**
     * Validate webhook URL to prevent SSRF attacks.
     * Blocks internal IPs, localhost, and cloud metadata endpoints.
     *
     * @throws IllegalArgumentException if URL is not allowed
     */
    private void validateWebhookUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Webhook URL is required");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid webhook URL format: " + e.getMessage());
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Webhook URL must contain a valid host");
        }

        String hostLower = host.toLowerCase();

        // Check blocked hosts
        if (BLOCKED_HOSTS.contains(hostLower)) {
            log.warn("SSRF attempt blocked: webhook URL to blocked host '{}'", host);
            throw new IllegalArgumentException("Webhook URL not allowed: internal or metadata endpoint");
        }

        // Check for IP address patterns that could be internal
        if (isPrivateOrInternalIp(host)) {
            log.warn("SSRF attempt blocked: webhook URL to private/internal IP '{}'", host);
            throw new IllegalArgumentException("Webhook URL not allowed: private or internal IP address");
        }

        // If domain allowlist is enforced, check against it
        if (ENFORCE_DOMAIN_ALLOWLIST) {
            boolean allowed = ALLOWED_DOMAINS.stream()
                    .anyMatch(domain -> hostLower.equals(domain) || hostLower.endsWith("." + domain));
            if (!allowed) {
                log.warn("Webhook URL to non-allowlisted domain rejected: '{}'", host);
                throw new IllegalArgumentException(
                        "Webhook URL domain not in allowlist. Allowed: " + String.join(", ", ALLOWED_DOMAINS));
            }
        }

        // Resolve hostname and check if it resolves to a private IP
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() ||
                        addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                    log.warn("SSRF attempt blocked: '{}' resolves to internal address {}", host, addr.getHostAddress());
                    throw new IllegalArgumentException("Webhook URL not allowed: resolves to internal address");
                }
            }
        } catch (UnknownHostException e) {
            // If we can't resolve, allow it - the webhook will just fail on delivery
            log.debug("Could not resolve webhook host '{}', allowing anyway", host);
        }

        log.debug("Webhook URL validated: {}", url);
    }

    /**
     * Check if a host string represents a private or internal IP address.
     * Uses InetAddress for comprehensive IPv4/IPv6 detection including mapped addresses.
     */
    private boolean isPrivateOrInternalIp(String host) {
        // First try to parse as IP address directly using InetAddress
        // This handles all formats including IPv4-mapped IPv6 (::ffff:127.0.0.1)
        try {
            // Remove brackets from IPv6 addresses like [::1]
            String cleanHost = host.startsWith("[") && host.endsWith("]")
                    ? host.substring(1, host.length() - 1)
                    : host;

            InetAddress addr = InetAddress.getByName(cleanHost);

            // Check for all internal address types
            if (addr.isLoopbackAddress() ||      // 127.x.x.x, ::1
                    addr.isSiteLocalAddress() ||     // 10.x, 172.16-31.x, 192.168.x, fc00::/7
                    addr.isLinkLocalAddress() ||     // 169.254.x.x, fe80::/10
                    addr.isAnyLocalAddress()) {      // 0.0.0.0, ::
                return true;
            }

            // Additional check for IPv4-mapped IPv6 addresses
            // InetAddress.getByName("::ffff:127.0.0.1") returns Inet6Address
            // but we need to check the mapped IPv4 address too
            byte[] addrBytes = addr.getAddress();
            if (addrBytes.length == 16) {
                // Check if it's an IPv4-mapped IPv6 address (::ffff:x.x.x.x)
                boolean isV4Mapped = true;
                for (int i = 0; i < 10; i++) {
                    if (addrBytes[i] != 0) {
                        isV4Mapped = false;
                        break;
                    }
                }
                if (isV4Mapped && addrBytes[10] == (byte) 0xff && addrBytes[11] == (byte) 0xff) {
                    // Extract the IPv4 part and check it
                    byte[] v4Bytes = new byte[4];
                    System.arraycopy(addrBytes, 12, v4Bytes, 0, 4);
                    InetAddress v4Addr = InetAddress.getByAddress(v4Bytes);
                    if (v4Addr.isLoopbackAddress() || v4Addr.isSiteLocalAddress() ||
                            v4Addr.isLinkLocalAddress() || v4Addr.isAnyLocalAddress()) {
                        return true;
                    }
                }
            }

            return false;
        } catch (UnknownHostException e) {
            // If we can't parse it, fall back to pattern matching
            log.debug("Could not parse host as IP, falling back to pattern matching: {}", host);
        }

        // Fallback: Pattern matching for edge cases
        // IPv4 private ranges
        if (host.matches("^10\\..*") ||                          // 10.0.0.0/8
                host.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*") || // 172.16.0.0/12
                host.matches("^192\\.168\\..*") ||                    // 192.168.0.0/16
                host.matches("^127\\..*") ||                          // 127.0.0.0/8 (loopback)
                host.matches("^169\\.254\\..*") ||                    // 169.254.0.0/16 (link-local)
                host.matches("^0\\..*")) {                            // 0.0.0.0/8
            return true;
        }

        // IPv6 patterns
        String hostLower = host.toLowerCase();
        if (hostLower.startsWith("fe80:") ||   // Link-local
                hostLower.startsWith("fc") ||      // Unique local
                hostLower.startsWith("fd") ||      // Unique local
                hostLower.equals("::1") ||         // Loopback
                hostLower.startsWith("::ffff:")) { // IPv4-mapped
            return true;
        }

        return false;
    }

    // Request DTOs
    public record CreateWebhookRequest(
            @NotBlank(message = "Webhook name is required")
            @Size(max = 255, message = "Name must be at most 255 characters")
            String name,

            @NotBlank(message = "Webhook URL is required")
            @Pattern(regexp = "^https?://.*", message = "URL must start with http:// or https://")
            @Size(max = 1000, message = "URL must be at most 1000 characters")
            String url,

            @Size(max = 255, message = "Secret must be at most 255 characters")
            String secret,

            @NotEmpty(message = "At least one event must be selected")
            List<String> events,

            Boolean enabled
    ) {}

    public record UpdateWebhookRequest(
            @Size(max = 255, message = "Name must be at most 255 characters")
            String name,

            @Pattern(regexp = "^https?://.*", message = "URL must start with http:// or https://")
            @Size(max = 1000, message = "URL must be at most 1000 characters")
            String url,

            @Size(max = 255, message = "Secret must be at most 255 characters")
            String secret,

            List<String> events,

            Boolean enabled
    ) {}
}
