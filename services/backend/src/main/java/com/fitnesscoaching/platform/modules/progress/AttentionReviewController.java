package com.fitnesscoaching.platform.modules.progress;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AttentionReviewController {
    private final AttentionReviewService service;
    public AttentionReviewController(AttentionReviewService service) { this.service=service; }

    @GetMapping("/review-timezone/me")
    public ResponseEntity<AttentionReviewService.TimezoneConfirmation> timezone(@AuthenticationPrincipal Jwt jwt) {
        var result=service.timezone(actor(jwt));
        return result==null?ResponseEntity.noContent().build():ResponseEntity.ok(result);
    }
    @PutMapping("/review-timezone/me")
    public AttentionReviewService.TimezoneConfirmation confirmTimezone(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConfirmTimezone body) {
        return service.confirmTimezone(actor(jwt),body.timezone(),body.commandKey());
    }
    @GetMapping("/review-tasks/me")
    public AttentionReviewService.Page<AttentionReviewService.Review> myReviews(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.reviews(actor(jwt),actor(jwt),page,size);
    }
    @GetMapping("/students/{studentId}/review-tasks")
    public AttentionReviewService.Page<AttentionReviewService.Review> trainerReviews(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return service.reviews(actor(jwt),studentId,page,size);
    }
    @GetMapping("/review-tasks/{id}")
    public AttentionReviewService.Review review(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        return service.review(actor(jwt),id);
    }
    @PostMapping("/review-tasks/{id}/complete")
    public AttentionReviewService.Review complete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
            @Valid @RequestBody CompleteReview body) {
        return service.complete(actor(jwt),id,body.expectedVersion(),body.commandKey(),body.summary(),
                body.evidenceRefs(),body.missingData());
    }
    @PostMapping("/review-tasks/{id}/acknowledge")
    public AttentionReviewService.Review acknowledge(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
            @Valid @RequestBody VersionedCommand body) {
        return service.acknowledgeReview(actor(jwt),id,body.expectedVersion(),body.commandKey());
    }
    @GetMapping("/attention-signals/me")
    public AttentionReviewService.Page<AttentionReviewService.Signal> mySignals(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.signals(actor(jwt),actor(jwt),page,size);
    }
    @GetMapping("/students/{studentId}/attention-signals")
    public AttentionReviewService.Page<AttentionReviewService.Signal> trainerSignals(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return service.signals(actor(jwt),studentId,page,size);
    }
    @GetMapping("/attention-signals/{id}")
    public AttentionReviewService.Signal signal(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        return service.signal(actor(jwt),id);
    }
    @GetMapping("/attention-signals/{id}/history")
    public List<AttentionReviewService.SignalEvent> history(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        return service.signalHistory(actor(jwt),id);
    }
    @PostMapping("/attention-signals/{id}/actions")
    public AttentionReviewService.Signal act(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
            @Valid @RequestBody SignalAction body) {
        return service.actOnSignal(actor(jwt),id,body.action(),body.expectedVersion(),body.commandKey(),body.reason());
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record ConfirmTimezone(@NotBlank String timezone,@NotNull UUID commandKey) {}
    public record VersionedCommand(long expectedVersion,@NotNull UUID commandKey) {}
    public record CompleteReview(long expectedVersion,@NotNull UUID commandKey,
            @NotBlank @Size(max=4000) String summary,@NotNull List<String> evidenceRefs,
            @NotNull List<String> missingData) {}
    public record SignalAction(@NotBlank String action,long expectedVersion,@NotNull UUID commandKey,String reason) {}
}
