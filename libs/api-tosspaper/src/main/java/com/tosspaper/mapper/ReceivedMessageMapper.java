package com.tosspaper.mapper;

import com.tosspaper.generated.model.ReceivedMessage;
import com.tosspaper.generated.model.ReceivedMessageList;
import com.tosspaper.models.domain.EmailMessage;
import com.tosspaper.models.paging.Paginated;

import com.tosspaper.models.query.ReceivedMessageQuery;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReceivedMessageMapper {

    public ReceivedMessageList toApiReceivedMessageList(Paginated<EmailMessage> paginated) {
        if (paginated == null) {
            return null;
        }

        List<ReceivedMessage> messages = paginated.data().stream()
                .map(this::toApiReceivedMessage)
                .toList();

        var pagination = new com.tosspaper.generated.model.Pagination()
                .page(paginated.pagination().page())
                .pageSize(paginated.pagination().pageSize())
                .totalPages(paginated.pagination().totalPages())
                .totalItems(paginated.pagination().totalItems());

        var result = new ReceivedMessageList();
        result.setData(messages);
        result.setPagination(pagination);
        return result;
    }

    public ReceivedMessage toApiReceivedMessage(EmailMessage record) {
        if (record == null) {
            return null;
        }

        var message = new ReceivedMessage();
        message.setId(record.getId());
        message.setFrom(record.getFromAddress());
        message.setTo(record.getToAddress());
        message.setSubject(record.getSubject());
        message.setBodyHtml(record.getBodyHtml());
        message.setSource(ReceivedMessage.SourceEnum.EMAIL);
        message.setDateReceived(record.getProviderTimestamp());
        message.setAttachmentsCount(record.getAttachmentsCount());
        return message;
    }
}
