package com.fitnesscoaching.platform.modules.content.application.service;

import com.fitnesscoaching.platform.modules.content.application.port.in.AdminKnowledgeQueryUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeDocumentRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeReviewRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeVersionRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeReviewRecord;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AdminKnowledgeQueryService implements AdminKnowledgeQueryUseCase {

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final KnowledgeReviewRepository reviewRepository;

    public AdminKnowledgeQueryService(
            KnowledgeDocumentRepository documentRepository,
            KnowledgeVersionRepository versionRepository,
            KnowledgeReviewRepository reviewRepository
    ) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.reviewRepository = reviewRepository;
    }

    @Override
    public List<KnowledgeDocument> listDocuments(int offset, int limit) {
        return documentRepository.findAll(offset, limit);
    }

    @Override
    public Optional<KnowledgeDocument> getDocument(UUID documentId) {
        return documentRepository.findById(documentId);
    }

    @Override
    public List<KnowledgeVersion> listVersions(UUID documentId) {
        return versionRepository.findByDocumentId(documentId);
    }

    @Override
    public Optional<KnowledgeVersion> getVersion(UUID versionId) {
        return versionRepository.findById(versionId);
    }

    @Override
    public List<KnowledgeReviewRecord> listReviews(UUID versionId) {
        return reviewRepository.findByVersionId(versionId);
    }
}
