package com.codelens.service;

import com.codelens.model.entity.*;
import com.codelens.repository.NotificationPreferenceRepository;
import com.codelens.repository.NotificationRepository;
import com.codelens.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;
    private final WebhookService webhookService;

    /**
     * Get notifications for a user
     */
    public Page<Notification> getNotificationsForUser(UUID userId, int page, int size) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
    }

    /**
     * Get unread count for a user
     */
    public long getUnreadCount(UUID userId) {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    /**
     * Get a notification by ID
     */
    public Optional<Notification> getNotification(UUID notificationId) {
        return notificationRepository.findById(notificationId);
    }

    /**
     * Mark a notification as read
     */
    @Transactional
    public void markAsRead(UUID notificationId) {
        notificationRepository.findById(notificationId).ifPresent(notification -> {
            notification.setIsRead(true);
            notificationRepository.save(notification);
        });
    }

    /**
     * Mark all notifications as read for a user
     */
    @Transactional
    public int markAllAsRead(UUID userId) {
        return notificationRepository.markAllAsReadForUser(userId);
    }

    /**
     * Get or create notification preferences for a user
     */
    public NotificationPreference getPreferencesForUser(UUID userId) {
        return preferenceRepository.findByUserId(userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
                    NotificationPreference prefs = NotificationPreference.builder()
                            .user(user)
                            .build();
                    return preferenceRepository.save(prefs);
                });
    }

    /**
     * Update notification preferences
     */
    @Transactional
    public NotificationPreference updatePreferences(UUID userId, UpdatePreferencesRequest request) {
        NotificationPreference prefs = getPreferencesForUser(userId);

        if (request.emailEnabled() != null) prefs.setEmailEnabled(request.emailEnabled());
        if (request.inAppEnabled() != null) prefs.setInAppEnabled(request.inAppEnabled());
        if (request.reviewCompleted() != null) prefs.setReviewCompleted(request.reviewCompleted());
        if (request.reviewFailed() != null) prefs.setReviewFailed(request.reviewFailed());
        if (request.criticalIssues() != null) prefs.setCriticalIssues(request.criticalIssues());

        return preferenceRepository.save(prefs);
    }

    /**
     * Notify users when a review is completed
     */
    @Transactional
    public void notifyReviewCompleted(Review review) {
        if (review.getUser() == null) return;

        NotificationPreference prefs = getPreferencesForUser(review.getUser().getId());
        if (!Boolean.TRUE.equals(prefs.getInAppEnabled()) || !Boolean.TRUE.equals(prefs.getReviewCompleted())) {
            return;
        }

        String title = "Review Completed";
        String message = String.format("Review for PR #%d (%s) has completed. Found %d issues.",
                review.getPrNumber(), review.getPrTitle(), review.getIssuesFound());

        createNotification(review.getUser(), Notification.NotificationType.REVIEW_COMPLETED,
                title, message, "review", review.getId());

        // Trigger webhooks
        if (review.getRepository() != null && review.getRepository().getOrganization() != null) {
            webhookService.triggerWebhooks(review.getRepository().getOrganization().getId(),
                    WebhookConfig.EVENT_REVIEW_COMPLETED, review);
        }

        // Check for critical issues
        if (review.getCriticalIssues() != null && review.getCriticalIssues() > 0) {
            notifyCriticalIssuesFound(review);
        }
    }

    /**
     * Notify users when a review fails
     */
    @Transactional
    public void notifyReviewFailed(Review review) {
        if (review.getUser() == null) return;

        NotificationPreference prefs = getPreferencesForUser(review.getUser().getId());
        if (!Boolean.TRUE.equals(prefs.getInAppEnabled()) || !Boolean.TRUE.equals(prefs.getReviewFailed())) {
            return;
        }

        String title = "Review Failed";
        String message = String.format("Review for PR #%d (%s) has failed. %s",
                review.getPrNumber(), review.getPrTitle(),
                review.getErrorMessage() != null ? review.getErrorMessage() : "");

        createNotification(review.getUser(), Notification.NotificationType.REVIEW_FAILED,
                title, message, "review", review.getId());

        // Trigger webhooks
        if (review.getRepository() != null && review.getRepository().getOrganization() != null) {
            webhookService.triggerWebhooks(review.getRepository().getOrganization().getId(),
                    WebhookConfig.EVENT_REVIEW_FAILED, review);
        }
    }

    /**
     * Notify users when critical issues are found
     */
    private void notifyCriticalIssuesFound(Review review) {
        if (review.getUser() == null) return;

        NotificationPreference prefs = getPreferencesForUser(review.getUser().getId());
        if (!Boolean.TRUE.equals(prefs.getInAppEnabled()) || !Boolean.TRUE.equals(prefs.getCriticalIssues())) {
            return;
        }

        String title = "Critical Issues Found";
        String message = String.format("PR #%d (%s) has %d critical issue%s that require immediate attention.",
                review.getPrNumber(), review.getPrTitle(), review.getCriticalIssues(),
                review.getCriticalIssues() > 1 ? "s" : "");

        createNotification(review.getUser(), Notification.NotificationType.CRITICAL_ISSUES_FOUND,
                title, message, "review", review.getId());

        // Trigger webhooks
        if (review.getRepository() != null && review.getRepository().getOrganization() != null) {
            webhookService.triggerWebhooks(review.getRepository().getOrganization().getId(),
                    WebhookConfig.EVENT_CRITICAL_ISSUES, review);
        }
    }

    /**
     * Create a notification
     */
    private void createNotification(User user, Notification.NotificationType type,
                                     String title, String message, String referenceType, UUID referenceId) {
        Notification notification = Notification.builder()
                .user(user)
                .type(type)
                .title(title)
                .message(message)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .build();

        notificationRepository.save(notification);
        log.info("Created notification for user {}: {}", user.getEmail(), title);
    }

    // Request DTOs
    public record UpdatePreferencesRequest(
            Boolean emailEnabled,
            Boolean inAppEnabled,
            Boolean reviewCompleted,
            Boolean reviewFailed,
            Boolean criticalIssues
    ) {}
}
