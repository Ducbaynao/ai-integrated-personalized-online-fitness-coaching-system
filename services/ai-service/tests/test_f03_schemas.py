import json
from pathlib import Path
from uuid import UUID

import pytest
from pydantic import ValidationError

from app.schemas.f03_schemas import (
    F03WorkoutAdjustmentResponse,
    LoadAdjustmentAfter,
    LoadAdjustmentBefore,
    LoadAdjustmentChange,
    TargetRefSchema,
)

FIXTURES_DIR = Path(__file__).resolve().parent.parent.parent.parent / "contracts" / "ai-schemas" / "fixtures" / "f03"


def test_valid_exercise_substitution_occurrence():
    data = json.loads((FIXTURES_DIR / "valid-exercise-substitution-occurrence.json").read_text(encoding="utf-8"))
    response = F03WorkoutAdjustmentResponse.model_validate(data)
    assert response.recommendationType == "WORKOUT_ADJUSTMENT_PROPOSAL"
    assert len(response.changes) == 1
    change = response.changes[0]
    assert change.changeType == "EXERCISE_SUBSTITUTION"
    assert change.scope == "ONE_MATERIALIZED_OCCURRENCE"
    assert change.targetRef.plannedWorkoutId == UUID("3fa85f64-5717-4562-b3fc-2c963f66afa6")
    assert change.after.substitutionCategory == "EQUIPMENT_UNAVAILABLE"


def test_valid_exercise_substitution_template():
    data = json.loads((FIXTURES_DIR / "valid-exercise-substitution-template.json").read_text(encoding="utf-8"))
    response = F03WorkoutAdjustmentResponse.model_validate(data)
    assert len(response.changes) == 1
    change = response.changes[0]
    assert change.changeType == "EXERCISE_SUBSTITUTION"
    assert change.scope == "TEMPLATE_OR_FUTURE_OCCURRENCES"
    assert change.targetRef.planId == UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")


def test_valid_load_adjustment():
    data = json.loads((FIXTURES_DIR / "valid-load-adjustment.json").read_text(encoding="utf-8"))
    response = F03WorkoutAdjustmentResponse.model_validate(data)
    assert len(response.changes) == 1
    change = response.changes[0]
    assert change.changeType == "LOAD_ADJUSTMENT"
    assert change.after.targetLoad == 82.5
    assert change.after.deltaLoad == 2.5
    assert change.after.direction == "INCREASE"


def test_valid_load_adjustment_bodyweight():
    data = json.loads((FIXTURES_DIR / "valid-load-adjustment-bodyweight.json").read_text(encoding="utf-8"))
    response = F03WorkoutAdjustmentResponse.model_validate(data)
    change = response.changes[0]
    assert change.before.targetLoad is None
    assert change.after.targetLoad is None
    assert change.after.deltaLoad is None
    assert change.after.direction == "MAINTAIN"


def test_valid_volume_adjustment_reps():
    data = json.loads((FIXTURES_DIR / "valid-volume-adjustment-reps.json").read_text(encoding="utf-8"))
    response = F03WorkoutAdjustmentResponse.model_validate(data)
    change = response.changes[0]
    assert change.changeType == "VOLUME_ADJUSTMENT"
    assert change.before.targetSets == 3
    assert change.after.targetSets == 4
    assert change.after.deltaSets == 1


def test_valid_volume_adjustment_duration():
    data = json.loads((FIXTURES_DIR / "valid-volume-adjustment-duration.json").read_text(encoding="utf-8"))
    response = F03WorkoutAdjustmentResponse.model_validate(data)
    change = response.changes[0]
    assert change.changeType == "VOLUME_ADJUSTMENT"
    assert change.before.durationSeconds == 45
    assert change.after.durationSeconds == 60
    assert change.after.deltaDurationSeconds == 15


def test_invalid_missing_change_type_fails():
    data = json.loads((FIXTURES_DIR / "invalid-missing-change-type.json").read_text(encoding="utf-8"))
    with pytest.raises(ValidationError):
        F03WorkoutAdjustmentResponse.model_validate(data)


def test_invalid_load_negative_fails():
    data = json.loads((FIXTURES_DIR / "invalid-load-negative.json").read_text(encoding="utf-8"))
    with pytest.raises(ValidationError):
        F03WorkoutAdjustmentResponse.model_validate(data)


def test_invalid_target_scope_mismatch_fails():
    data = json.loads((FIXTURES_DIR / "invalid-target-scope-mismatch.json").read_text(encoding="utf-8"))
    with pytest.raises(ValidationError):
        F03WorkoutAdjustmentResponse.model_validate(data)


def test_invalid_extra_fields_substitution_fails():
    data = json.loads((FIXTURES_DIR / "invalid-extra-fields-substitution.json").read_text(encoding="utf-8"))
    with pytest.raises(ValidationError):
        F03WorkoutAdjustmentResponse.model_validate(data)


def test_contradictory_delta_load_fails():
    target = TargetRefSchema(
        scope="ONE_MATERIALIZED_OCCURRENCE",
        plannedWorkoutId=UUID("3fa85f64-5717-4562-b3fc-2c963f66afa6"),
        plannedSessionExerciseId=UUID("7c9e6679-7425-40de-944b-e07fc1f90ae7"),
    )
    with pytest.raises(ValidationError, match="Contradictory deltaLoad"):
        LoadAdjustmentChange(
            changeId=UUID("11111111-1111-4111-8111-111111111111"),
            scope="ONE_MATERIALIZED_OCCURRENCE",
            targetRef=target,
            before=LoadAdjustmentBefore(targetLoad=50.0, loadUnit="KG"),
            after=LoadAdjustmentAfter(targetLoad=60.0, loadUnit="KG", direction="INCREASE", deltaLoad=5.0), # Expected 10.0
            reason="Test contradictory delta",
        )
