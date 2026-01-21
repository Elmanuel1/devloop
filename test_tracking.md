# Test Files to Verify

This file tracks the progress of verifying the newly added/modified tests against their corresponding implementation files and edge cases.

| Test File | Implementation File | Status | Findings |
|-----------|----------------------|--------|----------|
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/accounts/IntegrationAccountAPIServiceSpec.groovy` | `IntegrationAccountAPIServiceImpl.java` | Partial | Good coverage for basic flows. Missing: `companyId=null`, `mapper` returning null. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/accounts/IntegrationAccountServiceSpec.groovy` | `IntegrationAccountServiceImpl.java` | Complete | Covers happy path delegation and null/empty ID handling. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/common/GlobalExceptionHandlerSpec.groovy` | `GlobalExceptionHandler.java` | Partial | Many exception handlers (NotFound, Forbidden, etc.) are NOT tested. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/common/validator/ValidAssignedEmailDomainValidatorSpec.groovy` | `ValidAssignedEmailDomainValidator.java` | Complete | Excellent coverage for all logic branches and email formats. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/company/CompanyControllerSpec.groovy` | `CompanyController.java` | Complete | Good integration coverage including metadata and security errors. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/company/CompanyRepositorySpec.groovy` | `CompanyRepositoryImpl.java` | Complete | Good coverage for persistence and exceptions. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/company/CompanyServiceSpec.groovy` | `CompanyServiceImpl.java` | Partial | Missing: `NotFoundException` cases for `getCompanyById` and `updateCompany`. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/contact/ContactControllerSpec.groovy` | `ContactController.java` | Partial | Discrepancy: Test for archived contact deletion says 403 but expects 204. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/contact/ContactRepositorySpec.groovy` | `ContactRepositoryImpl.java` | Complete | Excellent coverage for filters and foreign key constraints. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/contact/ContactServiceSpec.groovy` | `ContactServiceImpl.java` | Complete | Very thorough coverage of business logic, approvals, and events. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/delivery_notes/DeliveryNoteServiceSpec.groovy` | `DeliveryNoteServiceImpl.java` | Complete | Good coverage for cursor pagination and 404 scenarios. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/delivery_slips/DeliverySlipServiceSpec.groovy` | `DeliverySlipServiceImpl.java` | Complete | Good coverage for cursor pagination and 404 scenarios. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/document_approval/DocumentApprovalDetailServiceSpec.groovy` | `DocumentApprovalDetailService.java` | Complete | Covers permission checks and full field mapping. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/document_approval/DocumentApprovalEmailProcessingServiceSpec.groovy` | `DocumentApprovalEmailProcessingServiceImpl.java` | Partial | Missing: Scenarios where approval or extraction task is not found. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/document_approval/DocumentApprovalServiceSpec.groovy` | `DocumentApprovalServiceImpl.java` | Partial | Missing: `NotFoundException` for `reviewExtraction`. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/file/FilesServiceImplSpec.groovy` | `FilesServiceImpl.java` | Complete | Excellent edge case coverage for extensions, sizes, and S3 errors. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/invoices/InvoiceServiceSpec.groovy` | `InvoiceServiceImpl.java` | Complete | Good coverage for cursor pagination and security (wrong company 404). |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/item/ItemAPIServiceSpec.groovy` | `ItemAPIServiceImpl.java` | Complete | Covers category filtering, active status, and integration events. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/project/ProjectControllerSpec.groovy` | `ProjectController.java` | Partial | Basic CRUD; missing 404/403 and validation error tests. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/project/ProjectRepositorySpec.groovy` | `ProjectRepositoryImpl.java` | Complete | Thorough search, duplicate handling, and complex pagination tests. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/project/ProjectServiceSpec.groovy` | `ProjectServiceImpl.java` | Complete | Robust status transition and final state immutability checks. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/purchaseorder/PurchaseOrderControllerSpec.groovy` | `PurchaseOrderController.java` | Complete | Extensive coverage for filters, pagination, and metadata JSON. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/purchaseorder/PurchaseOrderRepositorySpec.groovy` | `PurchaseOrderRepositoryImpl.java` | Complete | Covers changelog logging, status updates, and auto-ID generation. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/purchaseorder/PurchaseOrderServiceSpec.groovy` | `PurchaseOrderServiceImpl.java` | Complete | Excellent coverage for currency validation, status transitions, and events. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/service/impl/PurchaseOrderSyncServiceSpec.groovy` | `PurchaseOrderSyncServiceImpl.java` | Complete | Thin wrapper but covers delegation for needing-push and retry logic. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/service/impl/CompanySyncServiceSpec.groovy` | `CompanySyncServiceImpl.java` | Complete | Includes graceful exception handling and null param checks. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/service/impl/ContactSyncServiceSpec.groovy` | `ContactSyncServiceImpl.java` | Complete | Covers batch updates and null/empty ID handling. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/service/impl/InvoiceSyncServiceSpec.groovy` | `InvoiceSyncServiceImpl.java` | Complete | Integrates with `PushRetryConfig` for max attempts logic. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/service/impl/ItemServiceSpec.groovy` | `ItemServiceImpl.java` | Complete | Covers external ID mapping and batch sync status updates. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/service/impl/PaymentTermServiceSpec.groovy` | `PaymentTermServiceImpl.java` | Complete | Covers basic delegation and empty list scenarios. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/rbac/AuthorizedUserServiceSpec.groovy` | `AuthorizedUserServiceImpl.java` | Complete | Covers last owner protection and role update cache eviction. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/rbac/CompanyInvitationServiceSpec.groovy` | `CompanyInvitationServiceImpl.java` | Complete | Handles blocked domains and existing Supabase user path. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/rbac/RoleServiceSpec.groovy` | `RoleServiceImpl.java` | Complete | Maps all role properties correctly and ensures consistent ordering. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/rbac/UserOnboardingServiceSpec.groovy` | `UserOnboardingServiceImpl.java` | Complete | Tests complex transactional onboarding and business logic for assigned email format. |
| `libs/api-tosspaper/src/test/groovy/com/tosspaper/rbac/UserRoleCacheServiceSpec.groovy` | `UserRoleCacheServiceImpl.java` | Complete | Correctly filters out disabled users and tests various role mappings. |
