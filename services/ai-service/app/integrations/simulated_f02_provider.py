from uuid import UUID

from app.schemas.f02_schemas import (
    ExercisePrescriptionSchema,
    F02WorkoutProposalRequest,
    F02WorkoutProposalResponse,
    SessionTemplateSchema,
    WorkoutPlanPayloadSchema,
)

DEFAULT_VARIATION_IDS = [
    UUID("a0000000-0000-4000-8000-000000000001"),
    UUID("a0000000-0000-4000-8000-000000000002"),
    UUID("a0000000-0000-4000-8000-000000000003"),
    UUID("a0000000-0000-4000-8000-000000000004"),
]


class SimulatedF02WorkoutProvider:
    """Deterministic simulated provider generating 1-week workout plan proposals with zero external API spend."""

    async def generate_proposal(self, request: F02WorkoutProposalRequest) -> F02WorkoutProposalResponse:
        variations = request.candidateVariationIds if request.candidateVariationIds else DEFAULT_VARIATION_IDS
        v1 = variations[0] if len(variations) > 0 else DEFAULT_VARIATION_IDS[0]
        v2 = variations[1] if len(variations) > 1 else DEFAULT_VARIATION_IDS[1]
        v3 = variations[2] if len(variations) > 2 else DEFAULT_VARIATION_IDS[2]
        v4 = variations[3] if len(variations) > 3 else DEFAULT_VARIATION_IDS[3]

        goal = request.goalType or "GENERAL_FITNESS"
        level = request.fitnessLevel or "BEGINNER"
        mode = request.mode
        continuity = request.continuityState

        days = 3
        if request.preferences:
            if "daysPerWeek" in request.preferences:
                try:
                    days = int(request.preferences["daysPerWeek"])
                except (ValueError, TypeError):
                    pass
            elif "preferredDaysPerWeek" in request.preferences:
                try:
                    days = int(request.preferences["preferredDaysPerWeek"])
                except (ValueError, TypeError):
                    pass
        if continuity == "PROLONGED_INACTIVITY":
            days = 3
        elif days not in (3, 4):
            days = 3

        used_data = [
            f"student_id: {request.studentId}",
            f"coaching_mode: {mode}",
            f"fitness_level: {level}",
            f"goal_type: {goal}",
            f"requested_days_per_week: {days}",
        ]
        if request.equipmentAvailable:
            used_data.append(f"equipment: {', '.join(request.equipmentAvailable)}")
        if continuity:
            used_data.append(f"continuity_state: {continuity}")
            if request.daysSinceLastWorkout is not None:
                used_data.append(f"days_since_last_workout: {request.daysSinceLastWorkout}")

        missing_data = [
            "recent_1rm_test_results",
            "detailed_orthopedic_evaluations",
        ]

        reasoning = []
        limitations = [
            "Proposal covers exactly 1 week (Phase 2 release scope baseline).",
            "Loads and intensities must be self-regulated using RPE 7-8 guidelines.",
        ]

        plan_name_suffix = "Starter Plan"

        if continuity == "PROLONGED_INACTIVITY":
            plan_name_suffix = "Return-to-Training Plan"
            reasoning.append(
                "Trainee is returning after prolonged inactivity; starting with conservative re-adaptation volume and loads without assuming prior capacity."
            )
            limitations.append(
                "Return-to-training protocol applied due to prolonged inactivity (>=100 days). Historical performance is not representative of current capacity; loads must be conservative."
            )
        elif continuity == "CONFIRMED_NEW":
            reasoning.append(
                "Trainee is confirmed new with zero training history; foundation movement patterns prioritized."
            )
            limitations.append(
                "First-time introductory foundation; movement mechanics prioritized over loading."
            )
        elif continuity == "UNKNOWN_HISTORY":
            reasoning.append(
                "Trainee has unverified prior platform history; baseline calibration required."
            )
            limitations.append(
                "Unverified prior platform history; baseline calibration required."
            )
        elif continuity == "RESTRICTED_HISTORY":
            reasoning.append(
                "Coach does not have access to trainee workout history; proposal requires student confirmation."
            )
            limitations.append(
                "Workout history access restricted; baseline calibration required."
            )

        if days == 4:
            reasoning.extend([
                f"Program tailored for {level} level targeting {goal.replace('_', ' ').lower()}.",
                "Distributed across a 4-day upper/lower split (Day 1 Upper Push, Day 2 Lower, Day 4 Upper Pull, Day 5 Lower/Conditioning) with rest days scheduled after each 2-day training block.",
                "Volume calibrated at 3-4 sets per primary movement pattern with conservative starting loads.",
            ])
        else:
            reasoning.extend([
                f"Program tailored for {level} level targeting {goal.replace('_', ' ').lower()}.",
                "Distributed across a 3-day split (Day 1 Upper, Day 3 Lower, Day 5 Conditioning) ensuring 48h recovery between sessions.",
                "Volume calibrated at 3-4 sets per primary movement pattern with conservative starting loads.",
            ])

        if days == 4:
            sessions = [
                SessionTemplateSchema(
                    weekNumber=1,
                    dayNumber=1,
                    sequenceNumber=1,
                    name="Day 1: Upper Body Push & Core",
                    focus="Horizontal and vertical press mechanics",
                    estimatedDurationMinutes=50,
                    supervision="SELF_PERFORMABLE",
                    notes="Focus on controlled eccentric phase.",
                    prescriptions=[
                        ExercisePrescriptionSchema(
                            exerciseVariationId=v1,
                            sequenceNumber=1,
                            targetSets=3,
                            targetRepsMin=8,
                            targetRepsMax=12,
                            targetLoad=20.0,
                            restSeconds=90,
                            instructions="Lower weight with 2-second eccentric phase.",
                        ),
                    ],
                ),
                SessionTemplateSchema(
                    weekNumber=1,
                    dayNumber=2,
                    sequenceNumber=2,
                    name="Day 2: Lower Body Foundations",
                    focus="Squat pattern and ankle mobility",
                    estimatedDurationMinutes=50,
                    supervision="SELF_PERFORMABLE",
                    notes="Adequate warm-up for hips and ankles.",
                    prescriptions=[
                        ExercisePrescriptionSchema(
                            exerciseVariationId=v3,
                            sequenceNumber=1,
                            targetSets=4,
                            targetRepsMin=8,
                            targetRepsMax=10,
                            targetLoad=30.0,
                            restSeconds=120,
                            instructions="Break at hips and knees simultaneously.",
                        ),
                    ],
                ),
                SessionTemplateSchema(
                    weekNumber=1,
                    dayNumber=4,
                    sequenceNumber=3,
                    name="Day 3: Upper Body Pull & Posterior",
                    focus="Horizontal and vertical pull volume",
                    estimatedDurationMinutes=50,
                    supervision="SELF_PERFORMABLE",
                    notes="Focus on scapular retraction.",
                    prescriptions=[
                        ExercisePrescriptionSchema(
                            exerciseVariationId=v2,
                            sequenceNumber=1,
                            targetSets=3,
                            targetRepsMin=10,
                            targetRepsMax=12,
                            restSeconds=60,
                            instructions="Keep core engaged and avoid hyperextending lumbar spine.",
                        ),
                    ],
                ),
                SessionTemplateSchema(
                    weekNumber=1,
                    dayNumber=5,
                    sequenceNumber=4,
                    name="Day 4: Lower Body & Functional Conditioning",
                    focus="Hinge mechanics and work capacity",
                    estimatedDurationMinutes=45,
                    supervision="SELF_PERFORMABLE",
                    notes="Controlled tempo throughout all repetitions.",
                    prescriptions=[
                        ExercisePrescriptionSchema(
                            exerciseVariationId=v4,
                            sequenceNumber=1,
                            targetSets=3,
                            targetRepsMin=12,
                            targetRepsMax=15,
                            restSeconds=60,
                            instructions="Controlled tempo throughout all repetitions.",
                        ),
                    ],
                ),
            ]
        else:
            sessions = [
                SessionTemplateSchema(
                    weekNumber=1,
                    dayNumber=1,
                    sequenceNumber=1,
                    name="Day 1: Upper Body Strength & Posture",
                    focus="Upper horizontal push and horizontal pull",
                    estimatedDurationMinutes=50,
                    supervision="SELF_PERFORMABLE",
                    notes="Focus on full range of motion and scapular control.",
                    prescriptions=[
                        ExercisePrescriptionSchema(
                            exerciseVariationId=v1,
                            sequenceNumber=1,
                            targetSets=3,
                            targetRepsMin=8,
                            targetRepsMax=12,
                            targetLoad=20.0,
                            restSeconds=90,
                            instructions="Lower weight with 2-second eccentric phase.",
                        ),
                        ExercisePrescriptionSchema(
                            exerciseVariationId=v2,
                            sequenceNumber=2,
                            targetSets=3,
                            targetRepsMin=10,
                            targetRepsMax=12,
                            restSeconds=60,
                            instructions="Keep core engaged and avoid hyperextending lumbar spine.",
                        ),
                    ],
                ),
                SessionTemplateSchema(
                    weekNumber=1,
                    dayNumber=3,
                    sequenceNumber=2,
                    name="Day 2: Lower Body Foundations",
                    focus="Squat pattern and hinge mechanics",
                    estimatedDurationMinutes=55,
                    supervision="SELF_PERFORMABLE",
                    notes="Adequate warm-up for ankles and hip adductors required.",
                    prescriptions=[
                        ExercisePrescriptionSchema(
                            exerciseVariationId=v3,
                            sequenceNumber=1,
                            targetSets=4,
                            targetRepsMin=8,
                            targetRepsMax=10,
                            targetLoad=30.0,
                            restSeconds=120,
                            instructions="Break at hips and knees simultaneously; maintain neutral spine.",
                        ),
                    ],
                ),
                SessionTemplateSchema(
                    weekNumber=1,
                    dayNumber=5,
                    sequenceNumber=3,
                    name="Day 3: Full Body Conditioning",
                    focus="Compound movement endurance and core stability",
                    estimatedDurationMinutes=45,
                    supervision="SELF_PERFORMABLE",
                    notes="Keep rest periods brisk to build work capacity.",
                    prescriptions=[
                        ExercisePrescriptionSchema(
                            exerciseVariationId=v4,
                            sequenceNumber=1,
                            targetSets=3,
                            targetRepsMin=12,
                            targetRepsMax=15,
                            restSeconds=60,
                            instructions="Controlled tempo throughout all repetitions.",
                        ),
                    ],
                ),
            ]

        if continuity == "PROLONGED_INACTIVITY":
            # Cap targetSets at 3 for gentle return-to-training volume
            for s in sessions:
                for p in s.prescriptions:
                    if p.targetSets > 3:
                        p.targetSets = 3
            plan_description = f"Structured {days}-day return-to-training foundation program created for {level.lower()} trainees returning after prolonged inactivity."
        else:
            plan_description = f"Structured {days}-day foundation program created for {level.lower()} trainees."

        plan = WorkoutPlanPayloadSchema(
            name=f"1-Week {goal.replace('_', ' ').title()} {plan_name_suffix}",
            description=plan_description,
            durationWeeks=1,
            sessions=sessions,
        )

        return F02WorkoutProposalResponse(
            recommendationType="WORKOUT_PLAN_PROPOSAL",
            summary=f"1-week personalized workout proposal targeting {goal} with {days} balanced training sessions.",
            reasoning=reasoning,
            usedData=used_data,
            missingData=missing_data,
            limitations=limitations,
            confidence=0.92,
            plan=plan,
        )
