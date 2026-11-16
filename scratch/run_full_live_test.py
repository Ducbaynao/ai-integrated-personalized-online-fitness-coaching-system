import os
import sys
import json
import time
import base64
import hmac
import hashlib
import uuid
import urllib.request
import urllib.error
import subprocess

JWT_SECRET = os.environ.get("JWT_SECRET") or os.environ.get("AUTH_JWT_SECRET")
if not JWT_SECRET:
    print("ERROR: Environment variable JWT_SECRET (or AUTH_JWT_SECRET) is required but not set.", file=sys.stderr)
    print("Example: export JWT_SECRET=\"<placeholder_jwt_secret>\"", file=sys.stderr)
    sys.exit(1)

API_BASE = os.environ.get("API_BASE", "http://localhost:8080/api/v1")

def base64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode('utf-8').rstrip('=')

def generate_jwt(user_id: str, email: str, roles: list) -> str:
    header = {"alg": "HS256", "typ": "JWT"}
    now_ms = int(time.time() * 1000)
    now_sec = now_ms // 1000
    payload = {
        "iss": "fitness-coaching-backend",
        "sub": user_id,
        "email": email,
        "roles": roles,
        "iat": now_sec,
        "exp": now_sec + 7200,
        "jti": str(uuid.uuid4()),
        "iat_epoch_ms": now_ms
    }
    h_b64 = base64url(json.dumps(header, separators=(',', ':')).encode('utf-8'))
    p_b64 = base64url(json.dumps(payload, separators=(',', ':')).encode('utf-8'))
    sign_input = f"{h_b64}.{p_b64}"
    sig = hmac.new(JWT_SECRET.encode('utf-8'), sign_input.encode('utf-8'), hashlib.sha256).digest()
    sig_b64 = base64url(sig)
    return f"{sign_input}.{sig_b64}"

def query_db(sql: str) -> str:
    cmd = [
        "docker", "exec", "digital-fitness-postgres",
        "psql", "-U", "fitness_app", "-d", "digital_fitness", "-t", "-c", sql
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, check=True)
    return res.stdout.strip()

def http_req(endpoint: str, token: str, method: str = "GET", data: dict = None):
    url = f"{API_BASE}{endpoint}"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Accept": "application/json"
    }
    body = json.dumps(data).encode('utf-8') if data is not None else None
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            content = resp.read().decode('utf-8')
            return resp.status, json.loads(content) if content else {}
    except urllib.error.HTTPError as e:
        err_body = e.read().decode('utf-8')
        try:
            parsed = json.loads(err_body)
        except:
            parsed = {"raw": err_body}
        return e.code, parsed

TRAINER_ID = "1cb982ef-b7e6-493c-86f9-fe4994f8ad93"
TRAINER_EMAIL = "smoke.trainer@example.com"
STUDENT_HC_ID = "5c6f49c6-f4f6-4941-b217-2574b3830395"
STUDENT_HC_EMAIL = "smoke.student@example.com"
STUDENT_SD_ID = "9c0bad94-120a-437f-a6a7-d1d439f382cf"
STUDENT_SD_EMAIL = "student.f02.runtime@example.com"

trainer_token = generate_jwt(TRAINER_ID, TRAINER_EMAIL, ["ROLE_TRAINER", "TRAINER"])
student_hc_token = generate_jwt(STUDENT_HC_ID, STUDENT_HC_EMAIL, ["ROLE_STUDENT", "STUDENT"])
student_sd_token = generate_jwt(STUDENT_SD_ID, STUDENT_SD_EMAIL, ["ROLE_STUDENT", "STUDENT"])

OCCURRENCE_HC = "f3000000-0000-4000-8000-000000000002"
PRESCRIPTION_HC = "e590f09a-19cf-47bd-a2d6-1c815c1dc815"
OCCURRENCE_SD = "f3000000-0000-4000-8000-000000000001"
PRESCRIPTION_SD = "5143db2d-77d9-43e6-8034-4bb877c4f9f7"

