package com.tosspaper.integrations.provider;

import com.intuit.ipp.core.IEntity;
import com.intuit.ipp.data.Term;
import lombok.Getter;

/**
 * Entity types that can be synced via integrations.
 */
@Getter
public enum IntegrationEntityType {
    PURCHASE_ORDER("PurchaseOrder", "Purchase Order", com.intuit.ipp.data.PurchaseOrder.class),
    BILL("Bill", "Bill", com.intuit.ipp.data.Bill.class),
    VENDOR("Vendor", "Vendor", com.intuit.ipp.data.Vendor.class),
    JOB_LOCATION("Customer", "Job Location", com.intuit.ipp.data.Customer.class),
    ACCOUNT("Account", "Account", com.intuit.ipp.data.Account.class),
    PAYMENT_TERM("Term", "Payment Term", Term.class),
    ITEM("Item", "Item", com.intuit.ipp.data.Item.class),
    PREFERENCES("Preferences", "Preferences", com.intuit.ipp.data.Preferences.class);

    private final String value;
    private final String displayName;
    private final Class<? extends IEntity> clazz;

    IntegrationEntityType(String value, String displayName, Class<? extends IEntity> clazz) {
        this.value = value;
        this.displayName = displayName;
        this.clazz = clazz;
    }

    public static IntegrationEntityType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (IntegrationEntityType type : IntegrationEntityType.values()) {
            if (type.value.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown integration entity type: " + value);
    }

    public static IntegrationEntityType fromEntity(IEntity entity) {
        for (IntegrationEntityType type : values()) {
            if (type.clazz.isAssignableFrom(entity.getClass())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported entity: " + entity.getClass());
    }
}
