package com.fitnesscoaching.platform.modules.user.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.user.application.service.AccountDataExportService.AccountDataUnavailable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = CurrentUserController.class)
public class AccountDataExportAdvice {
    private final Clock clock;
    public AccountDataExportAdvice(Clock clock) { this.clock = clock; }

    @ExceptionHandler(AccountDataUnavailable.class)
    public ResponseEntity<ErrorResponse> unavailable() {
        return ResponseEntity.status(404).body(ErrorResponse.of("ACCOUNT_DATA_NOT_FOUND",
                "Account data is unavailable", Instant.now(clock), RequestIdHolder.get()));
    }
}
