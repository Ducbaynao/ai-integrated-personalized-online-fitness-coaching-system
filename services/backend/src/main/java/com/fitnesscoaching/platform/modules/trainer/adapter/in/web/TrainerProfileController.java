package com.fitnesscoaching.platform.modules.trainer.adapter.in.web;

import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.CreateTrainerProfileRequest;
import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.TrainerProfileResponse;
import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.UpdateTrainerProfileRequest;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.CreateTrainerProfileCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.CreateTrainerProfileUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetTrainerProfileUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.UpdateTrainerProfileCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.UpdateTrainerProfileUseCase;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trainer-profiles")
public class TrainerProfileController {

    private final CreateTrainerProfileUseCase createTrainerProfileUseCase;
    private final GetTrainerProfileUseCase getTrainerProfileUseCase;
    private final UpdateTrainerProfileUseCase updateTrainerProfileUseCase;

    public TrainerProfileController(
            CreateTrainerProfileUseCase createTrainerProfileUseCase,
            GetTrainerProfileUseCase getTrainerProfileUseCase,
            UpdateTrainerProfileUseCase updateTrainerProfileUseCase
    ) {
        this.createTrainerProfileUseCase = createTrainerProfileUseCase;
        this.getTrainerProfileUseCase = getTrainerProfileUseCase;
        this.updateTrainerProfileUseCase = updateTrainerProfileUseCase;
    }

    @PostMapping
    public ResponseEntity<TrainerProfileResponse> createTrainerProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateTrainerProfileRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        CreateTrainerProfileCommand command = request.toCommand(userId);

        TrainerProfile created = createTrainerProfileUseCase.createTrainerProfile(command);
        URI location = URI.create("/api/v1/trainer-profiles/me");
        return ResponseEntity.created(location).body(TrainerProfileResponse.fromDomain(created));
    }

    @GetMapping("/me")
    public ResponseEntity<TrainerProfileResponse> getMyTrainerProfile(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        TrainerProfile profile = getTrainerProfileUseCase.getTrainerProfile(userId);
        return ResponseEntity.ok(TrainerProfileResponse.fromDomain(profile));
    }

    @PatchMapping("/me")
    public ResponseEntity<TrainerProfileResponse> updateMyTrainerProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateTrainerProfileRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UpdateTrainerProfileCommand command = new UpdateTrainerProfileCommand(
                userId,
                request.toPublicSlugPatch(),
                request.toBioPatch(),
                request.toYearsExperiencePatch(),
                request.toAcceptingStudentsPatch()
        );

        TrainerProfile updated = updateTrainerProfileUseCase.updateTrainerProfile(command);
        return ResponseEntity.ok(TrainerProfileResponse.fromDomain(updated));
    }
}
