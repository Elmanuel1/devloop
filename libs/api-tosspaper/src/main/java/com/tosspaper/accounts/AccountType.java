package com.tosspaper.accounts;

import lombok.Getter;

import java.util.List;

/**
 * Enum for filtering integration accounts by type.
 * Used to filter accounts suitable for different use cases (e.g., PO line items).
 */
@Getter
public enum AccountType {
    /**
     * Only expense-related accounts (Expense, Cost of Goods Sold, Other Expense).
     * Suitable for purchase order line items and expense tracking.
     */
    EXPENSE(List.of(
        "Expense",
        "Cost of Goods Sold",
        "CostOfGoodsSold",      // Alternative format
        "CostofGoodsSold",      // Case variation
        "Other Expense",
        "OtherExpense",         // Alternative format
        "OtherCurrentAsset"     // For prepaid expenses, inventory
    ));

    /**
     * -- GETTER --
     *  Get the list of QuickBooks account type values for this filter.
     */

    private final List<String> accountTypes;

    AccountType(List<String> accountTypes) {
        this.accountTypes = accountTypes;
    }

    /**
     * Convert string value to AccountType enum.
     * Returns null for "all" or invalid values (indicating no filtering at API level).
     *
     * @param value the string value (case-insensitive)
     * @return corresponding AccountType enum, or null if "all" or invalid
     */
    public static AccountType fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
