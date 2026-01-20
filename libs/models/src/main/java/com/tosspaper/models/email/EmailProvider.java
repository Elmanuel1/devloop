package com.tosspaper.models.email;

/**
 * Provider interface for sending emails through different email service providers (Mailgun, SendGrid, etc.)
 */
public interface EmailProvider {

    /**
     * Send an email using the configured provider
     *
     * @param request The email request containing sender, recipient, subject, and body
     * @return Message ID or confirmation from the email provider
     * @throws RuntimeException if email sending fails
     */
    String sendEmail(EmailRequest request);
}
