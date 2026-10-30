package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F02AiServiceClient;

import java.util.ArrayList;
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
        String continuity = request.continuityState();

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
        if ("PROLONGED_INACTIVITY".equals(continuity)) {
            days = 3;
        } else if (days != 4) {
            days = 3;
        }

        List<Map<String, Object>> sessions;
        List<String> reasoning = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        limitations.add("Proposal covers exactly 1 week (Phase 2 release scope baseline).");
        limitations.add("Loads and intensities must be self-regulated using RPE 7-8 guidelines.");

        List<String> usedData = new ArrayList<>(List.of(
                "student_id: " + request.studentId(),
                "coaching_mode: " + request.mode(),
                "fitness_level: " + level,
                "goal_type: " + goal,
                "requested_days_per_week: " + days
        ));

        if (continuity != null) {
            usedData.add("continuity_state: " + continuity);
            if (request.daysSinceLastWorkout() != null) {
                usedData.add("days_since_last_workout: " + request.daysSinceLastWorkout());
            }
        }

        String planNameSuffix = "Starter Plan";

        if ("PROLONGED_INACTIVITY".equals(continuity)) {
            planNameSuffix = "Return-to-Training Plan";
            reasoning.add("Trainee is returning after prolonged inactivity; starting with conservative re-adaptation volume and loads without assuming prior capacity.");
            limitations.add("Return-to-training protocol applied due to prolonged inactivity (>=100 days). Historical performance is not representative of current capacity; loads must be conservative.");
        } else if ("CONFIRMED_NEW".equals(continuity)) {
            reasoning.add("Trainee is confirmed new with zero training history; foundation movement patterns prioritized.");
            limitations.add("First-time introductory foundation; movement mechanics prioritized over loading.");
        } else if ("UNKNOWN_HISTORY".equals(continuity)) {
            reasoning.add("Trainee has unverified prior platform history; baseline calibration required.");
            limitations.add("Unverified prior platform history; baseline calibration required.");
        } else if ("RESTRICTED_HISTORY".equals(continuity)) {
            reasoning.add("Coach does not have access to trainee workout history; proposal requires student confirmation.");
            limitations.add("Workout history access restricted; baseline calibration required.");
        }

        if (days == 4) {
            reasoning.add("Program tailored for " + level + " level targeting " + goal + ".");
            reasoning.add("Distributed across a 4-day upper/lower split (Day 1 Upper Push, Day 2 Lower, Day 4 Upper Pull, Day 5 Lower/Conditioning).");
            reasoning.add("Volume calibrated at 3-4 sets per primary movement pattern.");
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
                                            "instructions", "Keep core engaged and avoid hyperextending lumbar spine."
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
            reasoning.add("Program tailored for " + level + " level targeting " + goal + ".");
            reasoning.add("Distributed across a 3-day split (Day 1 Upper, Day 3 Lower, Day 5 Conditioning) ensuring 48h recovery between sessions.");
            reasoning.add("Volume calibrated at 3-4 sets per primary movement pattern.");
            sessions = List.of(
                    Map.of(
                            "weekNumber", 1,
                            "dayNumber", 1,
                            "sequenceNumber", 1,
                            "name", "Day 1: Upper Body Strength & Posture",
                            "focus", "Upper horizontal push and horizontal pull",
                            "estimatedDurationMinutes", 50,
                            "supervision", "SELF_PERFORMABLE",
                            "notes", "Focus on full range of motion and scapular control.",
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
                                            "instructions", "Keep core engaged and avoid hyperextending lumbar spine."
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
                                            "instructions", "Break at hips and knees simultaneously; maintain neutral spine."
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
                "usedData", usedData,
                "missingData", List.of(
                        "recent_1rm_test_results",
                        "detailed_orthopedic_evaluations"
                ),
                "limitations", limitations,
                "confidence", 0.92,
                "plan", Map.of(
                        "name", "1-Week " + goal.replace('_', ' ') + " " + planNameSuffix,
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