print("--- 1. Trainer Generates Proposal on Occurrence HC ---")
gen_req = {
    "studentId": STUDENT_HC_ID,
    "commandKey": str(uuid.uuid4()),
    "scope": "ONE_MATERIALIZED_OCCURRENCE",
    "target": {
        "scope": "ONE_MATERIALIZED_OCCURRENCE",
        "plannedWorkoutId": OCCURRENCE_HC,
        "plannedSessionExerciseId": PRESCRIPTION_HC
    },
    "requestedChangeType": "LOAD_ADJUSTMENT",
    "userReason": "Fatigue from previous session",
    "equipmentAvailable": ["BARBELL"],
    "physicalLimitations": [],
    "allowLoadIncrease": False
}
st, gen_res = http_req("/ai/workout-adjustments", trainer_token, "POST", gen_req)
print(f"Generate response status: {st}")
assert st == 200, f"Generate failed: {gen_res}"
rec_id_hc = gen_res["recommendationId"]
print(f"Generated Rec ID: {rec_id_hc}, status: {gen_res['status']}, authority: {gen_res['decisionAuthority']}")

print("\n--- 2. Student HC tries to read private unshared draft (must be 403) ---")
st_read, read_res = http_req(f"/ai/workout-adjustments/{rec_id_hc}", student_hc_token, "GET")
print(f"Student HC read private draft status: {st_read}")
assert st_read == 403, f"Expected 403 but got {st_read}: {read_res}"

print("\n--- 3. Trainer Edits Proposal to v2 ---")
payload_v2 = dict(gen_res["structuredPayload"])
# Update target load from 20 to 17.5 KG
changes = list(payload_v2["changes"])
changes[0]["after"] = {
    "direction": "DECREASE",
    "targetLoad": 17.5,
    "loadUnit": "KG",
    "loadUnitId": 1,
    "deltaLoad": -2.5,
    "deltaPercent": -12.5
}
payload_v2["changes"] = changes

update_req = {
    "expectedRevision": 1,
    "title": "Trainer Load Reduction (v2)",
    "explanation": "Trainer decreased load by 2.5 KG for recovery.",
    "structuredPayload": payload_v2
}
st_up, up_res = http_req(f"/ai/workout-adjustments/{rec_id_hc}/draft", trainer_token, "PUT", update_req)
print(f"Update draft status: {st_up}, new revision: {up_res.get('revision')}")
assert st_up == 200, f"Update draft failed: {up_res}"
assert up_res["revision"] == 2
assert up_res["isCustomized"] is True
assert up_res.get("diff") is not None
print(f"Diff badge: {up_res['diff']}")

print("\n--- 4. Trainer Shares Draft v2 ---")
share_req = {
    "expectedRevision": 2
}
st_sh, sh_res = http_req(f"/ai/workout-adjustments/{rec_id_hc}/share", trainer_token, "POST", share_req)
print(f"Share draft status: {st_sh}")
assert st_sh == 200, f"Share draft failed: {sh_res}"
assert sh_res["sharedWithStudent"] is True
assert sh_res["sharedRevision"] == 2

print("\n--- 5. Student HC Reads Shared Snapshot v2 ---")
st_read2, read_res2 = http_req(f"/ai/workout-adjustments/{rec_id_hc}", student_hc_token, "GET")
print(f"Student HC read status: {st_read2}")
assert st_read2 == 200
assert read_res2["revision"] == 2
assert read_res2["title"] == "Trainer Load Reduction (v2)"
assert read_res2["decisionAuthority"] == "TRAINER", "Authority must be TRAINER"
print(f"Student view isolated: revision={read_res2['revision']}, authority={read_res2['decisionAuthority']}")

