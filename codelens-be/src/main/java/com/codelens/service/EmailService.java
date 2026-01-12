package com.codelens.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Slf4j
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${spring.mail.enabled:false}")
    private boolean emailEnabled;

    @Value("${spring.mail.from:noreply@codelens.dev}")
    private String fromAddress;

    @Value("${spring.mail.from-name:CodeLens}")
    private String fromName;

    @Value("${codelens.base-url:http://localhost:5173}")
    private String baseUrl;

    public EmailService(JavaMailSender mailSender, TemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
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

        Context context = new Context();
        context.setVariable("userName", userName);
        context.setVariable("prTitle", prTitle);
        context.setVariable("reviewUrl", baseUrl + "/reviews/" + reviewId);
        context.setVariable("issuesFound", issuesFound);
        context.setVariable("criticalIssues", criticalIssues);
        context.setVariable("baseUrl", baseUrl);

        String html = templateEngine.process("email/review-completed", context);
        sendEmail(to, subject, html);
    }

    /**
     * Send review failed notification email
     */
    public void sendReviewFailedEmail(String to, String userName, String reviewId,
            String prTitle, String errorMessage) {
        String subject = "Review Failed: " + prTitle;

        Context context = new Context();
        context.setVariable("userName", userName);
        context.setVariable("prTitle", prTitle);
        context.setVariable("reviewUrl", baseUrl + "/reviews/" + reviewId);
        context.setVariable("errorMessage", errorMessage);
        context.setVariable("baseUrl", baseUrl);

        String html = templateEngine.process("email/review-failed", context);
        sendEmail(to, subject, html);
    }

    /**
     * Send critical issues found notification email
     */
    public void sendCriticalIssuesEmail(String to, String userName, String reviewId,
            String prTitle, int criticalCount) {
        String subject = "Critical Issues Found: " + prTitle;

        Context context = new Context();
        context.setVariable("userName", userName);
        context.setVariable("prTitle", prTitle);
        context.setVariable("reviewUrl", baseUrl + "/reviews/" + reviewId);
        context.setVariable("criticalCount", criticalCount);
        context.setVariable("baseUrl", baseUrl);

        String html = templateEngine.process("email/critical-issues", context);
        sendEmail(to, subject, html);
    }
}
