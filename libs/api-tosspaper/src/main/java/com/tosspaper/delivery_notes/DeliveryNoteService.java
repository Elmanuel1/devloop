package com.tosspaper.delivery_notes;

import com.tosspaper.generated.model.DeliveryNote;
import com.tosspaper.generated.model.DeliveryNoteList;

public interface DeliveryNoteService {
    DeliveryNoteList getDeliveryNotes(Long companyId, String projectId, String purchaseOrderId, String poNumber, String search, Integer limit, String cursor);
    DeliveryNote getDeliveryNoteById(Long companyId, String id);
}