print("\n--- 6. Trainer Edits to v3 (Unshared Draft) ---")
payload_v3 = dict(payload_v2)
update_req_v3 = {
    "expectedRevision": 2,
    "title": "Trainer Private Edit v3 (Unshared)",
    "explanation": "Testing draft isolation: Student must still see v2.",
    "structuredPayload": payload_v3
}
st_up3, up_res3 = http_req(f"/ai/workout-adjustments/{rec_id_hc}/draft", trainer_token, "PUT", update_req_v3)
print(f"Update v3 status: {st_up3}, revision: {up_res3.get('revision')}")
assert st_up3 == 200
assert up_res3["revision"] == 3

print("\n--- 7. Student HC Reads Proposal (MUST STILL BE v2) ---")
st_read3, read_res3 = http_req(f"/ai/workout-adjustments/{rec_id_hc}", student_hc_token, "GET")
print(f"Student read status: {st_read3}, revision seen: {read_res3['revision']}, title: {read_res3['title']}")
assert read_res3["revision"] == 2, f"Student must see shared revision 2, but saw {read_res3['revision']}"
assert read_res3["title"] == "Trainer Load Reduction (v2)"

print("\n--- 8. Trainer Reshares v3 ---")
sh_req_v3 = {"expectedRevision": 3}
st_sh3, sh_res3 = http_req(f"/ai/workout-adjustments/{rec_id_hc}/share", trainer_token, "POST", sh_req_v3)
assert st_sh3 == 200
assert sh_res3["sharedRevision"] == 3

print("\n--- 9. Student HC Reads Reshared v3 ---")
st_read4, read_res4 = http_req(f"/ai/workout-adjustments/{rec_id_hc}", student_hc_token, "GET")
assert st_read4 == 200
assert read_res4["revision"] == 3
assert read_res4["title"] == "Trainer Private Edit v3 (Unshared)"
print("Shared view isolation & reshare verified 100%!")

print("\n--- 10. Trainer Accepts Proposal v3 ---")
curr_occ_ver = int(query_db(f"SELECT version FROM fitness.planned_workouts WHERE id = '{OCCURRENCE_HC}';"))
cmd_key_hc = str(uuid.uuid4())
acc_hc = {
    "expectedRevision": 3,
    "expectedTargetVersion": curr_occ_ver,
    "decisionNote": "Trainer accepts proposal v3.",
    "commandKey": cmd_key_hc
}
st_acc, res_acc = http_req(f"/ai/workout-adjustments/{rec_id_hc}/accept", trainer_token, "POST", acc_hc)
print(f"Trainer Accept status: {st_acc}, response: {res_acc}")
assert st_acc == 200
assert res_acc["status"] == "APPLIED"
assert res_acc["replayed"] is False

print("\n--- 11. Domain Persistence Verification ---")
db_adj = query_db(f"SELECT adjustment_type, resolution_state, typed_target_load, typed_load_unit_id FROM fitness.workout_session_adjustments WHERE planned_workout_id = '{OCCURRENCE_HC}' ORDER BY created_at DESC LIMIT 1;")
print(f"Database adjustment row: {db_adj}")
assert "LOAD" in db_adj
assert "TYPED" in db_adj
assert "17.5" in db_adj
assert "1" in db_adj

print("\n--- 12. Idempotent Replay Verification ---")
st_replay, res_replay = http_req(f"/ai/workout-adjustments/{rec_id_hc}/accept", trainer_token, "POST", acc_hc)
print(f"Replay status: {st_replay}, replayed: {res_replay.get('replayed')}")
assert st_replay == 200
assert res_replay["replayed"] is True

print("\n--- 13. Stale Lifecycle Conflict Verification ---")
acc_stale = dict(acc_hc)
acc_stale["commandKey"] = str(uuid.uuid4())
st_stale, res_stale = http_req(f"/ai/workout-adjustments/{rec_id_hc}/accept", trainer_token, "POST", acc_stale)
print(f"Conflict status: {st_stale}, code: {res_stale.get('errorCode')}")
assert st_stale == 409

print("\nALL BACKEND/DOMAIN INVARIANTS VERIFIED SUCCESSFULLY!")
