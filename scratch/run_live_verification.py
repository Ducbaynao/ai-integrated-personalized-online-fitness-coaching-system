import os
import json
import time
import base64
import hmac
import hashlib
import uuid
import urllib.request
import urllib.error
import subprocess

JWT_SECRET = "dev-only-jwt-secret-change-before-production-1234567890"
API_BASE = "http://localhost:8080/api/v1"

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

# Define actors
TRAINER_ID = "1cb982ef-b7e6-493c-86f9-fe4994f8ad93"
TRAINER_EMAIL = "smoke.trainer@example.com"
STUDENT_HC_ID = "5c6f49c6-f4f6-4941-b217-2574b3830395"
STUDENT_HC_EMAIL = "smoke.student@example.com"
STUDENT_SD_ID = "9c0bad94-120a-437f-a6a7-d1d439f382cf"
STUDENT_SD_EMAIL = "student.f02.runtime@example.com"

trainer_token = generate_jwt(TRAINER_ID, TRAINER_EMAIL, ["ROLE_TRAINER", "TRAINER"])
student_hc_token = generate_jwt(STUDENT_HC_ID, STUDENT_HC_EMAIL, ["ROLE_STUDENT", "STUDENT"])
student_sd_token = generate_jwt(STUDENT_SD_ID, STUDENT_SD_EMAIL, ["ROLE_STUDENT", "STUDENT"])

print("Testing Trainer Auth:", http_req("/users/me", trainer_token)[0])
print("Testing Student HC Auth:", http_req("/users/me", student_hc_token)[0])
print("Testing Student SD Auth:", http_req("/users/me", student_sd_token)[0])
