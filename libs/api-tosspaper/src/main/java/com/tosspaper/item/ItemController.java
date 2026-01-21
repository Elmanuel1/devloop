package com.tosspaper.item;

import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.api.ItemsApi;
import com.tosspaper.generated.model.Item;
import com.tosspaper.generated.model.ItemCreate;
import com.tosspaper.generated.model.ItemList;
import com.tosspaper.generated.model.ItemUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@Validated
public class ItemController implements ItemsApi {

    private final ItemAPIService itemService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'items:view')")
    public ResponseEntity<ItemList> getItems(String xContextId, Boolean active) {
        log.info("GET /v1/items - Fetching items: companyId={}, active={}", xContextId, active);
        ItemList itemList = itemService.getItems(HeaderUtils.parseCompanyId(xContextId), active);
        return ResponseEntity.ok(itemList);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'items:view')")
    public ResponseEntity<Item> getItemById(String xContextId, String id) {
        log.info("GET /v1/items/{} - Fetching item: companyId={}", id, xContextId);
        Item item = itemService.getItemById(HeaderUtils.parseCompanyId(xContextId), id);
        return ResponseEntity.ok(item);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'items:create')")
    public ResponseEntity<Item> createItem(String xContextId, ItemCreate itemCreate) {
        log.info("POST /v1/items - Creating item: companyId={}, name={}", xContextId, itemCreate.getName());
        Item item = itemService.createItem(HeaderUtils.parseCompanyId(xContextId), itemCreate);
        return ResponseEntity.status(HttpStatus.CREATED).body(item);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'items:update')")
    public ResponseEntity<Void> updateItem(String xContextId, String id, ItemUpdate itemUpdate) {
        log.info("PUT /v1/items/{} - Updating item: companyId={}", id, xContextId);
        itemService.updateItem(HeaderUtils.parseCompanyId(xContextId), id, itemUpdate);
        return ResponseEntity.noContent().build();
    }
}
