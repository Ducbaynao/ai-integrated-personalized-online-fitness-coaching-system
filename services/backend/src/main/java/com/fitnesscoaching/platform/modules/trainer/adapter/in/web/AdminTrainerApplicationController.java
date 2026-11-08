package com.fitnesscoaching.platform.modules.trainer.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.AdminTrainerApplicationPageResponse;
import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.AdminTrainerApplicationResponse;
import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.AdminTrainerCertificateResponse;
import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.AdminTrainerDocumentResponse;
import com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto.DecideTrainerApplicationRequest;
import com.fitnesscoaching.platform.modules.trainer.application.model.AdminTrainerApplicationPage;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.DecideTrainerApplicationCommand;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.DecideTrainerApplicationUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.DownloadAdminTrainerApplicationDocumentUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationCertificatesUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationDetailUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationDocumentsUseCase;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationsQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.GetAdminTrainerApplicationsUseCase;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerCertificateInfo;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationDocumentContent;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationDocumentInfo;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/trainer-applications")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTrainerApplicationController {

    private final GetAdminTrainerApplicationsUseCase getApplicationsUseCase;
    private final GetAdminTrainerApplicationDetailUseCase getApplicationDetailUseCase;
    private final DecideTrainerApplicationUseCase decideApplicationUseCase;
    private final GetAdminTrainerApplicationDocumentsUseCase getDocumentsUseCase;
    private final DownloadAdminTrainerApplicationDocumentUseCase downloadDocumentUseCase;
    private final GetAdminTrainerApplicationCertificatesUseCase getCertificatesUseCase;

    public AdminTrainerApplicationController(
            GetAdminTrainerApplicationsUseCase getApplicationsUseCase,
            GetAdminTrainerApplicationDetailUseCase getApplicationDetailUseCase,
            DecideTrainerApplicationUseCase decideApplicationUseCase,
            GetAdminTrainerApplicationDocumentsUseCase getDocumentsUseCase,
            DownloadAdminTrainerApplicationDocumentUseCase downloadDocumentUseCase,
            GetAdminTrainerApplicationCertificatesUseCase getCertificatesUseCase
    ) {
        this.getApplicationsUseCase = getApplicationsUseCase;
        this.getApplicationDetailUseCase = getApplicationDetailUseCase;
        this.decideApplicationUseCase = decideApplicationUseCase;
        this.getDocumentsUseCase = getDocumentsUseCase;
        this.downloadDocumentUseCase = downloadDocumentUseCase;
        this.getCertificatesUseCase = getCertificatesUseCase;
    }

    @GetMapping
    public ResponseEntity<AdminTrainerApplicationPageResponse> getApplications(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());

        TrainerVerificationStatus verificationStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                verificationStatus = TrainerVerificationStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ApplicationValidationException("Invalid status parameter: " + status,
                        List.of(new FieldErrorDto("status", "Invalid", "Status must be a valid verification status")));
            }
        }

        GetAdminTrainerApplicationsQuery query = new GetAdminTrainerApplicationsQuery(
                adminUserId,
                verificationStatus,
                page,
                size
        );
        AdminTrainerApplicationPage resultPage = getApplicationsUseCase.getApplications(query);
        return ResponseEntity.ok(AdminTrainerApplicationPageResponse.fromDomain(resultPage));
    }

    @GetMapping("/{applicationId}")
    public ResponseEntity<AdminTrainerApplicationResponse> getApplicationDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        TrainerApplication app = getApplicationDetailUseCase.getApplicationDetail(adminUserId, applicationId);
        return ResponseEntity.ok(AdminTrainerApplicationResponse.fromDomain(app));
    }

    @PostMapping("/{applicationId}/decisions")
    public ResponseEntity<AdminTrainerApplicationResponse> decideApplication(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId,
            @Valid @RequestBody DecideTrainerApplicationRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        DecideTrainerApplicationCommand command = request.toCommand(adminUserId, applicationId);
        TrainerApplication app = decideApplicationUseCase.decideApplication(command);
        return ResponseEntity.ok(AdminTrainerApplicationResponse.fromDomain(app));
    }

    @GetMapping("/{applicationId}/documents")
    public ResponseEntity<List<AdminTrainerDocumentResponse>> getApplicationDocuments(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        List<TrainerVerificationDocumentInfo> docs = getDocumentsUseCase.getApplicationDocuments(adminUserId, applicationId);
        List<AdminTrainerDocumentResponse> response = docs.stream()
                .map(d -> AdminTrainerDocumentResponse.fromDomain(applicationId, d))
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{applicationId}/documents/{mediaId}/download")
    public ResponseEntity<byte[]> downloadApplicationDocument(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId,
            @PathVariable UUID mediaId
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        TrainerVerificationDocumentContent doc = downloadDocumentUseCase.downloadApplicationDocument(
                adminUserId, applicationId, mediaId);

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(doc.contentType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(doc.originalFilename()).build().toString())
                .contentType(mediaType)
                .body(doc.bytes());
    }

    @GetMapping("/{applicationId}/certificates")
    public ResponseEntity<List<AdminTrainerCertificateResponse>> getApplicationCertificates(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        List<TrainerCertificateInfo> certs = getCertificatesUseCase.getApplicationCertificates(adminUserId, applicationId);
        List<AdminTrainerCertificateResponse> response = certs.stream()
                .map(AdminTrainerCertificateResponse::fromDomain)
                .toList();
        return ResponseEntity.ok(response);
    }
}
