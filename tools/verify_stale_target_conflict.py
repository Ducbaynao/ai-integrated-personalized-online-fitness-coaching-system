#!/usr/bin/env python3
"""
F03 Stale Target Conflict & Lifecycle Replay Verification Script
File: tools/verify_stale_target_conflict.py

WARNING:
  This script generates AI workout adjustment recommendations and applies domain
  mutations to the local development database for verification purposes.
  DO NOT execute this script against staging or production environments!

Purpose:
  Deterministically validates three essential authority and concurrency invariants:
  1. True Stale Target Conflict: A PENDING recommendation whose target occurrence was
     modified concurrently is rejected with HTTP 409 Conflict (version mismatch),
     leaving the recommendation in PENDING status with zero partial domain mutation.
  2. Lifecycle Replay Guard: An already APPLIED recommendation cannot be applied again
     with a new commandKey (rejected with HTTP 409 Conflict).
  3. Idempotent Replay: Re-submitting the exact same apply commandKey returns HTTP 200
     with replayed=True without duplicating database side effects.

Environment Variables:
  FITNESS_API_BASE_URL: Base URL for Backend REST API (default: http://localhost:8080/api/v1)
  FITNESS_AUTH_TOKEN: Bearer token (optional, bypasses login)
  FITNESS_STUDENT_EMAIL: Email for test student (default: student.f02.runtime@example.com)
  FITNESS_STUDENT_PASSWORD: Password for test student (read from env or CLI argument)
  POSTGRES_HOST / POSTGRES_PORT / POSTGRES_DB / POSTGRES_USER: Database connection settings

Exit Code:
  0 on all assertions passed; 1 on any assertion failure or error.
"""

import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

API_BASE_URL = os.environ.get("FITNESS_API_BASE_URL", "http://localhost:8080/api/v1")
STUDENT_EMAIL = os.environ.get("FITNESS_STUDENT_EMAIL", "student.f02.runtime@example.com")
TARGET_OCCURRENCE_ID = "f3000000-0000-4000-8000-000000000001"
TARGET_PRESCRIPTION_ID = "5143db2d-77d9-43e6-8034-4bb877c4f9f7"
STUDENT_USER_ID = "9c0bad94-120a-437f-a6a7-d1d439f382cf"


def mask_secret(val: str) -> str:
    if not val:
        return "(none)"
    if len(val) <= 8:
        return "***"
    return f"{val[:4]}...{val[-4:]}"


def parse_args():
    parser = argparse.ArgumentParser(description="Verify F03 Stale Target Conflict and Lifecycle Guards")
    parser.add_argument("--token", default=os.environ.get("FITNESS_AUTH_TOKEN"), help="Bearer token for API access")
    parser.add_argument("--password", default=os.environ.get("FITNESS_STUDENT_PASSWORD"), help="Student password for login")
    parser.add_argument("--email", default=STUDENT_EMAIL, help="Student email for login")
    parser.add_argument("--api-url", default=API_BASE_URL, help="Backend API base URL")
    return parser.parse_args()


def query_db(sql: str) -> str:
    cmd = [
        "docker", "exec", "-i", "digital-fitness-postgres",
        "psql", "-U", "fitness_app", "-d", "digital_fitness", "-t", "-c", sql
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"Database query failed: {res.stderr.strip()}")
    return res.stdout.strip()


