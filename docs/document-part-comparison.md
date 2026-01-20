# Document Part Comparison System

## Overview

The Document Part Comparison System performs detailed, AI-driven semantic matching between extracted documents (invoices, delivery notes) and stored Purchase Orders (POs). It compares individual document components—vendor contacts, ship-to contacts, and line items—and identifies discrepancies in quantities, prices, and other details.

## Purpose

When a document is extracted and matched to a PO, we need to:
- Verify that each extracted part has a corresponding part in the PO
- Identify discrepancies in quantities, prices, descriptions, etc.
- Track which line items matched which PO line items
- Flag unmatched parts for manual review

This system automates the validation process while providing transparency through detailed comparison results.

## Architecture

### Components

1. **DocumentPartComparisonService** - Core comparison interface
2. **FileSystemDocumentPartComparisonService** - Filesystem-based implementation (primary)
3. **DocumentPartComparisonServiceImpl** - Vector-store based implementation (fallback)
4. **AbstractDocumentPartComparisonService** - Abstract base class with shared logic
5. **DocumentPartComparisonRepository** - JOOQ-based persistence layer
6. **PostgreSQL Schema** - Stores comparison results with JSONB discrepancy details

### Database Schema

The system stores results in a `document_part_comparisons` table with:
- **Extracted part info**: Type (vendor/ship-to/line item), index, name, description
- **Match info**: Matched PO ID, matched item index, confidence score
- **Discrepancy details**: AI-generated JSON with all differences (quantity, price, etc.)
- **Match status**: `match_score == NULL` means no match found

**Key Design Decision:** Using NULL match_score to indicate "no match" eliminates the need for a separate boolean flag and makes queries simpler.

## How It Works

### Two-Path Integration

The comparison system integrates into PO matching at two critical points:

#### Path 1: Direct Match Validation
When a PO is found by exact number match (e.g., "PO-2309"), the system immediately runs detailed comparison to validate and find discrepancies.

**Flow:**
1. Extract document with PO number "2309"
2. Direct database lookup finds PO-2309
3. Run detailed part-by-part comparison
4. Save results showing matched items and discrepancies

**Purpose:** Validate that the document contents actually match the PO, even though the PO number matched exactly.

#### Path 2: Vector Search Validation
When no direct match exists (e.g., document says "23-09" but database has "2309"), use vector search to find similar POs. After user approves the AI suggestion, run detailed comparison.

**Flow:**
1. Extract document with PO number "23-09"
2. No direct match in database
3. Vector search finds candidates including "2309"
4. AI suggests "2309" as best match
5. User reviews and approves suggestion
6. Run detailed part-by-part comparison
7. Save results showing matched items and discrepancies

**Purpose:** Confirm the suggested PO is actually correct by validating document contents, not just the PO number format.

### Comparison Flow

**Step 1: Parse Extraction**
- Extract vendor contact (name, address, phone)
- Extract ship-to contact (name, address, phone)
- Extract all line items (description, quantity, unit price, item code)

**Step 2: Filesystem-based Comparison (FileSystemDocumentPartComparisonService)**
- Sync PO data to VFS as JSON file
- Save extracted document to VFS as JSON file
- AI agent uses shell commands to read both files
- AI agent matches line items and writes comparison results
- System reads results from VFS and persists to database

**Alternative: Vector-store Comparison (DocumentPartComparisonServiceImpl)**
- Format each part as key-value text
- Split into batches of 100 items
- Process all batches concurrently using virtual threads
- For each item:
  - Search vector store for top 3 similar PO parts
  - AI selects best match from the 3 candidates
  - AI calculates all discrepancies (quantity, price, description differences)
- Collect all results

**Step 3: Conflict Resolution (Vector-store implementation only)**
- Identify duplicates: multiple extracted items matched to the same PO item
- For each conflict:
  - Keep the highest confidence match
  - Retry the losers with the already-matched items excluded
  - Increase search scope if no alternatives found (3→6→9 candidates)
  - Mark as "no match" if still no alternatives after 3 attempts

