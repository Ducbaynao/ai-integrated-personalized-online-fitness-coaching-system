package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F05AiServiceClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SimulatedF05AiServiceClient implements F05AiServiceClient {

    private final ObjectMapper objectMapper;

    public SimulatedF05AiServiceClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String generateRescheduleJson(F05ServiceRequest request) {
        try {
            String tzName = request.timezone() != null && !request.timezone().isBlank()
                    ? request.timezone()
                    : "Asia/Ho_Chi_Minh";
            ZoneId zone;
            try {
                zone = ZoneId.of(tzName);
            } catch (Exception e) {
                zone = ZoneId.of("Asia/Ho_Chi_Minh");
                tzName = "Asia/Ho_Chi_Minh";
            }

            Instant plannedStart = request.plannedStartAt() != null
                    ? Instant.parse(request.plannedStartAt())
                    : Instant.now();
            Instant now = Instant.now();
            Instant candidateStart = plannedStart.plus(1, ChronoUnit.DAYS);
            if (!candidateStart.isAfter(now)) {
                candidateStart = now.plus(1, ChronoUnit.DAYS);
            }

            LocalDateTime localStartDt = LocalDateTime.ofInstant(candidateStart, zone)
                    .withHour(9).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime localEndDt = localStartDt.plusHours(1);

            ZoneOffset offset = zone.getRules().getOffset(localStartDt);
            String offsetFormatted = offset.getId().equals("Z") ? "+00:00" : offset.getId();

            Instant proposedStart = localStartDt.toInstant(offset);
            Instant proposedEnd = localEndDt.toInstant(offset);

            String supervision = request.supervisionRequirement() != null
                    ? request.supervisionRequirement()
                    : "SELF_PERFORMABLE";

            Map<String, Object> destination = new LinkedHashMap<>();
            destination.put("localStart", localStartDt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            destination.put("localEnd", localEndDt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            destination.put("timezone", tzName);
            destination.put("startUtcOffset", offsetFormatted);
            destination.put("endUtcOffset", offsetFormatted);
            destination.put("proposedStartAt", proposedStart.toString());
            destination.put("proposedEndAt", proposedEnd.toString());

            Map<String, Object> reschedule = new LinkedHashMap<>();
            reschedule.put("occurrenceId", request.occurrenceId().toString());
            if (request.planSessionId() != null) {
                reschedule.put("planSessionId", request.planSessionId().toString());
            }
            if (request.sessionName() != null) {
                reschedule.put("sessionName", request.sessionName());
            }
            reschedule.put("previousPlannedStartAt", request.plannedStartAt());
            reschedule.put("originalPlannedStartAt", request.originalPlannedStartAt() != null
                    ? request.originalPlannedStartAt()
                    : request.plannedStartAt());
            reschedule.put("supervisionRequirement", supervision);
            reschedule.put("destination", destination);

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("recommendationType", "WORKOUT_RESCHEDULE_PROPOSAL");
            root.put("summary", "Reschedule workout to " + localStartDt.toLocalDate() + " at 09:00 (" + tzName + ")");
            root.put("reasoning", List.of(
                    "Session " + (request.sessionName() != null ? request.sessionName() : "workout") + " was requested for rescheduling.",
                    "Proposed slot preserves recovery and avoids conflict with subsequent workouts.",
                    "Supervision requirement " + supervision + " is preserved."
            ));
            root.put("usedData", List.of(
                    "occurrenceId: " + request.occurrenceId(),
                    "previousPlannedStartAt: " + request.plannedStartAt(),
                    "supervisionRequirement: " + supervision,
                    "coachingMode: " + request.mode()
            ));
            root.put("missingData", List.of());
            root.put("limitations", List.of(
                    "Single occurrence reschedule per proposal (Phase 2 release scope baseline).",
                    "Proposal expires in 30 minutes."
            ));
            root.put("confidence", 0.95);
            root.put("reschedule", reschedule);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate simulated F05 workout reschedule response: " + e.getMessage(), e);
        }
    }
}
