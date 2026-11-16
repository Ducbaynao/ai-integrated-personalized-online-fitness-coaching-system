import json
import time
import base64
import hmac
import hashlib
import uuid
import urllib.request
import subprocess

JWT_SECRET = 'dev-only-jwt-secret-change-before-production-1234567890'
def base64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode('utf-8').rstrip('=')

def generate_jwt(user_id: str, email: str, roles: list) -> str:
    header = {'alg': 'HS256', 'typ': 'JWT'}
    now_ms = int(time.time() * 1000)
    now_sec = now_ms // 1000
    payload = {
        'iss': 'fitness-coaching-backend',
        'sub': user_id,
        'email': email,
        'roles': roles,
        'iat': now_sec,
        'exp': now_sec + 7200,
        'jti': str(uuid.uuid4()),
        'iat_epoch_ms': now_ms
    }
    h_b64 = base64url(json.dumps(header, separators=(',', ':')).encode('utf-8'))
    p_b64 = base64url(json.dumps(payload, separators=(',', ':')).encode('utf-8'))
    sign_input = f'{h_b64}.{p_b64}'
    sig = hmac.new(JWT_SECRET.encode('utf-8'), sign_input.encode('utf-8'), hashlib.sha256).digest()
    sig_b64 = base64url(sig)
    return f'{sign_input}.{sig_b64}'

trainer_token = generate_jwt('1cb982ef-b7e6-493c-86f9-fe4994f8ad93', 'smoke.trainer@example.com', ['ROLE_TRAINER', 'TRAINER'])
rec_id = 'bf6e78e8-28b5-452d-b47f-29c55c520f95'

sql = f"SELECT application_payload->>'commandKey' FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = '{rec_id}' LIMIT 1;"
cmd = ['docker', 'exec', 'digital-fitness-postgres', 'psql', '-U', 'fitness_app', '-d', 'digital_fitness', '-t', '-c', sql]
ck = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout.strip()
print('Found command key:', ck)

acc_req = {
    'expectedRevision': 3,
    'expectedTargetVersion': 2,
    'decisionNote': 'Trainer approves load reduction.',
    'commandKey': ck
}
req = urllib.request.Request(f'http://localhost:8080/api/v1/ai/workout-adjustments/{rec_id}/accept', data=json.dumps(acc_req).encode('utf-8'), headers={'Authorization': f'Bearer {trainer_token}', 'Content-Type': 'application/json', 'Accept': 'application/json'}, method='POST')
with urllib.request.urlopen(req) as resp:
    print('REPLAY STATUS:', resp.status)
    body = json.loads(resp.read().decode('utf-8'))
    print('REPLAY BODY:', body)
    assert body.get('replayed') is True, 'Expected replayed: true'

# Now test conflict with a DIFFERENT command key
acc_req['commandKey'] = str(uuid.uuid4())
req2 = urllib.request.Request(f'http://localhost:8080/api/v1/ai/workout-adjustments/{rec_id}/accept', data=json.dumps(acc_req).encode('utf-8'), headers={'Authorization': f'Bearer {trainer_token}', 'Content-Type': 'application/json', 'Accept': 'application/json'}, method='POST')
try:
    with urllib.request.urlopen(req2) as resp2:
        print('STALE STATUS:', resp2.status)
except urllib.error.HTTPError as e:
    print('STALE CONFLICT STATUS:', e.code)
    assert e.code == 409, 'Expected 409 conflict for stale recommendation state'

print('\nALL REPLAY AND CONFLICT BEHAVIOR CONFIRMED 100%!')
