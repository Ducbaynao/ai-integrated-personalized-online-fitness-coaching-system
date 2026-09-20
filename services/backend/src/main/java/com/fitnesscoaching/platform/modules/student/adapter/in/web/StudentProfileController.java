package com.fitnesscoaching.platform.modules.student.adapter.in.web;

import com.fitnesscoaching.platform.modules.student.adapter.in.web.dto.CreateStudentProfileRequest;
import com.fitnesscoaching.platform.modules.student.adapter.in.web.dto.StudentProfileResponse;
import com.fitnesscoaching.platform.modules.student.adapter.in.web.dto.UpdateStudentProfileRequest;
import com.fitnesscoaching.platform.modules.student.application.port.in.CreateStudentProfileCommand;
import com.fitnesscoaching.platform.modules.student.application.port.in.CreateStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.application.port.in.GetStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.application.port.in.UpdateStudentProfileCommand;
import com.fitnesscoaching.platform.modules.student.application.port.in.UpdateStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
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
@RequestMapping("/api/v1/student-profiles")
public class StudentProfileController {

    private final CreateStudentProfileUseCase createStudentProfileUseCase;
    private final GetStudentProfileUseCase getStudentProfileUseCase;
    private final UpdateStudentProfileUseCase updateStudentProfileUseCase;

    public StudentProfileController(
            CreateStudentProfileUseCase createStudentProfileUseCase,
            GetStudentProfileUseCase getStudentProfileUseCase,
            UpdateStudentProfileUseCase updateStudentProfileUseCase
    ) {
        this.createStudentProfileUseCase = createStudentProfileUseCase;
        this.getStudentProfileUseCase = getStudentProfileUseCase;
        this.updateStudentProfileUseCase = updateStudentProfileUseCase;
    }

    @PostMapping
    public ResponseEntity<StudentProfileResponse> createStudentProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateStudentProfileRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        CreateStudentProfileCommand command = request.toCommand(userId);

        StudentProfile created = createStudentProfileUseCase.createStudentProfile(command);
        URI location = URI.create("/api/v1/student-profiles/me");
        return ResponseEntity.created(location).body(StudentProfileResponse.fromDomain(created));
    }

    @GetMapping("/me")
    public ResponseEntity<StudentProfileResponse> getMyStudentProfile(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        StudentProfile profile = getStudentProfileUseCase.getStudentProfile(userId);
        return ResponseEntity.ok(StudentProfileResponse.fromDomain(profile));
    }

    @PatchMapping("/me")
    public ResponseEntity<StudentProfileResponse> updateMyStudentProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateStudentProfileRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UpdateStudentProfileCommand command = new UpdateStudentProfileCommand(
                userId,
                request.toDateOfBirthPatch(),
                request.toGenderPatch(),
                request.toTrainingExperienceLevelPatch(),
                request.toTrainingExperienceMonthsPatch(),
                request.toAvailableDaysPerWeekPatch(),
                request.toPreferredSessionMinutesPatch(),
                request.toOnboardingCompletedPatch()
        );

        StudentProfile updated = updateStudentProfileUseCase.updateStudentProfile(command);
        return ResponseEntity.ok(StudentProfileResponse.fromDomain(updated));
    }
}
