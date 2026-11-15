package com.fitnesscoaching.platform.modules.support.correction.adapter.in.web;

import com.fitnesscoaching.platform.modules.support.correction.adapter.in.web.dto.DataCorrectionPageResponse;
import com.fitnesscoaching.platform.modules.support.correction.adapter.in.web.dto.DataCorrectionResponse;
import com.fitnesscoaching.platform.modules.support.correction.adapter.in.web.dto.ExecuteDataCorrectionRequest;
import com.fitnesscoaching.platform.modules.support.correction.application.model.DataCorrectionModel;
import com.fitnesscoaching.platform.modules.support.correction.application.model.DataCorrectionPage;
import com.fitnesscoaching.platform.modules.support.correction.application.port.in.DataCorrectionUseCase;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/data-corrections")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDataCorrectionController {

    private final DataCorrectionUseCase dataCorrectionUseCase;

    public AdminDataCorrectionController(DataCorrectionUseCase dataCorrectionUseCase) {
        this.dataCorrectionUseCase = dataCorrectionUseCase;
    }

    @GetMapping
    public ResponseEntity<DataCorrectionPageResponse> searchCorrections(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID supportCaseId,
            @RequestParam(required = false) String targetType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        DataCorrectionPage result = dataCorrectionUseCase.searchCorrections(adminUserId, supportCaseId, targetType, page, size);
        return ResponseEntity.ok(DataCorrectionPageResponse.fromDomain(result));
    }

    @GetMapping("/{correctionId}")
    public ResponseEntity<DataCorrectionResponse> getCorrection(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID correctionId
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        DataCorrectionModel model = dataCorrectionUseCase.getCorrection(adminUserId, correctionId);
        return ResponseEntity.ok(DataCorrectionResponse.fromDomain(model));
    }

    @PostMapping
    public ResponseEntity<DataCorrectionResponse> executeCorrection(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ExecuteDataCorrectionRequest request
    ) {
        UUID adminUserId = UUID.fromString(jwt.getSubject());
        DataCorrectionModel model = dataCorrectionUseCase.executeCorrection(adminUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(DataCorrectionResponse.fromDomain(model));
    }
}
