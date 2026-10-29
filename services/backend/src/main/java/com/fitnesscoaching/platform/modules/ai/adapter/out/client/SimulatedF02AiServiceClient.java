package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F02AiServiceClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SimulatedF02AiServiceClient implements F02AiServiceClient {

    private final ObjectMapper objectMapper;

    public SimulatedF02AiServiceClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static final UUID DEFAULT_V1 = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID DEFAULT_V2 = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final UUID DEFAULT_V3 = UUID.fromString("a0000000-0000-4000-8000-000000000003");
    private static final UUID DEFAULT_V4 = UUID.fromString("a0000000-0000-4000-8000-000000000004");

    @Override
    public String generateProposalJson(F02ServiceRequest request) {
        List<UUID> vars = request.candidateVariationIds() != null && !request.candidateVariationIds().isEmpty()
                ? request.candidateVariationIds()
                : List.of(DEFAULT_V1, DEFAULT_V2, DEFAULT_V3, DEFAULT_V4);

        UUID v1 = vars.size() > 0 ? vars.get(0) : DEFAULT_V1;
        UUID v2 = vars.size() > 1 ? vars.get(1) : DEFAULT_V2;
        UUID v3 = vars.size() > 2 ? vars.get(2) : DEFAULT_V3;
        UUID v4 = vars.size() > 3 ? vars.get(3) : DEFAULT_V4;

        String goal = request.goalType() != null ? request.goalType() : "GENERAL_FITNESS";
        String level = request.fitnessLevel() != null ? request.fitnessLevel() : "BEGINNER";

        int days = 3;
        if (request.preferences() != null) {
            Object d = request.preferences().get("daysPerWeek");
            if (d == null) d = request.preferences().get("preferredDaysPerWeek");
            if (d instanceof Number n) {
                days = n.intValue();
            } else if (d instanceof String s) {
                try {
                    days = Integer.parseInt(s);
                } catch (Exception ignored) {}
            }
        }
        if (days != 4) days = 3;

        List<Map<String, Object>> sessions;
        List<String> reasoning;

        if (days == 4) {
            reasoning = List.of(
                    "Program tailored for " + level + " level targeting " + goal + ".",
                    "Distributed across a 4-day upper/lower split (Day 1 Upper Push, Day 2 Lower, Day 4 Upper Pull, Day 5 Lower/Conditioning).",
                    "Volume calibrated at 3-4 sets per primary movement pattern."
            );
            sessions = List.of(
                    Map.of(
                            "weekNumber", 1,
                            "dayNumber", 1,
                            "sequenceNumber", 1,
                            "name", "Day 1: Upper Body Push & Core",
                            "focus", "Horizontal and vertical press mechanics",
                            "estimatedDurationMinutes", 50,
                            "supervision", "SELF_PERFORMABLE",
                            "notes", "Focus on controlled eccentric phase.",
                            "prescriptions", List.of(
                                    Map.of(
                                            "exerciseVariationId", v1.toString(),
                                            "sequenceNumber", 1,
                                            "targetSets", 3,
                                            "targetRepsMin", 8,
                                            "targetRepsMax", 12,
                                            "targetLoad", 20.0,
                                            "restSeconds", 90,
                                            "instructions", "Lower weight with 2-second eccentric phase."
                                    )
                            )
                    ),
                    Map.of(
                            "weekNumber", 1,
                            "dayNumber", 2,
                            "sequenceNumber", 2,
                            "name", "Day 2: Lower Body Foundations",
                            "focus", "Squat pattern and ankle mobility",
                            "estimatedDurationMinutes", 50,
                            "supervision", "SELF_PERFORMABLE",
                            "notes", "Adequate warm-up for hips and ankles.",
                            "prescriptions", List.of(
                                    Map.of(
                                            "exerciseVariationId", v3.toString(),
                                            "sequenceNumber", 1,
                                            "targetSets", 4,
                                            "targetRepsMin", 8,
                                            "targetRepsMax", 10,
                                            "targetLoad", 30.0,
                                            "restSeconds", 120,
                                            "instructions", "Break at hips and knees simultaneously."
                                    )
                            )
                    ),
                    Map.of(
                            "weekNumber", 1,
                            "dayNumber", 4,
                            "sequenceNumber", 3,
                            "name", "Day 3: Upper Body Pull & Posterior",
                            "focus", "Horizontal and vertical pull volume",
                            "estimatedDurationMinutes", 50,
                            "supervision", "SELF_PERFORMABLE",
                            "notes", "Focus on scapular retraction.",
                            "prescriptions", List.of(
                                    Map.of(
                                            "exerciseVariationId", v2.toString(),
                                            "sequenceNumber", 1,
                                            "targetSets", 3,
                                            "targetRepsMin", 10,
                                            "targetRepsMax", 12,
                                            "restSeconds", 60,
                                            "instructions", "Keep core engaged."
                                    )
                            )
                    ),
                    Map.of(
                            "weekNumber", 1,
                            "dayNumber", 5,
                            "sequenceNumber", 4,
                            "name", "Day 4: Lower Body & Functional Conditioning",
                            "focus", "Hinge mechanics and work capacity",
                            "estimatedDurationMinutes", 45,
                            "supervision", "SELF_PERFORMABLE",
                            "notes", "Controlled tempo throughout all repetitions.",
                            "prescriptions", List.of(
                                    Map.of(
                                            "exerciseVariationId", v4.toString(),
                                            "sequenceNumber", 1,
                                            "targetSets", 3,
                                            "targetRepsMin", 12,
                                            "targetRepsMax", 15,
                                            "restSeconds", 60,
                                            "instructions", "Controlled tempo throughout all repetitions."
                                    )
                            )
                    )
            );
        } else {
            reasoning = List.of(
                    "Program tailored for " + level + " level targeting " + goal + ".",
                    "Distributed across a 3-day split (Day 1 Upper, Day 3 Lower, Day 5 Conditioning) ensuring 48h recovery.",
                    "Volume calibrated at 3-4 sets per primary movement pattern."
            );
            sessions = List.of(
                    Map.of(
                            "weekNumber", 1,
                            "dayNumber", 1,
                            "sequenceNumber", 1,
                            "name", "Day 1: Upper Body Strength & Posture",
                            "focus", "Upper horizontal push and horizontal pull",
                            "estimatedDurationMinutes", 50,
                            "supervision", "SELF_PERFORMABLE",
                            "notes", "Focus on full range of motion.",
                            "prescriptions", List.of(
                                    Map.of(
                                            "exerciseVariationId", v1.toString(),
                                            "sequenceNumber", 1,
                                            "targetSets", 3,
                                            "targetRepsMin", 8,
                                            "targetRepsMax", 12,
                                            "targetLoad", 20.0,
                                            "restSeconds", 90,
                                            "instructions", "Lower weight with 2-second eccentric phase."
                                    ),
                                    Map.of(
                                            "exerciseVariationId", v2.toString(),
                                            "sequenceNumber", 2,
                                            "targetSets", 3,
                                            "targetRepsMin", 10,
                                            "targetRepsMax", 12,
                                            "restSeconds", 60,
                                            "instructions", "Keep core engaged."
                                    )
                            )
                    ),
                    Map.of(
                            "weekNumber", 1,
                            "dayNumber", 3,
                            "sequenceNumber", 2,
                            "name", "Day 2: Lower Body Foundations",
                            "focus", "Squat pattern and hinge mechanics",
                            "estimatedDurationMinutes", 55,
                            "supervision", "SELF_PERFORMABLE",
                            "notes", "Adequate warm-up for ankles and hip adductors required.",
                            "prescriptions", List.of(
                                    Map.of(
                                            "exerciseVariationId", v3.toString(),
                                            "sequenceNumber", 1,
                                            "targetSets", 4,
                                            "targetRepsMin", 8,
                                            "targetRepsMax", 10,
                                            "targetLoad", 30.0,
                                            "restSeconds", 120,
                                            "instructions", "Break at hips and knees simultaneously."
                                    )
                            )
                    ),
                    Map.of(
                            "weekNumber", 1,
                            "dayNumber", 5,
                            "sequenceNumber", 3,
                            "name", "Day 3: Full Body Conditioning",
                            "focus", "Compound movement endurance and core stability",
                            "estimatedDurationMinutes", 45,
                            "supervision", "SELF_PERFORMABLE",
                            "notes", "Keep rest periods brisk to build work capacity.",
                            "prescriptions", List.of(
                                    Map.of(
                                            "exerciseVariationId", v4.toString(),
                                            "sequenceNumber", 1,
                                            "targetSets", 3,
                                            "targetRepsMin", 12,
                                            "targetRepsMax", 15,
                                            "restSeconds", 60,
                                            "instructions", "Controlled tempo throughout all repetitions."
                                    )
                            )
                    )
            );
        }

        Map<String, Object> payload = Map.of(
                "recommendationType", "WORKOUT_PLAN_PROPOSAL",
                "summary", "1-week personalized workout proposal targeting " + goal + " with " + days + " balanced training sessions.",
                "reasoning", reasoning,
                "usedData", List.of(
                        "student_id: " + request.studentId(),
                        "coaching_mode: " + request.mode(),
                        "fitness_level: " + level,
                        "goal_type: " + goal,
                        "requested_days_per_week: " + days
                ),
                "missingData", List.of(
                        "recent_1rm_test_results",
                        "detailed_orthopedic_evaluations"
                ),
                "limitations", List.of(
                        "Proposal covers exactly 1 week (Phase 2 release scope baseline).",
                        "Loads and intensities must be self-regulated using RPE 7-8 guidelines."
                ),
                "confidence", 0.92,
                "plan", Map.of(
                        "name", "1-Week " + goal.replace('_', ' ') + " Starter Plan",
                        "description", "Structured " + days + "-day foundation program created for " + level.toLowerCase() + " trainees.",
                        "durationWeeks", 1,
                        "sessions", sessions
                )
        );

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize simulated F02 workout proposal", e);
        }
    }
}
