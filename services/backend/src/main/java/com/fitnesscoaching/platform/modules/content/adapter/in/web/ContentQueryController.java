package com.fitnesscoaching.platform.modules.content.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.content.adapter.in.web.dto.KnowledgeQueryApiRequest;
import com.fitnesscoaching.platform.modules.content.adapter.in.web.dto.KnowledgeQueryApiResponse;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeQueryResult;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeQueryUseCase;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/content/knowledge")
public class ContentQueryController {

    private final KnowledgeQueryUseCase queryUseCase;
    private final UserAccountStatusQuery accountStatusQuery;
    private final UserRoleQuery userRoleQuery;

    public ContentQueryController(
            KnowledgeQueryUseCase queryUseCase,
            UserAccountStatusQuery accountStatusQuery,
            UserRoleQuery userRoleQuery
    ) {
        this.queryUseCase = queryUseCase;
        this.accountStatusQuery = accountStatusQuery;
        this.userRoleQuery = userRoleQuery;
    }

    @PostMapping("/query")
    public ResponseEntity<KnowledgeQueryApiResponse> queryActiveKnowledge(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody KnowledgeQueryApiRequest request
    ) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new AccessDeniedException("Authentication required to query knowledge");
        }

        UUID actorId = UUID.fromString(jwt.getSubject());
        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(actorId)
                .orElseThrow(() -> new AccountUnavailableException("Actor account does not exist"));

        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Actor account is not active");
        }

        boolean hasAuthorizedRole = userRoleQuery.hasActiveRole(actorId, "STUDENT")
                || userRoleQuery.hasActiveRole(actorId, "TRAINER")
                || userRoleQuery.hasActiveRole(actorId, "ADMIN");

        if (!hasAuthorizedRole) {
            throw new AccessDeniedException("User lacks authorized coaching or administrative role to query knowledge");
        }

        KnowledgeQueryResult result = queryUseCase.queryActiveKnowledge(
                request != null ? request.toDomain() : null
        );
        return ResponseEntity.ok(KnowledgeQueryApiResponse.fromDomain(result));
    }
}
