-- Invoice Bill Push Diagnostic Query
-- Run this to understand why invoices are not being pulled for bill creation

-- 1. Check invoice statuses distribution
SELECT
    status,
    COUNT(*) as count,
    COUNT(*) FILTER (WHERE last_sync_at IS NULL) as never_synced,
    COUNT(*) FILTER (WHERE updated_at > last_sync_at) as has_local_changes,
    COUNT(*) FILTER (WHERE push_permanently_failed = true) as permanently_failed,
    COUNT(*) FILTER (WHERE push_retry_count >= 5) as max_retries_exceeded
FROM invoices
WHERE company_id = :company_id  -- Replace with your company ID
GROUP BY status;

-- 2. Check ACCEPTED invoices that SHOULD be pulled
SELECT
    id,
    document_number,
    po_number,
    status,
    created_at,
    updated_at,
    last_sync_at,
    push_retry_count,
    push_permanently_failed,
    push_failure_reason,
    external_id,
    CASE
        WHEN status != 'ACCEPTED' THEN 'Status not ACCEPTED'
        WHEN updated_at <= last_sync_at AND last_sync_at IS NOT NULL THEN 'Already synced (no local changes)'
        WHEN push_permanently_failed = true THEN 'Permanently failed'
        WHEN push_retry_count >= 5 THEN 'Exceeded max retries (5)'
        ELSE 'SHOULD BE PULLED ✓'
    END as pull_status
FROM invoices
WHERE company_id = :company_id  -- Replace with your company ID
ORDER BY created_at DESC
LIMIT 50;

-- 3. Check invoices that WOULD be pulled by the workflow
SELECT
    id,
    document_number,
    po_number,
    status,
    created_at,
    updated_at,
    last_sync_at,
    push_retry_count,
    push_permanently_failed,
    push_failure_reason
FROM invoices
WHERE company_id = :company_id  -- Replace with your company ID
  AND status = 'ACCEPTED'
  AND (updated_at > last_sync_at OR last_sync_at IS NULL)
  AND push_permanently_failed = false
  AND push_retry_count < 5
ORDER BY created_at ASC
LIMIT 50;

-- 4. Check permanently failed or max retry invoices
SELECT
    id,
    document_number,
    po_number,
    push_retry_count,
    push_permanently_failed,
    push_failure_reason,
    push_retry_last_attempt_at
FROM invoices
WHERE company_id = :company_id  -- Replace with your company ID
  AND (push_permanently_failed = true OR push_retry_count >= 5)
ORDER BY push_retry_last_attempt_at DESC;

-- 5. Check if invoices are missing required PO numbers (will fail dependency check)
SELECT
    id,
    document_number,
    po_number,
    status,
    'Missing PO number - will fail dependency check' as issue
FROM invoices
WHERE company_id = :company_id  -- Replace with your company ID
  AND status = 'ACCEPTED'
  AND (po_number IS NULL OR po_number = '')
ORDER BY created_at DESC;