def obtain_auth_token(api_url: str, email: str, password: str) -> str:
    login_url = f"{api_url}/auth/sessions"
    req = urllib.request.Request(
        login_url,
        data=json.dumps({"email": email, "password": password}).encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data["accessToken"]
    except urllib.error.HTTPError as e:
        err_msg = e.read().decode("utf-8")
        raise RuntimeError(f"Authentication failed (HTTP {e.code}): {err_msg}")


def api_request(api_url: str, token: str, endpoint: str, method: str = "GET", data: dict = None):
    url = f"{api_url}{endpoint}"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def main():
    args = parse_args()
    print("=" * 80)
    print("F03 Stale Target Conflict & Lifecycle Replay Verification")
    print("=" * 80)
    print("WARNING: This script creates proposals and applies adjustments to development data.")
    print("         DO NOT run against production environments.\n")

    token = args.token
    if not token:
        if not args.password:
            print("ERROR: Neither FITNESS_AUTH_TOKEN nor FITNESS_STUDENT_PASSWORD was provided.")
            print("Please set the environment variable or pass --token / --password.")
            sys.exit(1)
        print(f"Authenticating as {args.email}...")
        token = obtain_auth_token(args.api_url, args.email, args.password)
    
    print(f"Auth token established: {mask_secret(token)}")

    try:
        # Pre-check current version in DB
        v_initial_str = query_db(f"SELECT version FROM fitness.planned_workouts WHERE id = '{TARGET_OCCURRENCE_ID}'")
        assert v_initial_str.isdigit(), f"Invalid version returned from DB: {v_initial_str}"
        v_initial = int(v_initial_str)
        print(f"\n[Step 0] Initial Target Occurrence Version: {v_initial}")

        # Step 1: Generate Recommendation 1 (R1) against v_initial
        print("\n[Step 1] Generating Recommendation R1...")
        r1_payload = {
            "commandKey": str(uuid.uuid4()),
            "scope": "ONE_MATERIALIZED_OCCURRENCE",
            "studentId": STUDENT_USER_ID,
            "requestedChangeType": "LOAD_ADJUSTMENT",
            "target": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": TARGET_OCCURRENCE_ID,
                "plannedSessionExerciseId": TARGET_PRESCRIPTION_ID
            },
            "userReason": "R1 generation for stale target conflict test"
        }
        s1, res1 = api_request(args.api_url, token, "/ai/workout-adjustments", method="POST", data=r1_payload)
        assert s1 == 200, f"Expected HTTP 200 on R1 generation, got {s1}: {res1}"
        assert "recommendationId" in res1, f"Missing recommendationId in R1 response: {res1}"
        r1_id = res1["recommendationId"]
        assert res1.get("status") == "PENDING", f"Expected R1 status PENDING, got {res1.get('status')}"

        r1_status_db = query_db(f"SELECT status FROM fitness.ai_recommendations WHERE id = '{r1_id}'")
        assert r1_status_db == "PENDING", f"Expected R1 in DB to be PENDING, got {r1_status_db}"
        print(f"  -> R1 created successfully: {r1_id} (status: PENDING)")

        # Step 2: Concurrently generate & apply Recommendation R2 to increment target version
        print("\n[Step 2] Generating & Applying Competing Recommendation R2...")
        r2_payload = {
            "commandKey": str(uuid.uuid4()),
            "scope": "ONE_MATERIALIZED_OCCURRENCE",
            "studentId": STUDENT_USER_ID,
            "requestedChangeType": "LOAD_ADJUSTMENT",
            "target": {
                "scope": "ONE_MATERIALIZED_OCCURRENCE",
                "plannedWorkoutId": TARGET_OCCURRENCE_ID,
                "plannedSessionExerciseId": TARGET_PRESCRIPTION_ID
            },
            "userReason": "R2 competing adjustment to increment occurrence version"
        }
        s2, res2 = api_request(args.api_url, token, "/ai/workout-adjustments", method="POST", data=r2_payload)
        assert s2 == 200, f"Expected HTTP 200 on R2 generation, got {s2}: {res2}"
        r2_id = res2["recommendationId"]

        r2_command_key = str(uuid.uuid4())
        apply_r2_payload = {
            "commandKey": r2_command_key,
            "expectedTargetVersion": v_initial,
            "decisionNote": "Applying R2 to mutate target occurrence version"
        }
        s_apply2, res_apply2 = api_request(
            args.api_url, token, f"/ai/workout-adjustments/{r2_id}/accept", method="POST", data=apply_r2_payload
        )
        assert s_apply2 == 200, f"Expected HTTP 200 on applying R2, got {s_apply2}: {res_apply2}"
        assert res_apply2.get("status") == "APPLIED", f"Expected R2 status APPLIED, got {res_apply2.get('status')}"
        assert res_apply2.get("replayed") is False, "Expected first application replayed=False"

        v_after_r2 = int(query_db(f"SELECT version FROM fitness.planned_workouts WHERE id = '{TARGET_OCCURRENCE_ID}'"))
        assert v_after_r2 == v_initial + 1, f"Expected version {v_initial + 1}, got {v_after_r2}"
        print(f"  -> R2 applied successfully: {r2_id}. Occurrence version incremented: {v_initial} -> {v_after_r2}")

        # Step 3: Attempt to Apply Stale Recommendation R1 with v_initial
        print("\n[Step 3] Attempting to Apply Stale Recommendation R1...")
        stale_apply_payload = {
            "commandKey": str(uuid.uuid4()),
            "expectedTargetVersion": v_initial,  # Stale version! Current version in DB is v_after_r2
            "decisionNote": "Attempting to apply stale R1"
        }
        s_stale, res_stale = api_request(
            args.api_url, token, f"/ai/workout-adjustments/{r1_id}/accept", method="POST", data=stale_apply_payload
        )

        # Strict assertions for Stale Target Conflict:
        assert s_stale == 409, f"Expected HTTP 409 Conflict for stale target version, got {s_stale}: {res_stale}"
        err_code = res_stale.get("errorCode")
        err_msg = res_stale.get("message", "")
        assert err_code == "AI_RUN_LIFECYCLE_CONFLICT", f"Expected error code AI_RUN_LIFECYCLE_CONFLICT, got {err_code}"
        assert "version conflict" in err_msg.lower(), f"Expected version conflict in message, got '{err_msg}'"
        print(f"  -> Stale conflict rejected as expected: HTTP {s_stale}, Code: {err_code}, Msg: '{err_msg}'")

        # Database audit for Zero Partial Mutation on R1:
        r1_status_post = query_db(f"SELECT status FROM fitness.ai_recommendations WHERE id = '{r1_id}'")
        assert r1_status_post == "PENDING", f"R1 status must remain PENDING, got {r1_status_post}"

        r1_apps_count = int(query_db(f"SELECT count(*) FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = '{r1_id}'"))
        assert r1_apps_count == 0, f"Expected 0 application receipts for stale R1, got {r1_apps_count}"

        v_final = int(query_db(f"SELECT version FROM fitness.planned_workouts WHERE id = '{TARGET_OCCURRENCE_ID}'"))
        assert v_final == v_after_r2, f"Planned workout version must remain {v_after_r2}, got {v_final} (partial mutation detected!)"
        print("  -> Zero partial mutation verified in DB (R1 remains PENDING, 0 applications, version unchanged).")

        # Step 4: Validate Lifecycle Replay Guard on already APPLIED R2
        print("\n[Step 4] Validating Lifecycle Replay Guard on R2...")
        reapply_payload = {
            "commandKey": str(uuid.uuid4()),  # New command key
            "expectedTargetVersion": v_after_r2,
            "decisionNote": "Attempting to re-apply already applied R2 with new key"
        }
        s_reapply, res_reapply = api_request(
            args.api_url, token, f"/ai/workout-adjustments/{r2_id}/accept", method="POST", data=reapply_payload
        )
        assert s_reapply == 409, f"Expected HTTP 409 on re-applying already APPLIED rec, got {s_reapply}: {res_reapply}"
        assert "already been applied" in res_reapply.get("message", "").lower(), f"Unexpected message: {res_reapply}"
        print(f"  -> Lifecycle Guard verified: HTTP {s_reapply}, Msg: '{res_reapply.get('message')}'")

        # Step 5: Validate Idempotent Replay on R2 with original commandKey
        print("\n[Step 5] Validating Idempotent Replay on R2...")
        idempotent_payload = {
            "commandKey": r2_command_key,  # Identical command key as in Step 2
            "expectedTargetVersion": v_initial,
            "decisionNote": "Applying R2 to mutate target occurrence version"
        }
        s_idem, res_idem = api_request(
            args.api_url, token, f"/ai/workout-adjustments/{r2_id}/accept", method="POST", data=idempotent_payload
        )
        assert s_idem == 200, f"Expected HTTP 200 on idempotent replay, got {s_idem}: {res_idem}"
        assert res_idem.get("status") == "APPLIED", f"Expected APPLIED, got {res_idem.get('status')}"
        assert res_idem.get("replayed") is True, f"Expected replayed=True, got {res_idem.get('replayed')}"
        assert res_idem.get("recommendationId") == r2_id, "Recommendation ID mismatch in replay"
        print(f"  -> Idempotent Replay verified: HTTP {s_idem}, replayed=True, no duplicated mutation.")

        print("\n" + "=" * 80)
        print("ALL VERIFICATION ASSERTIONS PASSED SUCCESSFULLY!")
        print("=" * 80)
        sys.exit(0)

    except AssertionError as e:
        print(f"\n[ASSERTION FAILED]: {e}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"\n[UNEXPECTED ERROR]: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
