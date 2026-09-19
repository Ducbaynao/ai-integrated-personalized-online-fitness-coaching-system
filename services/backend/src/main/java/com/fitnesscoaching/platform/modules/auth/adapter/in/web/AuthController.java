package com.fitnesscoaching.platform.modules.auth.adapter.in.web;

import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.ConfirmEmailRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.RegisterRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.RegistrationResponse;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.UserSummaryDto;
import com.fitnesscoaching.platform.modules.auth.application.port.in.ConfirmEmailCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.ConfirmEmailUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserResult;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegisterUserUseCase registerUserUseCase;
    private final ConfirmEmailUseCase confirmEmailUseCase;

    public AuthController(RegisterUserUseCase registerUserUseCase, ConfirmEmailUseCase confirmEmailUseCase) {
        this.registerUserUseCase = registerUserUseCase;
        this.confirmEmailUseCase = confirmEmailUseCase;
    }

    @PostMapping("/registrations")
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterUserCommand command = new RegisterUserCommand(
                request.email(),
                request.password(),
                request.displayName(),
                request.preferredLocale(),
                request.timezone()
        );

        RegisterUserResult result = registerUserUseCase.register(command);

        UserSummaryDto userSummary = new UserSummaryDto(
                result.id(),
                result.email(),
                result.displayName(),
                result.status(),
                result.preferredLocale(),
                result.timezone(),
                result.emailVerifiedAt(),
                result.createdAt()
        );

        URI location = URI.create("/api/v1/users/" + result.id());
        return ResponseEntity.created(location).body(RegistrationResponse.of(userSummary));
    }

    @PostMapping("/email-verifications/confirmations")
    public ResponseEntity<Void> confirmEmail(@Valid @RequestBody ConfirmEmailRequest request) {
        ConfirmEmailCommand command = new ConfirmEmailCommand(request.token());
        confirmEmailUseCase.confirmEmail(command);
        return ResponseEntity.noContent().build();
    }
}
