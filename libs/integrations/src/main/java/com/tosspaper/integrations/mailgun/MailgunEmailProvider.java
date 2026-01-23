package com.tosspaper.integrations.mailgun;

import com.mailgun.api.v3.MailgunMessagesApi;
import com.mailgun.model.message.Message;
import com.mailgun.model.message.MessageResponse;
import com.tosspaper.models.email.EmailProvider;
import com.tosspaper.models.email.EmailRequest;
import com.tosspaper.models.config.MailgunProperties;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailgunEmailProvider implements EmailProvider {

    private final MailgunMessagesApi mailgunMessagesApi;
    private final MailgunProperties mailgunProperties;

    @Override
    public String sendEmail(EmailRequest request) {
        try {
            Message.MessageBuilder messageBuilder = Message.builder()
                    .from(request.from())
                    .to(request.to())
                    .subject(request.subject());

            if (request.textBody() != null) {
                messageBuilder.text(request.textBody());
            }
            if (request.htmlBody() != null) {
                messageBuilder.html(request.htmlBody());
            }
            
            // TODO: Handle attachments if needed, currently not mapping them deeply but interface supports it
            
            Message message = messageBuilder.build();

            log.debug("Sending email via Mailgun - Domain: {}", mailgunProperties.getDomain());

            MessageResponse response = mailgunMessagesApi.sendMessage(mailgunProperties.getDomain(), message);
            return response.getMessage();

        } catch (FeignException.Unauthorized e) {
            log.error("Mailgun authentication failed - Check API key configuration. Domain: {}, From: {}",
                    mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), e);
            throw new RuntimeException("Mailgun authentication failed", e);
        } catch (Exception e) {
            log.error("Failed to send email via Mailgun", e);
            throw new RuntimeException("Failed to send email via Mailgun", e);
        }
    }
}
