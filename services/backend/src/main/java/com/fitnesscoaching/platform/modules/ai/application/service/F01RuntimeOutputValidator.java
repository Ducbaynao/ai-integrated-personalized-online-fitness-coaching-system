package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic schema and domain validator for F01 runtime outputs.
 * Verifies that AI service responses strictly conform to the F01 contract invariants,
 * RFC 3339 constraints, citation grounding constraints, and allowed source boundaries.
 */
@Component
public class F01RuntimeOutputValidator {

    private static final Pattern DOCUMENT_ID_PATTERN = Pattern.compile("^[A-Z0-9-]+-VI-[0-9]{2}$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");
    private static final Pattern SOURCE_ID_PATTERN = Pattern.compile("^[A-Z0-9-]+$");

    public record ValidationResult(boolean isValid, String failureReason) {
        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    public ValidationResult validate(F01KnowledgeQueryResponse resp, F01InternalContextEnvelope envelope) {
        if (resp == null) {
            return ValidationResult.invalid("Response is null.");
        }

        // 1. Validate asOf date-time format (Strict RFC 3339: calendar and timezone bounds, second <= 59)
        if (resp.asOf() == null || resp.asOf().isBlank()) {
            return ValidationResult.invalid("Missing asOf timestamp.");
        }
        if (!isValidRfc3339DateTime(resp.asOf())) {
            return ValidationResult.invalid("asOf is not a valid RFC 3339 date-time or has invalid second value.");
        }

        String runStatus = resp.runStatus();
        String resultType = resp.resultType();
        String reasonCode = resp.reasonCode();

        // 2. Validate Allowed Source Scope for Citations
        Set<String> allowedSources = new HashSet<>();
        if (envelope.knowledgeScope() != null && envelope.knowledgeScope().allowedSourceIds() != null) {
            allowedSources.addAll(envelope.knowledgeScope().allowedSourceIds());
        }

        if ("SUCCEEDED".equals(runStatus)) {
            if ("INFORMATION".equals(resultType)) {
                if (!"NONE".equals(reasonCode)) {
                    return ValidationResult.invalid("INFORMATION result must have reasonCode NONE.");
                }
                if (resp.answer() == null || resp.answer().isBlank()) {
                    return ValidationResult.invalid("INFORMATION result requires non-empty answer.");
                }
                if (resp.citations() == null || resp.citations().isEmpty()) {
                    return ValidationResult.invalid("INFORMATION result requires at least one citation.");
                }
                if (resp.error() != null) {
                    return ValidationResult.invalid("INFORMATION result cannot contain error diagnostics.");
                }

                // Citations structural and boundary validation
                for (F01KnowledgeQueryResponse.CitationDto c : resp.citations()) {
                    if (c.documentId() == null || !DOCUMENT_ID_PATTERN.matcher(c.documentId()).matches()) {
                        return ValidationResult.invalid("Citation documentId does not match canonical pattern: " + c.documentId());
                    }
                    if (c.version() == null || !VERSION_PATTERN.matcher(c.version()).matches()) {
                        return ValidationResult.invalid("Citation version is not valid semver: " + c.version());
                    }
                    if (c.sourceId() == null || !SOURCE_ID_PATTERN.matcher(c.sourceId()).matches()) {
                        return ValidationResult.invalid("Citation sourceId is invalid: " + c.sourceId());
                    }
                    if (!allowedSources.contains(c.sourceId())) {
                        return ValidationResult.invalid("Citation sourceId " + c.sourceId() + " is outside envelope allowedSourceIds scope: " + allowedSources);
                    }
                    if (c.locator() == null || c.locator().sourcePath() == null || c.locator().sourcePath().isBlank()) {
                        return ValidationResult.invalid("Citation locator sourcePath is missing.");
                    }
                    String path = c.locator().sourcePath();
                    if (path.contains("..") || path.contains("\\") || path.startsWith("/") || path.contains(":")) {
                        return ValidationResult.invalid("Citation sourcePath escapes allowed relative structure: " + path);
                    }
                    if (c.locator().pageRange() != null) {
                        if (c.locator().pageRange().size() != 2) {
                            return ValidationResult.invalid("Citation pageRange must have exactly [startPage, endPage].");
                        }
                        if (c.locator().pageRange().get(0) < 1 || c.locator().pageRange().get(1) < c.locator().pageRange().get(0)) {
                            return ValidationResult.invalid("Citation pageRange values are invalid.");
                        }
                    }
                }
                return ValidationResult.valid();

            } else if ("INSUFFICIENT_DATA".equals(resultType)) {
                if (resp.answer() != null) {
                    return ValidationResult.invalid("INSUFFICIENT_DATA must not contain an answer.");
                }
                if (resp.citations() != null && !resp.citations().isEmpty()) {
                    return ValidationResult.invalid("INSUFFICIENT_DATA must not contain citations.");
                }
                if (!"MISSING_REQUIRED_CONTEXT".equals(reasonCode) && !"NO_RELEVANT_EVIDENCE".equals(reasonCode)) {
                    return ValidationResult.invalid("INSUFFICIENT_DATA reasonCode must be MISSING_REQUIRED_CONTEXT or NO_RELEVANT_EVIDENCE.");
                }
                boolean hasLimitations = resp.limitations() != null && !resp.limitations().isEmpty();
                boolean hasMissingData = resp.missingData() != null && !resp.missingData().isEmpty();
                if (!hasLimitations && !hasMissingData) {
                    return ValidationResult.invalid("INSUFFICIENT_DATA requires limitations or missingData.");
                }
                if (resp.error() != null) {
                    return ValidationResult.invalid("INSUFFICIENT_DATA cannot contain error diagnostics.");
                }
                return ValidationResult.valid();
            }
            return ValidationResult.invalid("Unknown SUCCEEDED resultType: " + resultType);

        } else if ("REJECTED_BY_VALIDATOR".equals(runStatus)) {
            if ("BLOCKED".equals(resultType)) {
                if (!"RULE_BLOCKED".equals(reasonCode)) {
                    return ValidationResult.invalid("BLOCKED result requires RULE_BLOCKED reasonCode.");
                }
                if (resp.answer() != null) {
                    return ValidationResult.invalid("BLOCKED result must not contain an answer.");
                }
                if (resp.citations() != null && !resp.citations().isEmpty()) {
                    return ValidationResult.invalid("BLOCKED result must not contain citations.");
                }
                return ValidationResult.valid();
            } else if (resultType == null) {
                if (!"VALIDATION_FAILED".equals(reasonCode)) {
                    return ValidationResult.invalid("REJECTED_BY_VALIDATOR without BLOCKED must have VALIDATION_FAILED reasonCode.");
                }
                if (resp.error() == null) {
                    return ValidationResult.invalid("VALIDATION_FAILED must contain error diagnostics.");
                }
                if (resp.answer() != null) {
                    return ValidationResult.invalid("VALIDATION_FAILED must not contain an answer.");
                }
                return ValidationResult.valid();
            }
            return ValidationResult.invalid("Invalid resultType for REJECTED_BY_VALIDATOR: " + resultType);

        } else if ("FAILED".equals(runStatus)) {
            if (resultType != null) {
                return ValidationResult.invalid("FAILED runStatus must not have a resultType.");
            }
            if (resp.answer() != null) {
                return ValidationResult.invalid("FAILED runStatus must not contain an answer.");
            }
            if (resp.citations() != null && !resp.citations().isEmpty()) {
                return ValidationResult.invalid("FAILED runStatus must not contain citations.");
            }
            if (resp.error() == null) {
                return ValidationResult.invalid("FAILED runStatus must contain error diagnostics.");
            }
            if (!List.of("MODEL_ERROR", "PROVIDER_ERROR", "TIMEOUT", "PROCESSING_ERROR").contains(reasonCode)) {
                return ValidationResult.invalid("Invalid reasonCode for FAILED runStatus: " + reasonCode);
            }
            return ValidationResult.valid();
        }

        return ValidationResult.invalid("Unknown runStatus: " + runStatus);
    }

    private boolean isValidRfc3339DateTime(String dateTimeStr) {
        try {
            TemporalAccessor parsed = java.time.format.DateTimeFormatter.ISO_DATE_TIME.parse(dateTimeStr);
            int second = parsed.get(ChronoField.SECOND_OF_MINUTE);
            return second >= 0 && second <= 59;
        } catch (DateTimeParseException | UnsupportedOperationException e) {
            return false;
        }
    }
}
