package com.fitnesscoaching.platform.modules.trainer.adapter.in.web;

import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.SubmitTrainerApplicationRequest;
import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.TrainerApplicationResponse;
import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.TrainerDocumentUploadResponse;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetCurrentTrainerApplicationUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.SubmitTrainerApplicationCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.SubmitTrainerApplicationUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.UploadTrainerVerificationDocumentUseCase;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationDocumentInfo;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trainer-applications")
public class TrainerApplicationController {

    private final SubmitTrainerApplicationUseCase submitTrainerApplicationUseCase;
    private final GetCurrentTrainerApplicationUseCase getCurrentTrainerApplicationUseCase;
    private final UploadTrainerVerificationDocumentUseCase uploadDocumentUseCase;

    public TrainerApplicationController(
            SubmitTrainerApplicationUseCase submitTrainerApplicationUseCase,
            GetCurrentTrainerApplicationUseCase getCurrentTrainerApplicationUseCase,
            UploadTrainerVerificationDocumentUseCase uploadDocumentUseCase
    ) {
        this.submitTrainerApplicationUseCase = submitTrainerApplicationUseCase;
        this.getCurrentTrainerApplicationUseCase = getCurrentTrainerApplicationUseCase;
        this.uploadDocumentUseCase = uploadDocumentUseCase;
    }

    @PostMapping
    public ResponseEntity<TrainerApplicationResponse> submitApplication(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SubmitTrainerApplicationRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        SubmitTrainerApplicationCommand command = request.toCommand(userId);

        TrainerApplication submitted = submitTrainerApplicationUseCase.submitApplication(command);
        URI location = URI.create("/api/v1/trainer-applications/me/current");
        return ResponseEntity.created(location).body(TrainerApplicationResponse.fromDomain(submitted));
    }

    @GetMapping("/me/current")
    public ResponseEntity<TrainerApplicationResponse> getCurrentApplication(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        TrainerApplication application = getCurrentTrainerApplicationUseCase.getCurrentApplication(userId);
        return ResponseEntity.ok(TrainerApplicationResponse.fromDomain(application));
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<TrainerDocumentUploadResponse> uploadDocument(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        UUID userId = UUID.fromString(jwt.getSubject());
        TrainerVerificationDocumentInfo doc = uploadDocumentUseCase.uploadDocument(
                userId,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getBytes()
        );
        return ResponseEntity.status(201).body(new TrainerDocumentUploadResponse(
                doc.id(),
                doc.originalFilename(),
                doc.contentType(),
                doc.sizeBytes(),
                doc.scanStatus(),
                doc.createdAt()
        ));
    }
}
