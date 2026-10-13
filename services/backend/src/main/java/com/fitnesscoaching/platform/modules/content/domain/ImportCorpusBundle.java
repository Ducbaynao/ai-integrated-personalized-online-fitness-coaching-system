package com.fitnesscoaching.platform.modules.content.domain;

import java.util.List;

/**
 * Encapsulates a bundle of knowledge items to be ingested into the platform.
 */
public record ImportCorpusBundle(
        int formatVersion,
        String purpose,
        String bundleName,
        List<ImportKnowledgeItem> items
) {
}
