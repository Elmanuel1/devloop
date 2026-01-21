package com.tosspaper.emailengine.service;

import com.mailgun.api.v3.MailgunMessagesApi;
import com.mailgun.model.message.Message;
import com.mailgun.model.message.MessageResponse;
import com.tosspaper.models.config.MailgunProperties;
import com.tosspaper.models.service.CompanyLookupService;
import com.tosspaper.models.service.SenderApprovalNotificationService;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Implementation of SenderApprovalNotificationService.
 * Sends email notifications to company owners when new senders require approval.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SenderApprovalNotificationServiceImpl implements SenderApprovalNotificationService {
    
    private final MailgunMessagesApi mailgunMessagesApi;
    private final MailgunProperties mailgunProperties;
    private final SenderApprovalEmailContentBuilder emailContentBuilder;
    private final CompanyLookupService companyLookupService;
    
    @Override
    public void sendPendingSenderApprovalNotification(String senderEmail, Long companyId) {
        try {
            // Get company owner email
            var companyInfo = companyLookupService.getCompanyById(companyId);
            String ownerEmail = companyInfo.ownerEmail();
            
            // If owner email is not available, we can't send the email
            if (ownerEmail == null || ownerEmail.isBlank()) {
                log.warn("Cannot send sender approval notification: company {} has no owner email configured", companyId);
                return;
            }
        
            // Build email content
            String subject = emailContentBuilder.buildSubject(senderEmail);
            String body = emailContentBuilder.buildBody(senderEmail, companyInfo.name(), OffsetDateTime.now());
            
            // Send email via Mailgun
            Message message = Message.builder()
                    .from(mailgunProperties.getFromEmail())
                    .to(List.of(ownerEmail))
                    .subject(subject)
                    .text(body)
                    .build();
            
            log.debug("Sending sender approval notification via Mailgun - Domain: {}, From: {}, To: {}, Subject: {}",
                    mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), ownerEmail, subject);
            
            MessageResponse response = mailgunMessagesApi.sendMessage(mailgunProperties.getDomain(), message);
            log.info("Sender approval notification email sent successfully - Message ID: {}, To: {}, Sender: {}", 
                    response.getMessage(), ownerEmail, senderEmail);
                    
        } catch (FeignException.Unauthorized e) {
            log.error("Mailgun authentication failed - Check API key configuration. Domain: {}, From: {}",
                    mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), e);
            // Don't throw - we don't want to fail the approval creation if email fails
        } catch (Exception e) {
            log.error("Failed to send sender approval notification email - Sender: {}, Company: {}",
                    senderEmail, companyId, e);
            // Don't throw - we don't want to fail the approval creation if email fails
        }
    }
}