**Note:** Filesystem-based implementation relies on AI agent's matching logic and does not perform separate conflict resolution.

**Step 4: Save Results**
- Store all results (both matched and unmatched) in database
- Results are available for UI display and reporting

### Why Filesystem-based Approach?

**Problem:** Vector store requires embedding generation and similarity search, which can be slow and resource-intensive.

**Solution:** Use a filesystem-based approach where the AI agent directly reads structured JSON files.

**Benefits:**
- No embedding generation overhead
- AI can see full context of both documents
- Simpler architecture - no vector store dependency
- Better for exact matching scenarios
- Thread-safe stateless design

**Note:** Vector-store implementation still uses parallel processing with virtual threads for large documents.

### Deduplication Logic (Vector-store implementation)

**Problem:** Multiple extracted line items might initially match the same PO line item. This can happen when:
- PO has duplicate line items
- Vector search returns the same top candidate for similar extracted items
- Initial parallel processing doesn't know about other matches

**Solution: Two-Phase Approach**

**Phase 1: Optimistic**
- Process all items in parallel (fast)
- Don't worry about conflicts yet
- Each item independently finds its best match

**Phase 2: Cleanup**
- Detect conflicts (same PO item matched multiple times)
- For each conflict:
  - Sort by confidence (highest to lowest)
  - Winner keeps the match
  - Losers retry with exclusion list
- Retry up to 3 times with increasing search scope
- Mark as "no match" if no alternatives exist

**Special Case:** Vendor and ship-to contacts bypass conflict resolution entirely since there can only be one vendor and one ship-to per document.

**Note:** Filesystem-based implementation relies on the AI agent's matching logic to handle duplicates correctly.

## AI-Driven Matching

### Why AI Instead of Simple String Matching?

**Semantic Understanding:**
- "Acme Corp" matches "ACME CORPORATION"
- "Steel Pipe 4in" matches "Steel Pipe 4 inch"
- Handles typos, abbreviations, formatting differences

**Discrepancy Detection:**
- Automatically identifies what changed (not just "different")
- Quantifies differences (50 vs 100 = -50 units)
- Provides human-readable explanations

### How AI Selection Works

**Input:** Top 3 similar items from vector search
**Output:** Best match + confidence + discrepancies

**The AI evaluates:**
- Semantic similarity of names/descriptions
- Differences in quantities, prices, measurements
- Context from surrounding information
- Overall likelihood this is the correct match

**Response format:**
- Best match index (1, 2, or 3)
- Confidence score (0.0 to 1.0)
- Reasons for selection
- Detailed discrepancy breakdown (JSON)

## Performance Characteristics

### Throughput

**Small Documents:**
- 2 contacts + 10 line items
- Processing time: 1-2 seconds
- Mostly single batch, minimal conflicts

**Large Documents:**
- 2 contacts + 2000 line items
- Processing time: 30-60 seconds
- 20 parallel batches
- More conflicts to resolve

### Scalability

**Horizontal Scaling:**
- Multiple instances can process different extractions simultaneously
- Each extraction is independent
- No shared state between extractions

**Vertical Scaling:**
- Virtual threads enable thousands of concurrent comparisons per instance
- No thread pool exhaustion
- Memory scales linearly with document size

**Database:**
- Indexed queries ensure fast retrieval
- JSONB storage efficient and flexible
- Can handle millions of comparison results

## Monitoring & Observability

### Key Metrics to Track

1. **Match Rate** - Percentage of parts successfully matched (target: >90%)
2. **Conflict Rate** - Percentage of line items with conflicts (normal: 5-15%)
3. **Retry Success Rate** - How often conflicts resolve on retry (target: >70%)
4. **Processing Time** - Average time per extraction by document size
5. **AI Accuracy** - How often AI selections are confirmed correct by users

### Log Levels

**INFO:** Summary information
- Comparison started/completed
- Total matched/unmatched counts
- Conflict resolution summary

