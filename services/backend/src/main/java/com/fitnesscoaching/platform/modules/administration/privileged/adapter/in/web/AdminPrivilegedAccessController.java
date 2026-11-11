package com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web;

import com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto.PrivilegedAccessPageResponse;
import com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto.PrivilegedAccessResponse;
import com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto.RequestPrivilegedAccessDto;
import com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto.RevokePrivilegedAccessDto;
import com.fitnesscoaching.platform.modules.administration.privileged.application.model.PrivilegedAccessModel;
import com.fitnesscoaching.platform.modules.administration.privileged.application.model.PrivilegedAccessPage;
import com.fitnesscoaching.platform.modules.administration.privileged.application.port.in.PrivilegedAccessUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/privileged-access")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPrivilegedAccessController {

    private final PrivilegedAccessUseCase privilegedAccessUseCase;
    private final Clock clock;

    public AdminPrivilegedAccessController(PrivilegedAccessUseCase privilegedAccessUseCase, Clock clock) {
        this.privilegedAccessUseCase = privilegedAccessUseCase;
        this.clock = clock;
    }

    @GetMapping
    public ResponseEntity<PrivilegedAccessPageResponse> listRequests(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String purpose,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        PrivilegedAccessPage result = privilegedAccessUseCase.searchRequests(adminUserId, status, purpose, page, size);
        return ResponseEntity.ok(PrivilegedAccessPageResponse.fromDomain(result, Instant.now(clock)));
    }

    @GetMapping("/active")
    public ResponseEntity<PrivilegedAccessResponse> getActiveRequest(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID targetUserId
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        Instant now = Instant.now(clock);
        List<PrivilegedAccessModel> list = privilegedAccessUseCase.getActiveRequestsForAdmin(adminUserId);
        if (targetUserId != null) {
            list = list.stream()
                    .filter(m -> targetUserId.equals(m.targetUserId()))
                    .toList();
        }
        if (list.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(PrivilegedAccessResponse.fromDomain(list.get(0), now));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<PrivilegedAccessResponse> getRequest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        PrivilegedAccessModel model = privilegedAccessUseCase.getRequest(adminUserId, requestId);
        return ResponseEntity.ok(PrivilegedAccessResponse.fromDomain(model, Instant.now(clock)));
    }

    @PostMapping
    public ResponseEntity<PrivilegedAccessResponse> requestAccess(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RequestPrivilegedAccessDto request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        PrivilegedAccessModel model = privilegedAccessUseCase.requestPrivilegedAccess(adminUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(PrivilegedAccessResponse.fromDomain(model, Instant.now(clock)));
    }

    @PostMapping("/{requestId}/revoke")
    public ResponseEntity<PrivilegedAccessResponse> revokeAccess(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId,
            @Valid @RequestBody RevokePrivilegedAccessDto request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        PrivilegedAccessModel model = privilegedAccessUseCase.revokePrivilegedAccess(adminUserId, requestId, request);
        return ResponseEntity.ok(PrivilegedAccessResponse.fromDomain(model, Instant.now(clock)));
    }
}
