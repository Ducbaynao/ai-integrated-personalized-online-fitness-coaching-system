from datetime import UTC, datetime, timedelta, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.schemas.f05_schemas import (
    F05WorkoutRescheduleRequest,
    F05WorkoutRescheduleResponse,
    RescheduleDestinationSchema,
    WorkoutReschedulePayloadSchema,
)


class SimulatedF05WorkoutProvider:
    """Deterministic simulated provider generating F05 workout rescheduling proposals."""

    async def generate_reschedule(self, request: F05WorkoutRescheduleRequest) -> F05WorkoutRescheduleResponse:
        # Determine base time from plannedStartAt or now
        try:
            planned_dt = datetime.fromisoformat(request.plannedStartAt)
        except ValueError:
            planned_dt = datetime.now(UTC)

        now_utc = datetime.now(UTC)
        # Advance by 1 day or enough to be in the future, respecting 90-day horizon
        candidate_dt = planned_dt + timedelta(days=1)
        if candidate_dt <= now_utc or candidate_dt > now_utc + timedelta(days=89):
            candidate_dt = now_utc + timedelta(days=1)

        # Handle timezone
        tz_name = request.timezone or "Asia/Ho_Chi_Minh"
        try:
            tz = ZoneInfo(tz_name)
        except (ZoneInfoNotFoundError, ValueError):
            try:
                tz = ZoneInfo("Asia/Ho_Chi_Minh")
            except (ZoneInfoNotFoundError, ValueError):
                tz = timezone(timedelta(hours=7))
            tz_name = "Asia/Ho_Chi_Minh"

        local_dt = candidate_dt.astimezone(tz)
        hour = 9
        pref = (request.preferredTimeOfDay or "").upper()
        if pref == "AFTERNOON":
            hour = 14
        elif pref == "EVENING":
            hour = 18

        local_start = local_dt.replace(hour=hour, minute=0, second=0, microsecond=0)
        for _ in range(90):
            if local_start.astimezone(UTC) > now_utc:
                break
            local_start += timedelta(days=1)
        local_end = local_start + timedelta(hours=1)

        # Offset strings e.g. +07:00
        start_offset = local_start.strftime("%z")
        end_offset = local_end.strftime("%z")
        start_offset_formatted = f"{start_offset[:3]}:{start_offset[3:]}" if len(start_offset) == 5 else "+07:00"
        end_offset_formatted = f"{end_offset[:3]}:{end_offset[3:]}" if len(end_offset) == 5 else "+07:00"

        utc_start = local_start.astimezone(UTC)
        utc_end = local_end.astimezone(UTC)

        destination = RescheduleDestinationSchema(
            localStart=local_start.strftime("%Y-%m-%dT%H:%M:%S"),
            localEnd=local_end.strftime("%Y-%m-%dT%H:%M:%S"),
            timezone=tz_name,
            startUtcOffset=start_offset_formatted,
            endUtcOffset=end_offset_formatted,
            proposedStartAt=utc_start.strftime("%Y-%m-%dT%H:%M:%SZ"),
            proposedEndAt=utc_end.strftime("%Y-%m-%dT%H:%M:%SZ"),
        )

        # Invariant T10: Supervision requirement MUST be preserved and never degraded.
        if request.supervisionRequirement == "COACH_REQUIRED":
            supervision = "COACH_REQUIRED"
        elif request.supervisionRequirement in ("COACH_PREFERRED", "COACH_OPTIONAL"):
            supervision = "COACH_OPTIONAL"
        else:
            supervision = "SELF_PERFORMABLE"

        used_data = [
            f"occurrenceId: {request.occurrenceId}",
            f"previousPlannedStartAt: {request.plannedStartAt}",
            f"supervisionRequirement: {supervision}",
            f"coachingMode: {request.mode}",
        ]
        if request.continuityState:
            used_data.append(f"continuityState: {request.continuityState}")

        reasoning = [
            f"Session {request.sessionName or 'workout'} was missed or requested for reschedule.",
            f"Next available slot on {local_start.strftime('%Y-%m-%d')} at {local_start.strftime('%H:%M')} preserves recovery intervals.",
            f"Supervision requirement {supervision} is preserved.",
        ]

        payload = WorkoutReschedulePayloadSchema(
            occurrenceId=request.occurrenceId,
            planSessionId=request.planSessionId,
            sessionName=request.sessionName,
            previousPlannedStartAt=request.plannedStartAt,
            originalPlannedStartAt=request.originalPlannedStartAt or request.plannedStartAt,
            supervisionRequirement=supervision,
            destination=destination,
        )

        missing_data: list[str] = []
        limitations: list[str] = [
            "Reschedules one occurrence at a time (Phase 2 release scope baseline).",
            "Proposal expires in 30 minutes.",
        ]

        if request.continuityState == "PROLONGED_INACTIVITY":
            limitations.append("Prolonged inactivity detected (>= 100 days); return-to-training adaptation protocol applies.")
            reasoning.append("Prolonged inactivity observed; scheduling preserves gradual return spacing.")
        elif request.continuityState in ("UNKNOWN_HISTORY", "RESTRICTED_HISTORY"):
            missing_data.append("trainingContinuity")
            limitations.append(f"Training continuity status is {request.continuityState}; unrecorded training history cannot be verified.")
        elif request.continuityState == "CONFIRMED_NEW":
            reasoning.append("Confirmed beginner trainee with no prior workout history.")
        elif request.continuityState in ("RECENT_ACTIVITY_OBSERVED", "RECENT_CONSISTENT"):
            reasoning.append("Recent workout activity observed within normal training cadence.")

        return F05WorkoutRescheduleResponse(
            recommendationType="WORKOUT_RESCHEDULE_PROPOSAL",
            summary=f"Reschedule workout to {local_start.strftime('%Y-%m-%d %H:%M')} ({tz_name})",
            reasoning=reasoning,
            usedData=used_data,
            missingData=missing_data,
            limitations=limitations,
            confidence=0.95,
            reschedule=payload,
        )
