package com.tosspaper.integrations.provider;

import com.tosspaper.models.common.DocumentSyncRequest;
import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;

import java.util.List;
import java.util.Map;

public interface IntegrationPushProvider<T> {

    boolean isEnabled();

    IntegrationProvider getProviderId();

    IntegrationEntityType getEntityType();

    DocumentType getDocumentType();

    Map<String, SyncResult> pushBatch(IntegrationConnection connection, List<DocumentSyncRequest<?>> batch);

    SyncResult push(IntegrationConnection connection, DocumentSyncRequest<T> request);
}
