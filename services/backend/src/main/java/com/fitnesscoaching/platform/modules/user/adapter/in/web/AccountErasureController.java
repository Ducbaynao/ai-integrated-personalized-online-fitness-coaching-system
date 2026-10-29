package com.fitnesscoaching.platform.modules.user.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.user.application.service.AccountErasureService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/account-erasure-requests")
public class AccountErasureController {
    private final AccountErasureService service;
    private final Clock clock;
    public AccountErasureController(AccountErasureService service, Clock clock) {
        this.service = service; this.clock = clock;
    }

    public record Request(@NotNull UUID commandKey, @NotBlank String statusKey) {}

    @PostMapping
    public ResponseEntity<AccountErasureService.State> request(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody Request body) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(service.request(UUID.fromString(jwt.getSubject()), body.commandKey(), body.statusKey()));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<AccountErasureService.State> status(@PathVariable UUID requestId,
            @RequestHeader(value = "X-Erasure-Status-Key", required = false) String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(requestId, key));
    }

    @ExceptionHandler(AccountErasureService.Failure.class)
    public ResponseEntity<ErrorResponse> failure(AccountErasureService.Failure error) {
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
                .body(ErrorResponse.of(error.code, "Account erasure request unavailable",
                        Instant.now(clock), RequestIdHolder.get()));
    }
}
