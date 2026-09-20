package com.fitnesscoaching.platform.modules.user.adapter.in.web;

import com.fitnesscoaching.platform.modules.user.adapter.in.web.dto.CurrentUserResponse;
import com.fitnesscoaching.platform.modules.user.adapter.in.web.dto.UpdateCurrentUserRequest;
import com.fitnesscoaching.platform.modules.user.adapter.in.web.dto.UserCapabilitiesDto;
import com.fitnesscoaching.platform.modules.user.adapter.in.web.dto.UserSettingsDto;
import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import com.fitnesscoaching.platform.modules.user.application.model.PatchField;
import com.fitnesscoaching.platform.modules.user.application.port.in.GetCurrentUserUseCase;
import com.fitnesscoaching.platform.modules.user.application.port.in.UpdateCurrentUserCommand;
import com.fitnesscoaching.platform.modules.user.application.port.in.UpdateCurrentUserUseCase;
import com.fitnesscoaching.platform.modules.user.application.port.in.UpdateUserSettingsCommand;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class CurrentUserController {

    private final GetCurrentUserUseCase getCurrentUserUseCase;
    private final UpdateCurrentUserUseCase updateCurrentUserUseCase;

    public CurrentUserController(
            GetCurrentUserUseCase getCurrentUserUseCase,
            UpdateCurrentUserUseCase updateCurrentUserUseCase
    ) {
        this.getCurrentUserUseCase = getCurrentUserUseCase;
        this.updateCurrentUserUseCase = updateCurrentUserUseCase;
    }

    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        CurrentUserView view = getCurrentUserUseCase.getCurrentUser(userId);
        return ResponseEntity.ok(toResponse(view));
    }

    @PatchMapping("/me")
    public ResponseEntity<CurrentUserResponse> updateCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateCurrentUserRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UpdateCurrentUserCommand command = toCommand(userId, request);
        CurrentUserView updatedView = updateCurrentUserUseCase.updateCurrentUser(command);
        return ResponseEntity.ok(toResponse(updatedView));
    }

    private UpdateCurrentUserCommand toCommand(UUID userId, UpdateCurrentUserRequest request) {
        PatchField<String> displayName = request.isDisplayNameSpecified()
                ? PatchField.of(request.getDisplayName())
                : PatchField.omitted();

        PatchField<String> phoneNumber = request.isPhoneNumberSpecified()
                ? (request.getPhoneNumber() == null ? PatchField.ofNull() : PatchField.of(request.getPhoneNumber()))
                : PatchField.omitted();

        PatchField<String> preferredLocale = request.isPreferredLocaleSpecified()
                ? PatchField.of(request.getPreferredLocale())
                : PatchField.omitted();

        PatchField<String> timezone = request.isTimezoneSpecified()
                ? PatchField.of(request.getTimezone())
                : PatchField.omitted();

        PatchField<UpdateUserSettingsCommand> settings;
        if (request.isSettingsSpecified()) {
            if (request.getSettings() != null) {
                var s = request.getSettings();
                PatchField<Integer> weekStartsOn = s.isWeekStartsOnSpecified()
                        ? PatchField.of(s.getWeekStartsOn())
                        : PatchField.omitted();
                PatchField<String> measurementSystem = s.isMeasurementSystemSpecified()
                        ? PatchField.of(s.getMeasurementSystem())
                        : PatchField.omitted();
                PatchField<java.util.Map<String, Object>> accessibility = s.isAccessibilityPreferencesSpecified()
                        ? PatchField.of(s.getAccessibilityPreferences())
                        : PatchField.omitted();
                PatchField<java.util.Map<String, Object>> privacy = s.isPrivacyPreferencesSpecified()
                        ? PatchField.of(s.getPrivacyPreferences())
                        : PatchField.omitted();

                settings = PatchField.of(new UpdateUserSettingsCommand(
                        weekStartsOn,
                        measurementSystem,
                        accessibility,
                        privacy
                ));
            } else {
                settings = PatchField.ofNull();
            }
        } else {
            settings = PatchField.omitted();
        }

        return new UpdateCurrentUserCommand(
                userId,
                displayName,
                phoneNumber,
                preferredLocale,
                timezone,
                settings
        );
    }

    private CurrentUserResponse toResponse(CurrentUserView view) {
        return new CurrentUserResponse(
                view.id(),
                view.email(),
                view.displayName(),
                view.status(),
                view.preferredLocale(),
                view.timezone(),
                view.emailVerifiedAt(),
                view.createdAt(),
                view.phoneNumber(),
                view.roles(),
                new UserCapabilitiesDto(
                        view.capabilities().hasStudentProfile(),
                        view.capabilities().hasTrainerProfile(),
                        view.capabilities().canCoach()
                ),
                new UserSettingsDto(
                        view.settings().weekStartsOn(),
                        view.settings().measurementSystem(),
                        view.settings().accessibilityPreferences(),
                        view.settings().privacyPreferences()
                )
        );
    }
}
