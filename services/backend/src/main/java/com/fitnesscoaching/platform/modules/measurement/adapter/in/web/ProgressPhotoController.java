package com.fitnesscoaching.platform.modules.measurement.adapter.in.web;

import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.ProgressPhotoPageResponse;
import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.ProgressPhotoResponse;
import com.fitnesscoaching.platform.modules.measurement.application.service.ProgressPhotoStorageService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/measurements/progress-photos")
public class ProgressPhotoController {

    private final ProgressPhotoStorageService photoService;

    public ProgressPhotoController(ProgressPhotoStorageService photoService) {
        this.photoService = photoService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProgressPhotoResponse> uploadPhoto(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "studentId", required = false) UUID studentId,
            @RequestParam(value = "viewType", required = false, defaultValue = "OTHER") String viewType,
            @RequestParam(value = "takenAt", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant takenAt,
            @RequestParam(value = "weightAtTime", required = false) BigDecimal weightAtTime,
            @RequestParam(value = "weightUnitCode", required = false) String weightUnitCode,
            @RequestParam(value = "notes", required = false) String notes
    ) throws IOException {
        UUID actorId = UUID.fromString(jwt.getSubject());
        byte[] bytes = file.getBytes();
        String filename = file.getOriginalFilename();

        ProgressPhotoResponse response = photoService.uploadProgressPhoto(
                actorId, studentId, bytes, filename, viewType, takenAt, weightAtTime, weightUnitCode, notes
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<ProgressPhotoPageResponse> listPhotos(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID studentId,
            @RequestParam(required = false) String viewType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false, defaultValue = "Asia/Ho_Chi_Minh") String timezone,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        ZoneId zone = ZoneId.of(timezone != null ? timezone : "Asia/Ho_Chi_Minh");
        ProgressPhotoPageResponse response = photoService.listProgressPhotos(
                actorId, studentId, viewType, fromDate, toDate, zone, page, size
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> getPhotoFile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        var download = photoService.loadPhotoFile(actorId, id);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, download.contentType())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-transform, max-age=3600")
                .body(download.data());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePhoto(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        photoService.deletePhoto(actorId, id);
        return ResponseEntity.noContent().build();
    }
}
