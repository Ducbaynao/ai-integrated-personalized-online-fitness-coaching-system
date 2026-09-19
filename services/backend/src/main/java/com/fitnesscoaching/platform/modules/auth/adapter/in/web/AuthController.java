package com.fitnesscoaching.platform.modules.auth.adapter.in.web;

import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.ConfirmEmailRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.RegisterRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.RegistrationResponse;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.UserSummaryDto;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.LoginRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.RefreshTokenRequest;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.SessionUserDto;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.TokenPairResponse;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.UserCapabilitiesDto;
import com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto.UserSettingsDto;
import com.fitnesscoaching.platform.modules.auth.application.port.in.ConfirmEmailCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.ConfirmEmailUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserResult;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RegisterUserUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.LoginCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.LoginUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RefreshSessionCommand;
import com.fitnesscoaching.platform.modules.auth.application.port.in.RefreshSessionUseCase;
import com.fitnesscoaching.platform.modules.auth.application.port.in.TokenPairResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegisterUserUseCase registerUserUseCase;
    private final ConfirmEmailUseCase confirmEmailUseCase;
    private final LoginUseCase loginUseCase;
    private final RefreshSessionUseCase refreshSessionUseCase;

    public AuthController(
            RegisterUserUseCase registerUserUseCase,
            ConfirmEmailUseCase confirmEmailUseCase,
            LoginUseCase loginUseCase,
            RefreshSessionUseCase refreshSessionUseCase
    ) {
        this.registerUserUseCase = registerUserUseCase;
        this.confirmEmailUseCase = confirmEmailUseCase;
        this.loginUseCase = loginUseCase;
        this.refreshSessionUseCase = refreshSessionUseCase;
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

    @PostMapping("/sessions")
    public TokenPairResponse login(@Valid @RequestBody LoginRequest request) {
        return toResponse(loginUseCase.login(
                new LoginCommand(request.email(), request.password(), request.deviceName())));
    }

    @PostMapping("/token-refreshes")
    public TokenPairResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return toResponse(refreshSessionUseCase.refresh(
                new RefreshSessionCommand(request.refreshToken(), request.deviceName())));
    }

    private TokenPairResponse toResponse(TokenPairResult result) {
        var user = result.user();
        SessionUserDto userDto = new SessionUserDto(
                user.id(),
                user.email(),
                user.displayName(),
                user.status(),
                user.preferredLocale(),
                user.timezone(),
                user.emailVerifiedAt(),
                user.createdAt(),
                user.phoneNumber(),
                user.roles(),
                new UserCapabilitiesDto(
                        user.capabilities().hasStudentProfile(),
                        user.capabilities().hasTrainerProfile(),
                        user.capabilities().canCoach()
                ),
                new UserSettingsDto(
                        user.settings().weekStartsOn(),
                        user.settings().measurementSystem(),
                        user.settings().accessibilityPreferences(),
                        user.settings().privacyPreferences()
                )
        );
        return new TokenPairResponse(
                "Bearer", result.accessToken(), result.accessTokenExpiresAt(), result.refreshToken(),
                result.refreshTokenExpiresAt(), userDto);
    }
}
