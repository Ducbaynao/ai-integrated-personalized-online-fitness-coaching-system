package com.fitnesscoaching.platform.modules.content.domain;

import java.util.List;
import java.util.Set;

/**
 * Shared domain rules and constants for knowledge source identities.
 * Ensures consistent canonicalization, alias resolution, and allowlist validation
 * across content indexing, retrieval, envelope construction, and runtime output validation.
 */
public final class KnowledgeSourceIdentity {

    private KnowledgeSourceIdentity() {}

    public static final String ODS_CANONICAL = "NIH-ODS-EXERCISE-SUPPLEMENTS";
    public static final String ODS_LEGACY_ALIAS = "ODS-EXERCISE-SUPPLEMENTS-2024";

    public static final Set<String> APPROVED_SOURCES = Set.of(
            "WHO-PA-SB-2020",
            "ISSN-PROTEIN-EXERCISE-2017",
            ODS_CANONICAL,
            "NIDDK-WEIGHT-FACTORS-2023"
    );

    public static final List<String> CANONICAL_APPROVED_SOURCES_LIST = List.of(
            "ISSN-PROTEIN-EXERCISE-2017",
            "WHO-PA-SB-2020",
            ODS_CANONICAL,
            "NIDDK-WEIGHT-FACTORS-2023"
    );

    public static String canonicalize(String sourceId) {
        if (sourceId == null) {
            return null;
        }
        String trimmed = sourceId.trim();
        if (ODS_LEGACY_ALIAS.equalsIgnoreCase(trimmed) || ODS_CANONICAL.equalsIgnoreCase(trimmed)) {
            return ODS_CANONICAL;
        }
        return trimmed;
    }

    public static boolean isApproved(String sourceId) {
        String canonical = canonicalize(sourceId);
        return canonical != null && APPROVED_SOURCES.contains(canonical);
    }
}
