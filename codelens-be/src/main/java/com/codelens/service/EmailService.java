package com.codelens.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Slf4j
@Service
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.enabled:false}")
    private boolean emailEnabled;

    @Value("${spring.mail.from:noreply@codelens.dev}")
    private String fromAddress;

    @Value("${spring.mail.from-name:CodeLens}")
    private String fromName;

    @Value("${codelens.base-url:http://localhost:5173}")
    private String baseUrl;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Check if email sending is enabled
     */
    public boolean isEnabled() {
        return emailEnabled;
    }

    /**
     * Send an email asynchronously
     */
    @Async
    public void sendEmail(String to, String subject, String htmlContent) {
        if (!emailEnabled) {
            log.debug("Email sending is disabled. Would have sent to: {}, subject: {}", to, subject);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Email sent successfully to: {}, subject: {}", to, subject);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send email to: {}, subject: {}, error: {}", to, subject, e.getMessage());
        }
    }

    /**
     * Send review completed notification email
     */
    public void sendReviewCompletedEmail(String to, String userName, String reviewId,
            String prTitle, int issuesFound, int criticalIssues) {
        String subject = "Review Completed: " + prTitle;
        String html = buildReviewCompletedEmail(userName, reviewId, prTitle, issuesFound, criticalIssues);
        sendEmail(to, subject, html);
    }

    /**
     * Send review failed notification email
     */
    public void sendReviewFailedEmail(String to, String userName, String reviewId,
            String prTitle, String errorMessage) {
        String subject = "Review Failed: " + prTitle;
        String html = buildReviewFailedEmail(userName, reviewId, prTitle, errorMessage);
        sendEmail(to, subject, html);
    }

    /**
     * Send critical issues found notification email
     */
    public void sendCriticalIssuesEmail(String to, String userName, String reviewId,
            String prTitle, int criticalCount) {
        String subject = "Critical Issues Found: " + prTitle;
        String html = buildCriticalIssuesEmail(userName, reviewId, prTitle, criticalCount);
        sendEmail(to, subject, html);
    }

    private String buildReviewCompletedEmail(String userName, String reviewId,
            String prTitle, int issuesFound, int criticalIssues) {
        String reviewUrl = baseUrl + "/reviews/" + reviewId;
        String issuesSummary = criticalIssues > 0
            ? String.format("<span style=\"color: #dc2626;\">%d critical</span> out of %d total issues", criticalIssues, issuesFound)
            : String.format("%d issues found", issuesFound);

        return buildEmailTemplate(
            "Review Completed",
            String.format("Hi %s,", userName),
            String.format("Your code review for <strong>%s</strong> has been completed.", prTitle),
            issuesSummary,
            reviewUrl,
            "View Review"
        );
    }

    private String buildReviewFailedEmail(String userName, String reviewId,
            String prTitle, String errorMessage) {
        String reviewUrl = baseUrl + "/reviews/" + reviewId;
        return buildEmailTemplate(
            "Review Failed",
            String.format("Hi %s,", userName),
            String.format("Your code review for <strong>%s</strong> has failed.", prTitle),
            String.format("Error: %s", errorMessage != null ? errorMessage : "Unknown error"),
            reviewUrl,
            "View Details"
        );
    }

    private String buildCriticalIssuesEmail(String userName, String reviewId,
            String prTitle, int criticalCount) {
        String reviewUrl = baseUrl + "/reviews/" + reviewId;
        return buildEmailTemplate(
            "Critical Issues Found",
            String.format("Hi %s,", userName),
            String.format("Your code review for <strong>%s</strong> found critical security or bug issues.", prTitle),
            String.format("<span style=\"color: #dc2626; font-weight: bold;\">%d critical issue%s</span> require%s immediate attention.",
                criticalCount, criticalCount > 1 ? "s" : "", criticalCount > 1 ? "" : "s"),
            reviewUrl,
            "View Issues"
        );
    }

    private String buildEmailTemplate(String title, String greeting, String message,
            String details, String actionUrl, String actionText) {
        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f3f4f6;">
                <table width="100%%" cellpadding="0" cellspacing="0" style="background-color: #f3f4f6; padding: 40px 20px;">
                    <tr>
                        <td align="center">
                            <table width="100%%" cellpadding="0" cellspacing="0" style="max-width: 600px; background-color: #ffffff; border-radius: 12px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.05);">
                                <!-- Header -->
                                <tr>
                                    <td style="background-color: #78350f; padding: 24px 32px;">
                                        <h1 style="margin: 0; color: #ffffff; font-size: 24px; font-weight: 600;">%s</h1>
                                    </td>
                                </tr>
                                <!-- Content -->
                                <tr>
                                    <td style="padding: 32px;">
                                        <p style="margin: 0 0 16px; color: #374151; font-size: 16px;">%s</p>
                                        <p style="margin: 0 0 16px; color: #374151; font-size: 16px;">%s</p>
                                        <p style="margin: 0 0 24px; color: #6b7280; font-size: 14px;">%s</p>
                                        <a href="%s" style="display: inline-block; background-color: #78350f; color: #ffffff; text-decoration: none; padding: 12px 24px; border-radius: 8px; font-weight: 500; font-size: 14px;">%s</a>
                                    </td>
                                </tr>
                                <!-- Footer -->
                                <tr>
                                    <td style="padding: 24px 32px; background-color: #f9fafb; border-top: 1px solid #e5e7eb;">
                                        <p style="margin: 0; color: #9ca3af; font-size: 12px; text-align: center;">
                                            This email was sent by CodeLens.
                                            <a href="%s/settings/notifications" style="color: #78350f;">Manage notification preferences</a>
                                        </p>
                                    </td>
                                </tr>
                            </table>
                        </td>
                    </tr>
                </table>
            </body>
            </html>
            """, title, greeting, message, details, actionUrl, actionText, baseUrl);
    }
}
