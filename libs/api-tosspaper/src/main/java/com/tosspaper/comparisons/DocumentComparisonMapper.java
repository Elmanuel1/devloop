package com.tosspaper.comparisons;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.generated.model.ComparisonPartResult;
import com.tosspaper.generated.model.DocumentComparisonResult;
import com.tosspaper.models.extraction.dto.Comparison;
import com.tosspaper.models.extraction.dto.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps domain Comparison DTOs to API DocumentComparisonResult DTOs.
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
        dto.setBlockingIssues(comparison.getBlockingIssues() != null
                ? comparison.getBlockingIssues().intValue() : null);

        // Map overallStatus enum
        if (comparison.getOverallStatus() != null) {
            dto.setOverallStatus(mapOverallStatus(comparison.getOverallStatus()));
        }

        // Map results array
        if (comparison.getResults() != null) {
            dto.setResults(comparison.getResults().stream()
                    .map(this::toPartResult)
                    .collect(Collectors.toList()));
        }

        return dto;
    }

    private ComparisonPartResult toPartResult(Result result) {
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
        dto.setConfidence(result.getConfidence());
        dto.setReasons(result.getReasons());

        // Map severity enum
        if (result.getSeverity() != null) {
            dto.setSeverity(mapSeverity(result.getSeverity()));
        }

        dto.setExtractedIndex(result.getExtractedIndex() != null
                ? result.getExtractedIndex().intValue() : null);
        dto.setPoIndex(result.getPoIndex() != null
                ? result.getPoIndex().intValue() : null);

        // Map signals - convert typed object to Map using ObjectMapper
        if (result.getSignals() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> signalsMap = objectMapper.convertValue(result.getSignals(), Map.class);
                dto.setSignals(signalsMap);
            } catch (Exception e) {
                log.warn("Failed to convert signals to map: {}", e.getMessage());
            }
        }

        // Map discrepancies - convert typed object to Map using ObjectMapper
        if (result.getDiscrepancies() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> discrepanciesMap = objectMapper.convertValue(result.getDiscrepancies(), Map.class);
                dto.setDiscrepancies(discrepanciesMap);
            } catch (Exception e) {
                log.warn("Failed to convert discrepancies to map: {}", e.getMessage());
            }
        }

        return dto;
    }

    private DocumentComparisonResult.OverallStatusEnum mapOverallStatus(Comparison.OverallStatus status) {
        return switch (status) {
            case MATCHED -> DocumentComparisonResult.OverallStatusEnum.MATCHED;
            case PARTIAL -> DocumentComparisonResult.OverallStatusEnum.PARTIAL;
            case UNMATCHED -> DocumentComparisonResult.OverallStatusEnum.UNMATCHED;
        };
    }

    private ComparisonPartResult.TypeEnum mapType(Result.Type type) {
        return switch (type) {
            case VENDOR -> ComparisonPartResult.TypeEnum.VENDOR;
            case SHIP_TO -> ComparisonPartResult.TypeEnum.SHIP_TO;
            case LINE_ITEM -> ComparisonPartResult.TypeEnum.LINE_ITEM;
        };
    }

    private ComparisonPartResult.StatusEnum mapStatus(Result.Status status) {
        return switch (status) {
            case MATCHED -> ComparisonPartResult.StatusEnum.MATCHED;
            case PARTIAL -> ComparisonPartResult.StatusEnum.PARTIAL;
            case UNMATCHED -> ComparisonPartResult.StatusEnum.UNMATCHED;
        };
    }

    private ComparisonPartResult.SeverityEnum mapSeverity(Result.Severity severity) {
        return switch (severity) {
            case INFO -> ComparisonPartResult.SeverityEnum.INFO;
            case WARNING -> ComparisonPartResult.SeverityEnum.WARNING;
            case BLOCKING -> ComparisonPartResult.SeverityEnum.BLOCKING;
        };
    }
}
