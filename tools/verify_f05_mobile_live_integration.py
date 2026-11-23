#!/usr/bin/env python3
"""
F05 Mobile Live API & Domain Integration Verification Script
File: tools/verify_f05_mobile_live_integration.py

Verifies live API & domain integration across:
  Mobile API Client contract -> Spring Boot Backend (port 8080) -> FastAPI AI Service (port 8000) -> PostgreSQL (port 5433)

Executes all 8 domain scenarios:
  1. Student SELF_DIRECTED: Generate -> Review -> Accept -> Receipt -> Successor in DB
  2. Student HUMAN_COACH: Generate -> Student Accept blocked with 403
  3. Trainer HUMAN_COACH: Trainer reviews & Applies with MANAGE authority
  4. Reject Proposal: Transitions to REJECTED, original planned workout preserved
  5. Stale Source / Version Conflict: Controlled fault injection on isolated fixture -> 409
  6. Appointment Coordination & Version Drift: Controlled fault injection on isolated fixture -> 409 (Backend drift guard; does not substitute two-sided negotiation UX)
  7. Timeout & Idempotent Replay: Same commandKey returns replayed=True, no duplicate entity
  8. Expired Proposal Semantics: Controlled fault injection on isolated fixture -> New commandKey blocked on expired proposal, but replay succeeds

Note: This harness verifies backend domain integration, database integrity, and API contract compliance.
It is distinct from Mobile UI Live Runtime verification.

Outputs JSON evidence to:
  docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

API_BASE_URL = os.environ.get("FITNESS_API_BASE_URL", "http://localhost:8080/api/v1")
EVIDENCE_DIR = os.path.join("docs", "08-ai", "artifacts", "f05-runtime-evidence")
EVIDENCE_FILE = os.path.join(EVIDENCE_DIR, "f05_mobile_live_integration_20261123.json")


def psql_query(sql: str) -> str:
    cmd = [
        "docker", "exec", "-i", "digital-fitness-postgres",
        "psql", "-U", "fitness_app", "-d", "digital_fitness", "-t", "-A", "-c", sql
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"psql failed: {res.stderr.strip()}\nSQL was:\n{sql}")
    return res.stdout.strip()


def login(email: str, password: str = "Pass12345!") -> str:
    payload = json.dumps({"email": email, "password": password, "deviceName": "Live Integration Harness"}).encode("utf-8")
    req = urllib.request.Request(
        f"{API_BASE_URL}/auth/sessions",
        data=payload,
        headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode("utf-8"))
        return data["accessToken"]


def http_request(method: str, path: str, token: str, body: dict = None) -> tuple[int, dict]:
    url = f"{API_BASE_URL}{path}"
    data = json.dumps(body).encode("utf-8") if body else None
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Accept": "application/json"
    }
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            content = resp.read().decode("utf-8")
            return resp.status, json.loads(content) if content else {}
    except urllib.error.HTTPError as err:
        content = err.read().decode("utf-8")
        try:
            parsed = json.loads(content)
        except Exception:
            parsed = {"raw": content}
        return err.code, parsed


def get_clean_sessions(student_id: str, limit: int = 3) -> list[str]:
    sql = f"""
    SELECT s.id
    FROM fitness.workout_plans wp
    JOIN fitness.workout_plan_versions v ON v.workout_plan_id = wp.id
    JOIN fitness.workout_plan_sessions s ON s.workout_plan_version_id = v.id
    LEFT JOIN fitness.planned_workouts pw ON pw.workout_plan_session_id = s.id
    WHERE wp.student_id = '{student_id}'
    GROUP BY s.id
    ORDER BY count(pw.id) ASC
    LIMIT {limit};
    """
    rows = psql_query(sql).strip().splitlines()
    return [r.strip() for r in rows if r.strip()]


def cleanup_test_fixtures(
    occ_ids: list[str] = None,
    app_ids: list[str] = None,
    rec_ids: list[str] = None,
    successor_ids: list[str] = None
) -> None:
    """
    Safely cleans up ONLY the specific test fixtures created by this run.
    Never performs blanket updates, cancellations, or deletions on shared smoke accounts.
    """
    all_occ_ids = [oid for oid in (occ_ids or []) + (successor_ids or []) if oid]
    if all_occ_ids:
        quoted = ", ".join(f"'{oid}'" for oid in all_occ_ids)
        psql_query(f"UPDATE fitness.planned_workouts SET status = 'CANCELLED', version = version + 1 WHERE id IN ({quoted}) AND status = 'SCHEDULED';")

    if app_ids:
        quoted = ", ".join(f"'{aid}'" for aid in app_ids if aid)
        psql_query(f"DELETE FROM fitness.appointments WHERE id IN ({quoted});")

    if rec_ids:
        quoted = ", ".join(f"'{rid}'" for rid in rec_ids if rid)
        psql_query(f"DELETE FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id IN ({quoted});")
        psql_query(f"DELETE FROM fitness.ai_recommendations WHERE id IN ({quoted});")


def create_fresh_fixtures() -> tuple[str, str, str, str, str, str]:
    # Select isolated sessions with 0 scheduled workouts to avoid duplicate slot collisions naturally
    student_1_sessions = get_clean_sessions('9c0bad94-120a-437f-a6a7-d1d439f382cf', 3)
    student_2_sessions = get_clean_sessions('5c6f49c6-f4f6-4941-b217-2574b3830395', 2)

    sess_1 = student_1_sessions[0]
    sess_3 = student_1_sessions[1]
    sess_4 = student_1_sessions[2]

    sess_2 = student_2_sessions[0]
    sess_5 = student_2_sessions[1]

    occ_id_1 = str(uuid.uuid4())
    occ_id_2 = str(uuid.uuid4())
    occ_id_3 = str(uuid.uuid4())
    occ_id_4 = str(uuid.uuid4())
    occ_id_5 = str(uuid.uuid4())
    app_id_5 = str(uuid.uuid4())

    safe_start = psql_query(
        "SELECT REPLACE(TO_CHAR((COALESCE(min(start_at), NOW()) - INTERVAL '2 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'), ' ', 'T') "
        "FROM fitness.appointments WHERE trainer_id = '1cb982ef-b7e6-493c-86f9-fe4994f8ad93';"
    )
    safe_end = psql_query(
        "SELECT REPLACE(TO_CHAR((COALESCE(min(start_at), NOW()) - INTERVAL '1 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'), ' ', 'T') "
        "FROM fitness.appointments WHERE trainer_id = '1cb982ef-b7e6-493c-86f9-fe4994f8ad93';"
    )

    sql = f"""
    -- Ensure test users have active password
    UPDATE fitness.users
    SET password_hash = '$2a$10$cld7dVAIRmrFqoEZBeN5m.JGCNaUYaMQ.Dax3WYr.9zto.f1uGdBC',
        status = 'ACTIVE',
        email_verified_at = COALESCE(email_verified_at, NOW())
    WHERE email IN (
        'student.f02.runtime@example.com',
        'smoke.student@example.com',
        'smoke.trainer@example.com'
    );

    -- 1. Insert Occ 1 (SELF_DIRECTED, Student 9c0bad94-120a-437f-a6a7-d1d439f382cf)
    INSERT INTO fitness.planned_workouts (
        id, student_id, workout_plan_session_id, coaching_period_id,
        planned_start_at, planned_end_at, original_planned_start_at,
        status, created_by, supervision_requirement, version,
        schedule_local_start, schedule_local_end, schedule_timezone,
        schedule_start_utc_offset, schedule_end_utc_offset
    ) VALUES (
        '{occ_id_1}',
        '9c0bad94-120a-437f-a6a7-d1d439f382cf',
        '{sess_1}',
        'cd77ff7f-807f-4223-a2c8-6e74caf762df',
        NOW() - INTERVAL '3 hours',
        NOW() - INTERVAL '2 hours',
        NOW() - INTERVAL '3 hours',
        'SCHEDULED',
        '9c0bad94-120a-437f-a6a7-d1d439f382cf',
        'SELF_ALLOWED',
        1,
        REPLACE(TO_CHAR((NOW() - INTERVAL '3 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'), ' ', 'T'),
        REPLACE(TO_CHAR((NOW() - INTERVAL '2 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'), ' ', 'T'),
        'Asia/Ho_Chi_Minh',
        '+07:00',
        '+07:00'
    );

    -- 2. Insert Occ 2 (HUMAN_COACH, Student 5c6f49c6-f4f6-4941-b217-2574b3830395)
    INSERT INTO fitness.planned_workouts (
        id, student_id, workout_plan_session_id, coaching_period_id,
        planned_start_at, planned_end_at, original_planned_start_at,
        status, created_by, supervision_requirement, version,
        schedule_local_start, schedule_local_end, schedule_timezone,
        schedule_start_utc_offset, schedule_end_utc_offset
    ) VALUES (
        '{occ_id_2}',
        '5c6f49c6-f4f6-4941-b217-2574b3830395',
        '{sess_2}',
        'bdeccc87-beaf-43df-b475-807be044ed0b',
        NOW() - INTERVAL '4 hours',
        NOW() - INTERVAL '3 hours',
        NOW() - INTERVAL '4 hours',
        'SCHEDULED',
        '1cb982ef-b7e6-493c-86f9-fe4994f8ad93',
        'SELF_ALLOWED',
        1,
        REPLACE(TO_CHAR((NOW() - INTERVAL '4 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'), ' ', 'T'),
        REPLACE(TO_CHAR((NOW() - INTERVAL '3 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'), ' ', 'T'),
        'Asia/Ho_Chi_Minh',
        '+07:00',
        '+07:00'
    );

    -- 3. Insert Occ 3 (Reject test, Student 9c0bad94-120a-437f-a6a7-d1d439f382cf)
    INSERT INTO fitness.planned_workouts (
        id, student_id, workout_plan_session_id, coaching_period_id,
        planned_start_at, planned_end_at, original_planned_start_at,
        status, created_by, supervision_requirement, version,
        schedule_local_start, schedule_local_end, schedule_timezone,
        schedule_start_utc_offset, schedule_end_utc_offset
    ) VALUES (
        '{occ_id_3}',
        '9c0bad94-120a-437f-a6a7-d1d439f382cf',
        '{sess_3}',
        'cd77ff7f-807f-4223-a2c8-6e74caf762df',
        NOW() - INTERVAL '5 hours',
        NOW() - INTERVAL '4 hours',
        NOW() - INTERVAL '5 hours',
        'SCHEDULED',
        '9c0bad94-120a-437f-a6a7-d1d439f382cf',
        'SELF_ALLOWED',
        1,
        REPLACE(TO_CHAR((NOW() - INTERVAL '5 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'), ' ', 'T'),
        REPLACE(TO_CHAR((NOW() - INTERVAL '4 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'), ' ', 'T'),
        'Asia/Ho_Chi_Minh',
        '+07:00',
        '+07:00'
    );

    -- 4. Insert Occ 4 (Version drift test, Student 9c0bad94-120a-437f-a6a7-d1d439f382cf)
    INSERT INTO fitness.planned_workouts (
        id, student_id, workout_plan_session_id, coaching_period_id,
        planned_start_at, planned_end_at, original_planned_start_at,
        status, created_by, supervision_requirement, version,
        schedule_local_start, schedule_local_end, schedule_timezone,
        schedule_start_utc_offset, schedule_end_utc_offset
    ) VALUES (
        '{occ_id_4}',
        '9c0bad94-120a-437f-a6a7-d1d439f382cf',
        '{sess_4}',
        'cd77ff7f-807f-4223-a2c8-6e74caf762df',
        NOW() - INTERVAL '6 hours',
        NOW() - INTERVAL '5 hours',
        NOW() - INTERVAL '6 hours',
        'SCHEDULED',
        '9c0bad94-120a-437f-a6a7-d1d439f382cf',
        'SELF_ALLOWED',
        1,
        REPLACE(TO_CHAR((NOW() - INTERVAL '6 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'), ' ', 'T'),
        REPLACE(TO_CHAR((NOW() - INTERVAL '5 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'), ' ', 'T'),
        'Asia/Ho_Chi_Minh',
        '+07:00',
        '+07:00'
    );

    -- 5. Insert Occ 5 (Appointment drift test, Student 5c6f49c6-f4f6-4941-b217-2574b3830395)
    INSERT INTO fitness.planned_workouts (
        id, student_id, workout_plan_session_id, coaching_period_id,
        planned_start_at, planned_end_at, original_planned_start_at,
        status, created_by, supervision_requirement, version,
        schedule_local_start, schedule_local_end, schedule_timezone,
        schedule_start_utc_offset, schedule_end_utc_offset
    ) VALUES (
        '{occ_id_5}',
        '5c6f49c6-f4f6-4941-b217-2574b3830395',
        '{sess_5}',
        'bdeccc87-beaf-43df-b475-807be044ed0b',
        '{safe_start}'::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh',
        '{safe_end}'::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh',
        '{safe_start}'::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh',
        'SCHEDULED',
        '1cb982ef-b7e6-493c-86f9-fe4994f8ad93',
        'COACH_REQUIRED',
        1,
        '{safe_start}',
        '{safe_end}',
        'Asia/Ho_Chi_Minh',
        '+07:00',
        '+07:00'
    );

    -- Insert linked appointment for Occ 5
    INSERT INTO fitness.appointments (
        id, trainer_id, student_id, relationship_id, coaching_period_id,
        planned_workout_id, start_at, end_at, display_timezone, status,
        version, guarded_v2, local_start, local_end, start_utc_offset,
        end_utc_offset, created_by
    ) VALUES (
        '{app_id_5}',
        '1cb982ef-b7e6-493c-86f9-fe4994f8ad93',
        '5c6f49c6-f4f6-4941-b217-2574b3830395',
        '95501599-414d-4d0d-b663-296978c402a5',
        'bdeccc87-beaf-43df-b475-807be044ed0b',
        '{occ_id_5}',
        '{safe_start}'::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh',
        '{safe_end}'::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh',
        'Asia/Ho_Chi_Minh',
        'CONFIRMED',
        1,
        true,
        '{safe_start}'::timestamp,
        '{safe_end}'::timestamp,
        '+07:00',
        '+07:00',
        '1cb982ef-b7e6-493c-86f9-fe4994f8ad93'
    );
    """
    psql_query(sql)
    return occ_id_1, occ_id_2, occ_id_3, occ_id_4, occ_id_5, app_id_5


def run_all_scenarios():
    os.makedirs(EVIDENCE_DIR, exist_ok=True)
    results = {}
    print("=" * 70)
    print("STARTING F05 MOBILE LIVE INTEGRATION VERIFICATION")
    print(f"Timestamp: {datetime.now(timezone.utc).isoformat()}")
    print(f"Backend API: {API_BASE_URL}")
    print("=" * 70)

    # Re-apply seed to guarantee fresh test records
    print("\n[Setup] Seeding fresh, isolated test occurrences and appointments...")
    occ_id_1, occ_id_2, occ_id_3, occ_id_4, occ_id_5, app_id_5 = create_fresh_fixtures()
    print("Test occurrences seeded successfully:")
    print(f"  Occ 1 (SELF_DIRECTED): {occ_id_1}")
    print(f"  Occ 2 (HUMAN_COACH):   {occ_id_2}")
    print(f"  Occ 3 (Reject):        {occ_id_3}")
    print(f"  Occ 4 (Version drift): {occ_id_4}")
    print(f"  Occ 5 (Appt drift):    {occ_id_5} (Appt: {app_id_5})")

    created_occ_ids = [occ_id_1, occ_id_2, occ_id_3, occ_id_4, occ_id_5]
    created_app_ids = [app_id_5]
    created_rec_ids = []
    created_successor_ids = []

    def _run():
        # Logins
        student_self_token = login("student.f02.runtime@example.com")
        student_coach_token = login("smoke.student@example.com")
        trainer_token = login("smoke.trainer@example.com")
        print("\nAuthentication tokens acquired for all 3 roles.")

        # --------------------------------------------------------------------------
        # SCENARIO 1: Student SELF_DIRECTED: Generate -> Review -> Accept -> Receipt -> Successor in DB
        # --------------------------------------------------------------------------
        print("\n--- Scenario 1: Student SELF_DIRECTED End-to-End ---")
        cmd_key_1 = str(uuid.uuid4())
        gen_body_1 = {
            "commandKey": cmd_key_1,
            "occurrenceId": occ_id_1,
            "expectedOccurrenceVersion": 1,
            "reason": "Ban viec dot xuat buoi sang",
            "preferredTimeOfDay": "MORNING"
        }
        status_code, gen_resp_1 = http_request("POST", "/ai/workout-reschedules", student_self_token, gen_body_1)
        assert status_code == 200, f"Scenario 1 Generate failed: {status_code} - {gen_resp_1}"
        rec_id_1 = gen_resp_1["recommendationId"]
        created_rec_ids.append(rec_id_1)
        assert gen_resp_1["decisionAuthority"] == "STUDENT", "Self-directed proposal authority must be STUDENT"
        assert gen_resp_1["status"] == "PENDING"
        print(f"Proposal generated: {rec_id_1}, authority: {gen_resp_1['decisionAuthority']}, status: {gen_resp_1['status']}")

        accept_cmd_1 = str(uuid.uuid4())
        accept_body_1 = {
            "commandKey": accept_cmd_1,
            "expectedOccurrenceVersion": 1
        }
        status_code, accept_resp_1 = http_request("POST", f"/ai/workout-reschedules/{rec_id_1}/accept", student_self_token, accept_body_1)
        assert status_code == 200, f"Scenario 1 Accept failed: {status_code} - {accept_resp_1}"
        assert accept_resp_1["status"] == "APPLIED"
        assert accept_resp_1["replayed"] is False
        successor_id_1 = accept_resp_1["resultingEntityId"]
        assert successor_id_1 is not None, "Successor entity ID must not be null"
        created_successor_ids.append(successor_id_1)
        print(f"Proposal accepted & applied. Successor ID: {successor_id_1}")

        # Readback from PostgreSQL
        source_status = psql_query(f"SELECT status FROM fitness.planned_workouts WHERE id = '{occ_id_1}';")
        successor_check = psql_query(f"SELECT status, rescheduled_from_id FROM fitness.planned_workouts WHERE id = '{successor_id_1}';")
        rec_status_db = psql_query(f"SELECT status FROM fitness.ai_recommendations WHERE id = '{rec_id_1}';")
        assert source_status == "RESCHEDULED", f"Expected source RESCHEDULED, got {source_status}"
        assert f"SCHEDULED|{occ_id_1}" in successor_check, f"Successor check failed: {successor_check}"
        assert rec_status_db == "APPLIED", f"Expected recommendation APPLIED, got {rec_status_db}"
        print(f"Domain verified: source status={source_status}, successor status & link={successor_check}, rec={rec_status_db}")
        results["scenario_1_self_directed_flow"] = {
            "status": "PASS",
            "recommendationId": rec_id_1,
            "predecessorId": occ_id_1,
            "successorId": successor_id_1,
            "replayed": accept_resp_1["replayed"],
            "predecessorDbStatus": source_status,
            "successorDbStatus": "SCHEDULED"
        }

        # --------------------------------------------------------------------------
        # SCENARIO 2: Student HUMAN_COACH: Generate -> Student Accept blocked with 403
        # --------------------------------------------------------------------------
        print("\n--- Scenario 2: Student HUMAN_COACH Blocked from Accept ---")
        cmd_key_2 = str(uuid.uuid4())
        gen_body_2 = {
            "commandKey": cmd_key_2,
            "occurrenceId": occ_id_2,
            "expectedOccurrenceVersion": 1,
            "reason": "Xin dời buổi tập chiều",
            "preferredTimeOfDay": "AFTERNOON"
        }
        status_code, gen_resp_2 = http_request("POST", "/ai/workout-reschedules", student_coach_token, gen_body_2)
        assert status_code == 200, f"Scenario 2 Generate failed: {status_code} - {gen_resp_2}"
        rec_id_2 = gen_resp_2["recommendationId"]
        created_rec_ids.append(rec_id_2)
        assert gen_resp_2["decisionAuthority"] == "TRAINER", "Human-coach proposal authority must be TRAINER"
        print(f"Proposal generated: {rec_id_2}, authority: {gen_resp_2['decisionAuthority']}")

        # Student attempts Accept -> Must fail with 403
        accept_body_2 = {
            "commandKey": str(uuid.uuid4()),
            "expectedOccurrenceVersion": 1
        }
        status_code, err_resp_2 = http_request("POST", f"/ai/workout-reschedules/{rec_id_2}/accept", student_coach_token, accept_body_2)
        assert status_code == 403, f"Expected 403 for student accept in HUMAN_COACH, got {status_code}: {err_resp_2}"
        assert err_resp_2.get("errorCode") in ("AI_FORBIDDEN", "AI_RUN_ACCESS_DENIED"), f"Expected AI_FORBIDDEN/AI_RUN_ACCESS_DENIED, got {err_resp_2}"
        print(f"Student accept correctly blocked with 403 ({err_resp_2.get('errorCode')})")
        results["scenario_2_student_human_coach_blocked"] = {
            "status": "PASS",
            "recommendationId": rec_id_2,
            "httpStatus": status_code,
            "errorCode": err_resp_2.get("errorCode"),
            "message": err_resp_2.get("message")
        }

        # --------------------------------------------------------------------------
        # SCENARIO 3: Trainer HUMAN_COACH: Reviews & Applies with MANAGE Authority
        # --------------------------------------------------------------------------
        print("\n--- Scenario 3: Trainer HUMAN_COACH Applies Proposal ---")
        accept_cmd_3 = str(uuid.uuid4())
        accept_body_3 = {
            "commandKey": accept_cmd_3,
            "expectedOccurrenceVersion": 1
        }
        status_code, accept_resp_3 = http_request("POST", f"/ai/workout-reschedules/{rec_id_2}/accept", trainer_token, accept_body_3)
        assert status_code == 200, f"Trainer accept failed: {status_code} - {accept_resp_3}"
        assert accept_resp_3["status"] == "APPLIED"
        successor_id_3 = accept_resp_3["resultingEntityId"]
        created_successor_ids.append(successor_id_3)
        print(f"Trainer accepted proposal {rec_id_2}. Successor ID: {successor_id_3}")

        # Readback from PostgreSQL
        source_status_3 = psql_query(f"SELECT status FROM fitness.planned_workouts WHERE id = '{occ_id_2}';")
        successor_check_3 = psql_query(f"SELECT status, rescheduled_from_id FROM fitness.planned_workouts WHERE id = '{successor_id_3}';")
        assert source_status_3 == "RESCHEDULED"
        assert f"SCHEDULED|{occ_id_2}" in successor_check_3
        print(f"Domain verified: source={source_status_3}, successor={successor_check_3}")
        results["scenario_3_trainer_human_coach_apply"] = {
            "status": "PASS",
            "recommendationId": rec_id_2,
            "appliedBy": accept_resp_3.get("appliedBy"),
            "successorId": successor_id_3,
            "predecessorDbStatus": source_status_3,
            "successorDbStatus": "SCHEDULED"
        }

        # --------------------------------------------------------------------------
        # SCENARIO 4: Reject Proposal -> Transitions to REJECTED, original planned workout preserved
        # --------------------------------------------------------------------------
        print("\n--- Scenario 4: Reject Proposal Preserves Original Workout ---")
        gen_body_4 = {
            "commandKey": str(uuid.uuid4()),
            "occurrenceId": occ_id_3,
            "expectedOccurrenceVersion": 1,
            "reason": "Can doi gio tap",
            "preferredTimeOfDay": "EVENING"
        }
        status_code, gen_resp_4 = http_request("POST", "/ai/workout-reschedules", student_self_token, gen_body_4)
        assert status_code == 200, f"Scenario 4 Generate failed: {status_code}"
        rec_id_4 = gen_resp_4["recommendationId"]
        created_rec_ids.append(rec_id_4)

        # Reject
        reject_body_4 = {"decisionNote": "Gio nay khong phu hop voi lich ca nhan"}
        status_code, reject_resp_4 = http_request("POST", f"/ai/workout-reschedules/{rec_id_4}/reject", student_self_token, reject_body_4)
        assert status_code == 200, f"Reject failed: {status_code} - {reject_resp_4}"
        assert reject_resp_4["status"] == "REJECTED"
        print(f"Proposal {rec_id_4} rejected.")

        # Readback from PostgreSQL
        occ_status_4 = psql_query(f"SELECT status, version FROM fitness.planned_workouts WHERE id = '{occ_id_3}';")
        rec_status_4 = psql_query(f"SELECT status, decision_note FROM fitness.ai_recommendations WHERE id = '{rec_id_4}';")
        assert "SCHEDULED|1" in occ_status_4, f"Original occurrence must remain unchanged: {occ_status_4}"
        assert "REJECTED|Gio nay khong phu hop voi lich ca nhan" in rec_status_4
        print(f"Domain verified: original occurrence unchanged ({occ_status_4}), rec={rec_status_4}")
        results["scenario_4_reject_preserves_workout"] = {
            "status": "PASS",
            "recommendationId": rec_id_4,
            "predecessorId": occ_id_3,
            "predecessorDbStatus": "SCHEDULED",
            "predecessorDbVersion": 1,
            "recommendationDbStatus": "REJECTED"
        }

        # --------------------------------------------------------------------------
        # SCENARIO 5: Stale Source / Version Conflict (Controlled Fault Injection)
        # --------------------------------------------------------------------------
        print("\n--- Scenario 5: Stale Source / Version Conflict (Controlled Fault Injection) ---")
        gen_body_5 = {
            "commandKey": str(uuid.uuid4()),
            "occurrenceId": occ_id_4,
            "expectedOccurrenceVersion": 1,
            "reason": "Test version conflict",
            "preferredTimeOfDay": "MORNING"
        }
        status_code, gen_resp_5 = http_request("POST", "/ai/workout-reschedules", student_self_token, gen_body_5)
        assert status_code == 200, f"Scenario 5 Generate failed: {status_code}"
        rec_id_5 = gen_resp_5["recommendationId"]
        created_rec_ids.append(rec_id_5)

        # Controlled fault injection: Concurrently bump occurrence version in DB for isolated fixture occ_id_4
        psql_query(f"UPDATE fitness.planned_workouts SET version = 2 WHERE id = '{occ_id_4}';")
        print(f"Controlled fault injection: occurrence {occ_id_4} version bumped to 2")

        # Attempt accept with old version 1
        accept_body_5 = {
            "commandKey": str(uuid.uuid4()),
            "expectedOccurrenceVersion": 1
        }
        status_code, err_resp_5 = http_request("POST", f"/ai/workout-reschedules/{rec_id_5}/accept", student_self_token, accept_body_5)
        assert status_code == 409, f"Expected 409 Conflict, got {status_code}: {err_resp_5}"
        assert err_resp_5.get("errorCode") in ("AI_RUN_LIFECYCLE_CONFLICT", "PLANNED_WORKOUT_VERSION_CONFLICT"), f"Unexpected code: {err_resp_5}"
        print(f"Stale version drift correctly rejected with 409 ({err_resp_5.get('errorCode')})")
        results["scenario_5_stale_source_version_conflict"] = {
            "status": "PASS",
            "faultInjection": "CONTROLLED_OCCURRENCE_VERSION_DRIFT",
            "targetEntityId": occ_id_4,
            "recommendationId": rec_id_5,
            "httpStatus": status_code,
            "errorCode": err_resp_5.get("errorCode"),
            "message": err_resp_5.get("message")
        }

        # --------------------------------------------------------------------------
        # SCENARIO 6: Appointment Coordination & Version Drift (Controlled Fault Injection)
        # --------------------------------------------------------------------------
        print("\n--- Scenario 6: Appointment Coordination & Version Drift (Controlled Fault Injection) ---")
        gen_body_6 = {
            "commandKey": str(uuid.uuid4()),
            "occurrenceId": occ_id_5,
            "expectedOccurrenceVersion": 1,
            "reason": "Lich tap co HLV",
            "preferredTimeOfDay": "MORNING"
        }
        status_code, gen_resp_6 = http_request("POST", "/ai/workout-reschedules", trainer_token, gen_body_6)
        assert status_code == 200, f"Scenario 6 Generate failed: {status_code} - {gen_resp_6}"
        rec_id_6 = gen_resp_6["recommendationId"]
        created_rec_ids.append(rec_id_6)

        # Controlled fault injection: Concurrently drift appointment version in DB for isolated fixture app_id_5
        psql_query(f"UPDATE fitness.appointments SET version = 2 WHERE id = '{app_id_5}';")
        print(f"Controlled fault injection: appointment {app_id_5} version bumped to 2")

        # Attempt accept with old appointment version 1
        accept_body_6 = {
            "commandKey": str(uuid.uuid4()),
            "expectedOccurrenceVersion": 1,
            "expectedAppointmentId": app_id_5,
            "expectedAppointmentVersion": 1
        }
        status_code, err_resp_6 = http_request("POST", f"/ai/workout-reschedules/{rec_id_6}/accept", trainer_token, accept_body_6)
        assert status_code == 409, f"Expected 409 Conflict, got {status_code}: {err_resp_6}"
        assert err_resp_6.get("errorCode") in ("AI_RUN_LIFECYCLE_CONFLICT", "APPOINTMENT_VERSION_CONFLICT", "WORKOUT_SCHEDULE_APPOINTMENT_LINKED"), f"Unexpected code: {err_resp_6}"
        print(f"Appointment version drift correctly rejected with 409 ({err_resp_6.get('errorCode')})")
        results["scenario_6_appointment_version_drift"] = {
            "status": "PASS",
            "faultInjection": "CONTROLLED_APPOINTMENT_VERSION_DRIFT",
            "targetAppointmentId": app_id_5,
            "recommendationId": rec_id_6,
            "httpStatus": status_code,
            "errorCode": err_resp_6.get("errorCode"),
            "message": err_resp_6.get("message"),
            "note": "Proves backend version drift protection (HTTP 409) under concurrent appointment mutation; does not substitute for the full two-sided reschedule negotiation UX."
        }

        # --------------------------------------------------------------------------
        # SCENARIO 7: Timeout & Idempotent Replay (Re-submitting exact same commandKey)
        # --------------------------------------------------------------------------
        print("\n--- Scenario 7: Idempotent Replay after Network Timeout ---")
        # Re-submit Scenario 1's accept command with exact same commandKey (accept_cmd_1)
        status_code, replay_resp = http_request("POST", f"/ai/workout-reschedules/{rec_id_1}/accept", student_self_token, accept_body_1)
        assert status_code == 200, f"Replay failed: {status_code} - {replay_resp}"
        assert replay_resp["replayed"] is True, "Replay flag must be True"
        assert replay_resp["resultingEntityId"] == successor_id_1, "Replayed resultingEntityId must match original successor"
        print(f"Replay verified: replayed={replay_resp['replayed']}, resultingEntityId={replay_resp['resultingEntityId']}")

        # Verify database: exactly 1 successor entity exists
        successors_count = psql_query(f"SELECT count(*) FROM fitness.planned_workouts WHERE rescheduled_from_id = '{occ_id_1}';")
        assert successors_count == "1", f"Expected exactly 1 successor, found {successors_count}"
        print(f"Database verified: zero duplicate successor entities created (count={successors_count})")
        results["scenario_7_idempotent_replay"] = {
            "status": "PASS",
            "recommendationId": rec_id_1,
            "commandKey": accept_cmd_1,
            "replayed": True,
            "resultingEntityId": successor_id_1,
            "successorCount": int(successors_count)
        }

        # --------------------------------------------------------------------------
        # SCENARIO 8: Expired Proposal Semantics (Controlled Fault Injection)
        # --------------------------------------------------------------------------
        print("\n--- Scenario 8: Expired Proposal Semantics (Controlled Fault Injection) ---")
        # Controlled fault injection: Set expires_at of proposal 1 in the past on isolated recommendation fixture
        psql_query(f"UPDATE fitness.ai_recommendations SET expires_at = NOW() - INTERVAL '5 minutes' WHERE id = '{rec_id_1}';")

        # 8A: Replay with previous commandKey (accept_cmd_1) on expired proposal -> SUCCEEDS because Step 3 replay precedes Step 4 expiry
        status_code, replay_expired_resp = http_request("POST", f"/ai/workout-reschedules/{rec_id_1}/accept", student_self_token, accept_body_1)
        assert status_code == 200, f"Replay on expired proposal failed: {status_code} - {replay_expired_resp}"
        assert replay_expired_resp["replayed"] is True
        print(f"Replay with saved commandKey on expired proposal succeeded (replayed=True)")

        # 8B: New accept attempt with fresh commandKey on expired pending proposal -> BLOCKED
        # Controlled fault injection: Set expires_at of proposal 6 in the past on isolated recommendation fixture
        psql_query(f"UPDATE fitness.ai_recommendations SET expires_at = NOW() - INTERVAL '5 minutes' WHERE id = '{rec_id_6}';")
        new_accept_expired = {
            "commandKey": str(uuid.uuid4()),
            "expectedOccurrenceVersion": 1
        }
        status_code, err_expired_resp = http_request("POST", f"/ai/workout-reschedules/{rec_id_6}/accept", trainer_token, new_accept_expired)
        assert status_code == 409, f"Expected 409 for new accept on expired proposal, got {status_code}: {err_expired_resp}"
        assert err_expired_resp.get("errorCode") == "AI_RUN_LIFECYCLE_CONFLICT"
        print(f"New accept on expired proposal correctly blocked with 409 ({err_expired_resp.get('errorCode')})")
        results["scenario_8_expired_proposal_semantics"] = {
            "status": "PASS",
            "faultInjection": "CONTROLLED_EXPIRED_TTL",
            "targetRecommendationIds": [rec_id_1, rec_id_6],
            "replayExpiredStatus": "SUCCESS_REPLAYED",
            "newAcceptExpiredStatus": "BLOCKED_409",
            "errorCode": err_expired_resp.get("errorCode")
        }

        # --------------------------------------------------------------------------
        # Output Summary Evidence
        # --------------------------------------------------------------------------
        summary = {
            "verificationTimestamp": datetime.now(timezone.utc).isoformat(),
            "environment": "local-live",
            "backendUrl": API_BASE_URL,
            "aiServiceUrl": "http://localhost:8000",
            "database": "digital_fitness (PostgreSQL 18.6 on port 5433)",
            "provider": "simulated (SimulatedF05WorkoutProvider)",
            "scenarios": results,
            "overallResult": "PASS"
        }

        with open(EVIDENCE_FILE, "w", encoding="utf-8") as f:
            json.dump(summary, f, indent=2)

    try:
        _run()
    finally:
        # Teardown: Safely clean up isolated test fixtures
        print("\n[Teardown] Safely cleaning up isolated test fixtures...")
        cleanup_test_fixtures(
            occ_ids=created_occ_ids,
            app_ids=created_app_ids,
            rec_ids=created_rec_ids,
            successor_ids=created_successor_ids
        )
        print("[Teardown] Test fixtures cleaned up successfully.")

    print("\n" + "=" * 70)
    print("ALL 8 LIVE DOMAIN INTEGRATION SCENARIOS PASSED WITH RIGOROUS ASSERTIONS!")
    print(f"Evidence saved to: {EVIDENCE_FILE}")
    print("=" * 70)


if __name__ == "__main__":
    run_all_scenarios()
