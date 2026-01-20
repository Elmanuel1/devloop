package com.tosspaper.models.query;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
public class ReceivedMessageQuery extends BaseQuery {
    String assignedEmail;
    String fromEmail;
}
