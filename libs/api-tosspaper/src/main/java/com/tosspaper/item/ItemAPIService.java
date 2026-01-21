package com.tosspaper.item;

import com.tosspaper.generated.model.Item;
import com.tosspaper.generated.model.ItemCreate;
import com.tosspaper.generated.model.ItemList;
import com.tosspaper.generated.model.ItemUpdate;

/**
 * API Service for Item operations (controller layer).
 */
public interface ItemAPIService {

    /**
     * Get items for a company.
     *
     * @param companyId company ID
     * @param active filter by active status (null means all)
     * @return list of items
     */
    ItemList getItems(Long companyId, Boolean active);

    /**
     * Get an item by ID.
     *
     * @param companyId company ID
     * @param itemId item ID
     * @return the item
     */
    Item getItemById(Long companyId, String itemId);

    /**
     * Create a new item.
     * If an active integration connection exists, the item will be pushed to the provider.
     *
     * @param companyId company ID
     * @param itemCreate item creation request
     * @return the created item
     */
    Item createItem(Long companyId, ItemCreate itemCreate);

    /**
     * Update an existing item.
     * If an active integration connection exists, the item will be pushed to the provider.
     *
     * @param companyId company ID
     * @param itemId item ID
     * @param itemUpdate item update request
     */
    void updateItem(Long companyId, String itemId, ItemUpdate itemUpdate);
}
