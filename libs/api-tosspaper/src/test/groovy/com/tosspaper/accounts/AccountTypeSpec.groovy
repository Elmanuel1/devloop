package com.tosspaper.accounts

import spock.lang.Specification
import spock.lang.Unroll

class AccountTypeSpec extends Specification {

    // ==================== fromString ====================

    def "fromString returns EXPENSE for 'expense' (case-insensitive)"() {
        expect: "EXPENSE is returned for lowercase"
            AccountType.fromString("expense") == AccountType.EXPENSE
    }

    def "fromString returns EXPENSE for 'EXPENSE' (uppercase)"() {
        expect: "EXPENSE is returned for uppercase"
            AccountType.fromString("EXPENSE") == AccountType.EXPENSE
    }

    def "fromString returns EXPENSE for 'Expense' (mixed case)"() {
        expect: "EXPENSE is returned for mixed case"
            AccountType.fromString("Expense") == AccountType.EXPENSE
    }

    def "fromString returns null for invalid type"() {
        expect: "null is returned for invalid type"
            AccountType.fromString("invalid") == null
    }

    def "fromString returns null for 'all'"() {
        expect: "null is returned for 'all' (indicating no filtering)"
            AccountType.fromString("all") == null
    }

    def "fromString returns null for empty string"() {
        when: "calling fromString with empty string"
            def result = AccountType.fromString("")

        then: "null is returned"
            result == null
    }

    def "fromString returns null for null input"() {
        when: "calling fromString with null"
            def result = AccountType.fromString(null)

        then: "null is returned"
            result == null
    }

    def "fromString returns null for blank string with spaces"() {
        when: "calling fromString with blank string"
            def result = AccountType.fromString("   ")

        then: "null is returned"
            result == null
    }

    def "fromString returns null for tab characters"() {
        when: "calling fromString with tab character"
            def result = AccountType.fromString("\t")

        then: "null is returned"
            result == null
    }

    @Unroll
    def "fromString handles various invalid inputs: '#input'"() {
        expect: "null is returned for invalid input"
            AccountType.fromString(input) == null

        where:
            input << ["revenue", "asset", "liability", "ALL", "none", "123", "expense_account"]
    }

    // ==================== getAccountTypes ====================

    def "EXPENSE account type contains expected account types"() {
        expect: "EXPENSE contains the expected account type strings"
            AccountType.EXPENSE.accountTypes.contains("Expense")
            AccountType.EXPENSE.accountTypes.contains("Cost of Goods Sold")
            AccountType.EXPENSE.accountTypes.contains("CostOfGoodsSold")
            AccountType.EXPENSE.accountTypes.contains("CostofGoodsSold")
            AccountType.EXPENSE.accountTypes.contains("Other Expense")
            AccountType.EXPENSE.accountTypes.contains("OtherExpense")
            AccountType.EXPENSE.accountTypes.contains("OtherCurrentAsset")
    }

    def "EXPENSE account type returns immutable list"() {
        expect: "accountTypes list is not empty"
            !AccountType.EXPENSE.accountTypes.isEmpty()
            AccountType.EXPENSE.accountTypes.size() == 7
    }
}
