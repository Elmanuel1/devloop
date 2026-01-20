package com.tosspaper.models.email;

import java.util.List;

/**
 * Request object for sending emails through an email provider
 *
 * @param from      Sender email address
 * @param to        Recipient email address (or comma-separated list)
 * @param subject   Email subject
 * @param textBody  Plain text email body (optional)
 * @param htmlBody  HTML email body (optional)
 * @param attachments List of attachment file paths or URLs (optional)
 */
public record EmailRequest(
        String from,
        String to,
        String subject,
        String textBody,
        String htmlBody,
        List<String> attachments
) {
    /**
     * Builder for creating EmailRequest with only required fields
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String from;
        private String to;
        private String subject;
        private String textBody;
        private String htmlBody;
        private List<String> attachments;

        public Builder from(String from) {
            this.from = from;
            return this;
        }

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder textBody(String textBody) {
            this.textBody = textBody;
            return this;
        }

        public Builder htmlBody(String htmlBody) {
            this.htmlBody = htmlBody;
            return this;
        }

        public Builder attachments(List<String> attachments) {
            this.attachments = attachments;
            return this;
        }

        public EmailRequest build() {
            return new EmailRequest(from, to, subject, textBody, htmlBody, attachments);
        }
    }
}