**DEBUG:** Detailed execution trace
- Batch processing progress
- Individual match results
- Conflict detection and resolution steps
- Retry attempts and outcomes

**TRACE:** Deep debugging
- Full AI prompts and responses
- Vector search results
- Filter expressions used

## Error Handling & Recovery

### Failure Scenarios

**Vector Store Unavailable:**
- Impact: Entire comparison fails
- Detection: Exception during similarity search
- Recovery: Retry with exponential backoff
- User Impact: Extraction stays in "pending" state

**AI Service Unavailable:**
- Impact: Individual parts marked as "no match"
- Detection: Exception during AI call
- Recovery: Continue processing other parts
- User Impact: Fewer automated matches, more manual review

**Database Write Failure:**
- Impact: Results not persisted
- Detection: Exception during save
- Recovery: Transaction rollback, retry entire comparison
- User Impact: Comparison appears incomplete

**Invalid Extraction Data:**
- Impact: Part skipped or marked as "no match"
- Detection: Parsing error or null values
- Recovery: Log warning, continue with other parts
- User Impact: Some parts show as unmatched

### Recovery Strategies

**Automatic Retry:**
- Vector search failures: 3 retries with exponential backoff
- AI call failures: Mark as "no match" and continue
- Database write failures: Retry entire comparison once

**Manual Retry:**
- Delete old results for an extraction
- Rerun comparison with fresh data
- Useful after fixing data quality issues

## Configuration & Tuning

### Key Parameters

**Batch Size:** Number of items processed in parallel per batch
- Default: 100
- Smaller: Faster initial results, more overhead
- Larger: Better throughput, higher memory usage

**Initial TopK:** How many similar items to retrieve from vector search
- Default: 3
- Smaller: Faster, might miss correct matches
- Larger: Slower, more accurate matching

**Max Retry Attempts:** How many times to retry conflicts
- Default: 3
- More attempts: Higher match rate, longer processing time
- Fewer attempts: Faster, more "no match" results

**TopK Increment:** How much to increase search scope per retry
- Default: 3 (so 3→6→9)
- Larger: Fewer retries, might skip good matches
- Smaller: More retries, better coverage

### Environment-Specific Settings

**Development:**
- Smaller batch size for faster feedback
- More logging (DEBUG level)
- Lower retry limits for faster iteration

**Production:**
- Optimal batch size (100)
- INFO level logging
- Full retry attempts for maximum accuracy

## Use Cases & Examples

### Use Case 1: Standard Invoice Validation

**Scenario:** Customer receives invoice for PO-2309 with 10 line items.

**Process:**
1. System extracts invoice data
2. Finds PO-2309 directly in database
3. Compares vendor (matched, no discrepancies)
4. Compares ship-to (matched, no discrepancies)
5. Compares 10 line items (8 matched perfectly, 2 have quantity discrepancies)
6. User reviews discrepancies and approves invoice

**Outcome:** 8 items auto-approved, 2 flagged for review (received 50 units, ordered 100).

### Use Case 2: Fuzzy PO Number Match

**Scenario:** Delivery note says "PO# 23-09" but database has "2309".

**Process:**
1. System extracts delivery note
2. No direct match for "23-09"
3. Vector search finds "2309" as top candidate
4. AI confirms high similarity (vendor, ship-to, line items all match)
5. System suggests PO-2309 to user
6. User approves suggestion
7. Detailed comparison runs and saves results

**Outcome:** Correct PO found despite format difference.

### Use Case 3: Duplicate Line Items

**Scenario:** Invoice has 5 "Steel Pipes" line items, PO also has 5 "Steel Pipe" line items.

**Process:**
1. Phase 1: All 5 extracted items initially match to PO item #1 (highest similarity)
2. Phase 2: Conflict detected (5 items → 1 PO item)
3. Highest confidence match kept for PO item #1
4. Other 4 retry with exclusion
5. Each finds different PO items #2, #3, #4, #5

**Outcome:** All 5 line items correctly matched one-to-one.

### Use Case 4: Missing Line Items

**Scenario:** PO has 10 line items, invoice only has 8.

