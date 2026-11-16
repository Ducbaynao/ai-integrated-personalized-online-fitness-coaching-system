from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

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
            planned_dt = datetime.fromisoformat(request.plannedStartAt.replace("Z", "+00:00"))
        except Exception:
            planned_dt = datetime.now(timezone.utc)

        now_utc = datetime.now(timezone.utc)
        # Advance by 1 day or enough to be in the future
        candidate_dt = planned_dt + timedelta(days=1)
        if candidate_dt <= now_utc:
            candidate_dt = now_utc + timedelta(days=1)

        # Handle timezone
        tz_name = request.timezone or "Asia/Ho_Chi_Minh"
        try:
            tz = ZoneInfo(tz_name)
        except Exception:
            try:
                tz = ZoneInfo("Asia/Ho_Chi_Minh")
            except Exception:
                tz = timezone(timedelta(hours=7))
            tz_name = "Asia/Ho_Chi_Minh"

        local_dt = candidate_dt.astimezone(tz)
        # Set to standard morning slot 09:00:00 local time
        local_start = local_dt.replace(hour=9, minute=0, second=0, microsecond=0)
        local_end = local_start + timedelta(hours=1)

        # Offset strings e.g. +07:00
        start_offset = local_start.strftime("%z")
        end_offset = local_end.strftime("%z")
        start_offset_formatted = f"{start_offset[:3]}:{start_offset[3:]}" if len(start_offset) == 5 else "+07:00"
        end_offset_formatted = f"{end_offset[:3]}:{end_offset[3:]}" if len(end_offset) == 5 else "+07:00"

        utc_start = local_start.astimezone(timezone.utc)
        utc_end = local_end.astimezone(timezone.utc)

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
        supervision = request.supervisionRequirement

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
            f"Next available slot on {local_start.strftime('%Y-%m-%d')} at 09:00 preserves recovery intervals.",
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

        return F05WorkoutRescheduleResponse(
            recommendationType="WORKOUT_RESCHEDULE_PROPOSAL",
            summary=f"Reschedule workout to {local_start.strftime('%Y-%m-%d 09:00')} ({tz_name})",
            reasoning=reasoning,
            usedData=used_data,
            missingData=[],
            limitations=[
                "Reschedules one occurrence at a time (Phase 2 release scope baseline).",
                "Proposal expires in 30 minutes.",
            ],
            confidence=0.95,
            reschedule=payload,
        )
