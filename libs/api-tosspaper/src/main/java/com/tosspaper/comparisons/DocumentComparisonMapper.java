package com.tosspaper.comparisons;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.generated.model.ComparisonPartResult;
import com.tosspaper.generated.model.DocumentComparisonResult;
import com.tosspaper.generated.model.FieldComparison;
import com.tosspaper.models.extraction.dto.Comparison;
import com.tosspaper.models.extraction.dto.ComparisonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps domain Comparison DTOs to API DocumentComparisonResult DTOs.
 *
 * <p>The new schema uses a single "comparisons" array per result instead of
 * separate "reasons", "signals", and "discrepancies" objects. This makes the
 * comparison data more human-readable and easier to display in the UI.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentComparisonMapper {

    private final ObjectMapper objectMapper;

    public DocumentComparisonResult toDto(Comparison comparison) {
        if (comparison == null) {
            return null;
        }

        DocumentComparisonResult dto = new DocumentComparisonResult();
        dto.setDocumentId(comparison.getDocumentId());
        dto.setPoId(comparison.getPoId());
        dto.setConfidence(comparison.getConfidence());

        // Map overallStatus enum
        if (comparison.getOverallStatus() != null) {
            dto.setOverallStatus(mapOverallStatus(comparison.getOverallStatus()));
        }

        // Map results array
        List<ComparisonPartResult> results = null;
        if (comparison.getResults() != null) {
            results = comparison.getResults().stream()
                    .map(this::toPartResult)
                    .collect(Collectors.toList());
            dto.setResults(results);
        }

        // Recalculate blockingIssues from results (count results with severity=blocking)
        int blockingCount = results != null
                ? (int) results.stream()
                    .filter(r -> r.getSeverity() == ComparisonPartResult.SeverityEnum.BLOCKING)
                    .count()
                : 0;
        dto.setBlockingIssues(blockingCount);

        return dto;
    }

    private ComparisonPartResult toPartResult(ComparisonResult result) {
        if (result == null) {
            return null;
        }

        ComparisonPartResult dto = new ComparisonPartResult();

        // Map type enum
        if (result.getType() != null) {
            dto.setType(mapType(result.getType()));
        }

        // Map status enum
        if (result.getStatus() != null) {
            dto.setStatus(mapStatus(result.getStatus()));
        }

        dto.setMatchScore(result.getMatchScore());

        // Map severity enum
        if (result.getSeverity() != null) {
            dto.setSeverity(mapSeverity(result.getSeverity()));
        }

        dto.setExtractedIndex(result.getExtractedIndex() != null
                ? result.getExtractedIndex().intValue() : null);
        dto.setPoIndex(result.getPoIndex() != null
                ? result.getPoIndex().intValue() : null);

        // Map comparisons array
        if (result.getComparisons() != null) {
            dto.setComparisons(result.getComparisons().stream()
                    .map(this::toFieldComparison)
                    .collect(Collectors.toList()));
        }

        return dto;
    }

    private FieldComparison toFieldComparison(
            com.tosspaper.models.extraction.dto.FieldComparison source) {
        if (source == null) {
            return null;
        }

        FieldComparison dto = new FieldComparison();
        dto.setField(source.getField());
        dto.setPoValue(source.getPoValue());
        dto.setDocumentValue(source.getDocumentValue());
        dto.setIsBlocking(source.getIsBlocking() != null ? source.getIsBlocking() : false);
        dto.setExplanation(source.getExplanation());

        // Map match enum
        if (source.getMatch() != null) {
            dto.setMatch(mapMatch(source.getMatch()));
        }

        return dto;
    }

    private FieldComparison.MatchEnum mapMatch(
            com.tosspaper.models.extraction.dto.FieldComparison.Match match) {
        return switch (match) {
            case EXACT -> FieldComparison.MatchEnum.EXACT;
            case CLOSE -> FieldComparison.MatchEnum.CLOSE;
            case MISMATCH -> FieldComparison.MatchEnum.MISMATCH;
        };
    }

    private DocumentComparisonResult.OverallStatusEnum mapOverallStatus(Comparison.OverallStatus status) {
        return switch (status) {
            case MATCHED -> DocumentComparisonResult.OverallStatusEnum.MATCHED;
            case PARTIAL -> DocumentComparisonResult.OverallStatusEnum.PARTIAL;
            case UNMATCHED -> DocumentComparisonResult.OverallStatusEnum.UNMATCHED;
        };
    }

    private ComparisonPartResult.TypeEnum mapType(ComparisonResult.Type type) {
        return switch (type) {
            case VENDOR -> ComparisonPartResult.TypeEnum.VENDOR;
            case SHIP_TO -> ComparisonPartResult.TypeEnum.SHIP_TO;
            case LINE_ITEM -> ComparisonPartResult.TypeEnum.LINE_ITEM;
        };
    }

    private ComparisonPartResult.StatusEnum mapStatus(ComparisonResult.Status status) {
        return switch (status) {
            case MATCHED -> ComparisonPartResult.StatusEnum.MATCHED;
            case PARTIAL -> ComparisonPartResult.StatusEnum.PARTIAL;
            case UNMATCHED -> ComparisonPartResult.StatusEnum.UNMATCHED;
        };
    }

    private ComparisonPartResult.SeverityEnum mapSeverity(ComparisonResult.Severity severity) {
        return switch (severity) {
            case INFO -> ComparisonPartResult.SeverityEnum.INFO;
            case WARNING -> ComparisonPartResult.SeverityEnum.WARNING;
            case BLOCKING -> ComparisonPartResult.SeverityEnum.BLOCKING;
        };
    }
}