**Process:**
1. System compares 8 extracted items
2. All 8 find matches in PO
3. 2 PO items remain unmatched (no extracted item for them)
4. System flags: "2 PO items not found in invoice"

**Outcome:** User notified of partial delivery.

## Best Practices

### Data Quality

**Input Requirements:**
- Extraction quality directly impacts match accuracy
- Clean OCR results critical for good matches
- Structured data (POs) must be complete and accurate

**Recommendations:**
- Validate extraction quality before comparison
- Ensure PO data is up-to-date in vector store
- Handle edge cases (null values, empty strings) gracefully

### Performance Optimization

**For Large Documents:**
- Process in production environment (more resources)
- Monitor memory usage (scales with document size)
- Consider breaking very large documents into chunks

**For High Volume:**
- Scale horizontally (multiple instances)
- Monitor AI API rate limits
- Optimize vector store queries (proper indexing)

### Monitoring

**What to Monitor:**
- Average processing time by document size
- Match rate trends over time
- Conflict rate (increasing = data quality issue)
- Error rate and types
- User corrections (improve AI prompts)

**Alerts:**
- Processing time >2x baseline
- Match rate <70%
- Error rate >5%
- AI service unavailable

## Troubleshooting Guide

### Low Match Rates

**Symptoms:** <70% of parts finding matches

**Possible Causes:**
- Poor extraction quality
- PO data not in vector store
- Incorrect filter parameters (wrong email/PO ID)
- AI model changed or degraded

**Solutions:**
- Check extraction quality scores
- Verify PO ingestion completed
- Validate filter expressions in logs
- Review sample AI responses

### High Processing Time

**Symptoms:** >2 minutes for 2000 items

**Possible Causes:**
- Vector store slow response
- AI API rate limiting
- Too many conflicts/retries
- Insufficient resources

**Solutions:**
- Check vector store performance
- Monitor AI API quotas
- Analyze conflict patterns
- Scale vertically or horizontally

### Frequent Conflicts

**Symptoms:** >30% of line items have conflicts

**Possible Causes:**
- PO has many duplicate items
- Vector search returning same items
- Poor extracted item differentiation

**Solutions:**
- Improve PO line item descriptions
- Increase initial topK
- Add more metadata to searches
- Review extraction formatting

### "No Match" for Valid Items

**Symptoms:** Items that should match show "no match"

**Possible Causes:**
- Overly strict filtering
- PO item not in vector store
- Description too different
- Already matched by another item

**Solutions:**
- Verify PO ingestion
- Check filter expressions
- Review AI selection reasoning
- Analyze conflict resolution logs

## Future Enhancements

### Planned Improvements

**Confidence Thresholds:**
- Configurable minimum confidence for auto-approval
- Different thresholds by part type
- Learning from user corrections

**Batch Processing:**
- Compare multiple extractions at once
- Optimize for recurring suppliers
- Pre-compute common matches

**Smart Retry:**
- Learn which items typically need retries
- Adjust topK dynamically based on patterns
- Skip unlikely matches

**Discrepancy Rules:**
- Configurable tolerance (±5% quantity OK)
- Auto-approve within tolerance
- Escalate only significant differences

**Analytics:**
- Supplier match accuracy reports
- Most common discrepancy types
- Processing time trends
- Cost per comparison

## Summary

The Document Part Comparison System provides automated, AI-driven validation of extracted documents against Purchase Orders. By comparing individual parts (contacts and line items) and handling complex scenarios like duplicates and fuzzy matches, it reduces manual review time while maintaining accuracy.

**Key Benefits:**
- 90%+ automatic match rate
- Detailed discrepancy detection
- Handles complex deduplication
- Scales to large documents
- Full audit trail of results

**Success Metrics:**
- Match accuracy: >95%
- Processing speed: <1 min per 1000 items
- User intervention: <10% of parts
- System uptime: >99.9%

---

**Document Version:** 1.0  
**Last Updated:** 2025-01-12  
**Author:** Engineering Team
