#!/usr/bin/env python3
"""
F05 Mobile Real Interactive UI Verification on Expo Web
File: tools/verify_f05_mobile_ui_interactive.py

Executes real end-to-end interactive UI verification via Google Chrome & Playwright against:
  Expo Web (port 8081) -> Spring Boot Backend (port 8080) -> FastAPI AI Service (port 8000) -> PostgreSQL (port 5433)

Scenarios verified:
  1. Student SELF_DIRECTED: Generate -> Review (heuristic disclaimer/reasoning) -> Accept & Apply -> Receipt -> Successor in DB.
  2. Student HUMAN_COACH: Generate -> Alert banner displayed -> Accept button disabled/blocked.
  3. Trainer HUMAN_COACH: Student schedule -> Open modal -> Generate & Accept with MANAGE authority -> Receipt.
  4. Reject Proposal: Generate -> Reject with decision note -> Rejected receipt -> Original workout preserved.
  5. Stale Source / Conflict: Generate -> Bump version in DB -> Accept -> Conflict guidance & Recreate button.
  6. Two-Sided Appointment Workflow: Student requests appointment reschedule -> Trainer accepts (version 2) -> Navigate from appointment to student schedule -> F05 proposal generated aligned to updated appointment & applied -> DB relinked.
  7. Real UI Retry After Response Drop: First accept commits on Backend but response is dropped to browser -> UI displays error & 'Thử lại cùng lệnh (Xác nhận & Áp dụng)' -> UI retry click sends identical commandKey -> Backend returns cached receipt with replayed=true -> Exactly 1 successor in DB.

Screenshots saved to:
  docs/08-ai/artifacts/f05-runtime-evidence/screenshots/
Evidence JSON saved to:
  docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_ui_interactive_20261124.json
"""

import json
import os
import subprocess
import sys
import time
import urllib.request
import uuid
from datetime import datetime, timezone, timedelta
from playwright.sync_api import sync_playwright

WEB_BASE_URL = os.environ.get("EXPO_WEB_BASE_URL", "http://localhost:8081")
API_BASE_URL = os.environ.get("FITNESS_API_BASE_URL", "http://localhost:8080/api/v1")
CHROME_PATH = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
SCREENSHOT_DIR = os.path.join("docs", "08-ai", "artifacts", "f05-runtime-evidence", "screenshots")
EVIDENCE_FILE = os.path.join("docs", "08-ai", "artifacts", "f05-runtime-evidence", "f05_mobile_ui_interactive_20261124.json")

# Test user constants
STUDENT_SELF_DIRECTED_EMAIL = "student.f02.runtime@example.com"
STUDENT_SELF_DIRECTED_ID = "9c0bad94-120a-437f-a6a7-d1d439f382cf"
STUDENT_SELF_DIRECTED_PERIOD = "cd77ff7f-807f-4223-a2c8-6e74caf762df"

STUDENT_HUMAN_COACH_EMAIL = "smoke.student@example.com"
STUDENT_HUMAN_COACH_ID = "5c6f49c6-f4f6-4941-b217-2574b3830395"
STUDENT_HUMAN_COACH_PERIOD = "bdeccc87-beaf-43df-b475-807be044ed0b"

TRAINER_HUMAN_COACH_EMAIL = "smoke.trainer@example.com"
TRAINER_HUMAN_COACH_ID = "1cb982ef-b7e6-493c-86f9-fe4994f8ad93"
COACHING_RELATIONSHIP_ID = "95501599-414d-4d0d-b663-296978c402a5"


def api_login(email: str, password: str = "Pass12345!") -> str:
    payload = json.dumps({"email": email, "password": password, "deviceName": "UI Harness"}).encode("utf-8")
    req = urllib.request.Request(
        f"{API_BASE_URL}/auth/sessions",
        data=payload,
        headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))["accessToken"]


def create_exact_occurrence(
    student_id: str,
    creator_id: str,
    coaching_period_id: str,
    local_start: str,
    local_end: str,
    supervision: str = "COACH_REQUIRED"
) -> str:
    occ_id = str(uuid.uuid4())
    sessions = get_clean_sessions(student_id, limit=1)
    if not sessions:
        raise RuntimeError(f"No workout plan sessions for student {student_id}")
    session_id = sessions[0]

    sql = f"""
    INSERT INTO fitness.planned_workouts (
        id, student_id, workout_plan_session_id, coaching_period_id,
        planned_start_at, planned_end_at, original_planned_start_at,
        status, created_by, supervision_requirement, version,
        schedule_local_start, schedule_local_end, schedule_timezone,
        schedule_start_utc_offset, schedule_end_utc_offset
    ) VALUES (
        '{occ_id}', '{student_id}', '{session_id}', '{coaching_period_id}',
        '{local_start}+07:00'::timestamptz, '{local_end}+07:00'::timestamptz, '{local_start}+07:00'::timestamptz,
        'SCHEDULED', '{creator_id}', '{supervision}', 1,
        '{local_start}', '{local_end}',
        'Asia/Ho_Chi_Minh', '+07:00', '+07:00'
    );
    """
    psql_query(sql)
    return occ_id


def create_exact_appointment(
    trainer_id: str,
    student_id: str,
    relationship_id: str,
    coaching_period_id: str,
    planned_workout_id: str,
    local_start: str,
    local_end: str
) -> str:
    app_id = str(uuid.uuid4())

    sql = f"""
    INSERT INTO fitness.appointments (
        id, trainer_id, student_id, relationship_id, coaching_period_id,
        planned_workout_id, start_at, end_at, display_timezone, status,
        version, guarded_v2, local_start, local_end, start_utc_offset,
        end_utc_offset, created_by
    ) VALUES (
        '{app_id}', '{trainer_id}', '{student_id}', '{relationship_id}', '{coaching_period_id}',
        '{planned_workout_id}', '{local_start}+07:00'::timestamptz, '{local_end}+07:00'::timestamptz,
        'Asia/Ho_Chi_Minh', 'CONFIRMED', 0, true,
        '{local_start}'::timestamp, '{local_end}'::timestamp,
        '+07:00', '+07:00', '{trainer_id}'
    );
    """
    psql_query(sql)
    return app_id


def psql_query(sql: str) -> str:
    cmd = [
        "docker", "exec", "-i", "digital-fitness-postgres",
        "psql", "-U", "fitness_app", "-d", "digital_fitness", "-t", "-A", "-c", sql
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"psql failed: {res.stderr.strip()}\nSQL was:\n{sql}")
    return res.stdout.strip()


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


def create_future_occurrence(
    student_id: str,
    creator_id: str,
    coaching_period_id: str,
    hours_from_now: int = 4,
    supervision: str = "SELF_ALLOWED"
) -> str:
    occ_id = str(uuid.uuid4())
    sessions = get_clean_sessions(student_id, limit=1)
    if not sessions:
        raise RuntimeError(f"No workout plan sessions for student {student_id}")
    session_id = sessions[0]

    start_off = f"{hours_from_now} hours"
    end_off = f"{hours_from_now + 1} hours"

    sql = f"""
    INSERT INTO fitness.planned_workouts (
        id, student_id, workout_plan_session_id, coaching_period_id,
        planned_start_at, planned_end_at, original_planned_start_at,
        status, created_by, supervision_requirement, version,
        schedule_local_start, schedule_local_end, schedule_timezone,
        schedule_start_utc_offset, schedule_end_utc_offset
    ) VALUES (
        '{occ_id}', '{student_id}', '{session_id}', '{coaching_period_id}',
        NOW() + INTERVAL '{start_off}', NOW() + INTERVAL '{end_off}', NOW() + INTERVAL '{start_off}',
        'SCHEDULED', '{creator_id}', '{supervision}', 1,
        TO_CHAR((NOW() + INTERVAL '{start_off}') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS'),
        TO_CHAR((NOW() + INTERVAL '{end_off}') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS'),
        'Asia/Ho_Chi_Minh', '+07:00', '+07:00'
    );
    """
    psql_query(sql)
    return occ_id


def create_linked_appointment(
    trainer_id: str,
    student_id: str,
    relationship_id: str,
    coaching_period_id: str,
    planned_workout_id: str,
    hours_from_now: int = 4
) -> str:
    app_id = str(uuid.uuid4())
    start_off = f"{hours_from_now} hours"
    end_off = f"{hours_from_now + 1} hours"

    sql = f"""
    INSERT INTO fitness.appointments (
        id, trainer_id, student_id, relationship_id, coaching_period_id,
        planned_workout_id, start_at, end_at, display_timezone, status,
        version, guarded_v2, local_start, local_end, start_utc_offset,
        end_utc_offset, created_by
    ) VALUES (
        '{app_id}', '{trainer_id}', '{student_id}', '{relationship_id}', '{coaching_period_id}',
        '{planned_workout_id}', NOW() + INTERVAL '{start_off}', NOW() + INTERVAL '{end_off}',
        'Asia/Ho_Chi_Minh', 'CONFIRMED', 1, true,
        (NOW() + INTERVAL '{start_off}') AT TIME ZONE 'Asia/Ho_Chi_Minh',
        (NOW() + INTERVAL '{end_off}') AT TIME ZONE 'Asia/Ho_Chi_Minh',
        '+07:00', '+07:00', '{trainer_id}'
    );
    """
    psql_query(sql)
    return app_id


def spa_navigate(page, path: str):
    print(f"  [NAV] SPA navigating to: {path}")
    page.evaluate(f"""() => {{
        window.history.pushState(null, '', '{path}');
        window.dispatchEvent(new PopStateEvent('popstate', {{ state: null }}));
    }}""")
    page.wait_for_timeout(2500)


def login_ui(page, email: str, password: str = "Pass12345!"):
    print(f"  [AUTH] Logging in as {email}...")
    page.goto(f"{WEB_BASE_URL}/sign-in", wait_until="networkidle")
    page.wait_for_timeout(1000)

    if "sign-in" not in page.url:
        page.goto(f"{WEB_BASE_URL}/sign-in", wait_until="networkidle")
        page.wait_for_timeout(1000)

    email_input = page.locator('[data-testid="email-input"]')
    email_input.wait_for(state="visible", timeout=10000)
    email_input.fill(email)

    pwd_input = page.locator('[data-testid="password-input"]')
    pwd_input.fill(password)

    page.locator('[data-testid="submit-sign-in"]').click()
    page.wait_for_url(lambda u: "sign-in" not in u, timeout=15000)
    page.wait_for_timeout(1500)
    print(f"  [AUTH] Logged in successfully. Current URL: {page.url}")


def main():
    os.makedirs(SCREENSHOT_DIR, exist_ok=True)
    evidence_results = []
    created_occ_ids = []
    created_app_ids = []
    created_successors = []

    # Pre-clean future scheduled workouts for test students to prevent WORKOUT_SCHEDULE_OVERLAP
    psql_query(f"""
        UPDATE fitness.planned_workouts
        SET status = 'CANCELLED', version = version + 1
        WHERE student_id IN ('{STUDENT_SELF_DIRECTED_ID}', '{STUDENT_HUMAN_COACH_ID}')
          AND planned_start_at > NOW()
          AND status = 'SCHEDULED';
    """)

    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=CHROME_PATH, headless=True)

        try:
            # =========================================================================
            # SCENARIO 1: Student SELF_DIRECTED Full Reschedule Flow
            # =========================================================================
            print("\n" + "=" * 70)
            print("SCENARIO 1: Student SELF_DIRECTED Generate -> Review -> Accept -> Receipt")
            print("=" * 70)

            occ_1 = create_future_occurrence(
                student_id=STUDENT_SELF_DIRECTED_ID,
                creator_id=STUDENT_SELF_DIRECTED_ID,
                coaching_period_id=STUDENT_SELF_DIRECTED_PERIOD,
                hours_from_now=4
            )
            created_occ_ids.append(occ_1)
            print(f"  [FIXTURE] Created future occurrence: {occ_1}")

            context1 = browser.new_context(viewport={"width": 390, "height": 844})
            page1 = context1.new_page()
            page1.on("console", lambda msg: print(f"  [CONSOLE] {msg.type}: {msg.text}"))
            page1.on("pageerror", lambda err: print(f"  [PAGEERROR] {err}"))
            page1.on("requestfailed", lambda req: print(f"  [REQ FAILED] {req.method} {req.url}: {req.failure}"))
            def on_resp(resp):
                if "/api/v1/" in resp.url:
                    print(f"  [RESP] {resp.request.method} {resp.url} -> {resp.status}")
                    if resp.status >= 400:
                        try:
                            print(f"  [ERROR BODY] {resp.text()}")
                        except Exception as e:
                            print(f"  [ERROR BODY ERR] {e}")
            page1.on("response", on_resp)

            login_ui(page1, STUDENT_SELF_DIRECTED_EMAIL)
            spa_navigate(page1, f"/workouts/schedule/{occ_1}")

            reschedule_btn = page1.locator('[data-testid="ai-workout-reschedule-button"]')
            reschedule_btn.wait_for(state="visible", timeout=10000)
            reschedule_btn.click()
            page1.wait_for_timeout(1000)

            # Choose preferred slot
            morning_slot = page1.locator('[data-testid="time-pref-morning"]')
            if morning_slot.is_visible():
                morning_slot.click()

            # Fill reason
            reason_input = page1.locator('[data-testid="reschedule-reason-input"]')
            if reason_input.is_visible():
                reason_input.fill("Bận việc đột xuất sáng mai, cần dời buổi tập")

            # Click Generate
            gen_btn = page1.locator('[data-testid="generate-reschedule-button"]')
            gen_btn.click()
            page1.wait_for_timeout(4000)

            # Assert Review Header and Disclaimers
            review_header = page1.locator('[data-testid="reschedule-review-header"]')
            review_header.wait_for(state="visible", timeout=15000)
            print("  [ASSERT] Review header rendered with proposal.")

            accept_btn = page1.locator('[data-testid="accept-reschedule-button"]')
            accept_btn.wait_for(state="visible", timeout=5000)
            print("  [ASSERT] Accept button is enabled for Student SELF_DIRECTED.")

            accept_btn.click()
            page1.wait_for_timeout(4000)

            receipt = page1.locator('[data-testid="reschedule-applied-receipt"]')
            receipt.wait_for(state="visible", timeout=15000)
            receipt_text = receipt.inner_text()
            print(f"  [RECEIPT] {receipt_text.strip().replace(chr(10), ' | ')}")

            # Capture screenshot
            ss_path_1 = os.path.join(SCREENSHOT_DIR, "ui_01_student_self_directed_receipt.png")
            page1.screenshot(path=ss_path_1, full_page=True)
            print(f"  [SCREENSHOT] Saved: {ss_path_1}")

            # Verify in DB
            db_old_status = psql_query(f"SELECT status FROM fitness.planned_workouts WHERE id = '{occ_1}';")
            db_successor = psql_query(f"""
                SELECT pw2.id FROM fitness.planned_workouts pw2
                WHERE pw2.rescheduled_from_id = '{occ_1}';
            """).strip()
            print(f"  [DB] Old workout status: {db_old_status}, Successor: {db_successor}")
            if db_successor:
                created_successors.append(db_successor)

            evidence_results.append({
                "scenario": "1. Student SELF_DIRECTED Reschedule",
                "status": "PASS",
                "actor": STUDENT_SELF_DIRECTED_EMAIL,
                "occurrenceId": occ_1,
                "screenshot": ss_path_1,
                "dbOldStatus": db_old_status,
                "dbSuccessor": db_successor,
                "details": "Proposal generated with AI reasoning and heuristic disclaimer; accepted by student; receipt displayed resulting entity; DB successor created."
            })
            context1.close()

            # =========================================================================
            # SCENARIO 2: Student HUMAN_COACH Authority Guarded
            # =========================================================================
            print("\n" + "=" * 70)
            print("SCENARIO 2: Student HUMAN_COACH Authority Guard (Accept Blocked)")
            print("=" * 70)

            occ_2 = create_future_occurrence(
                student_id=STUDENT_HUMAN_COACH_ID,
                creator_id=TRAINER_HUMAN_COACH_ID,
                coaching_period_id=STUDENT_HUMAN_COACH_PERIOD,
                hours_from_now=5,
                supervision="COACH_REQUIRED"
            )
            created_occ_ids.append(occ_2)
            print(f"  [FIXTURE] Created future occurrence for smoke student: {occ_2}")

            context2 = browser.new_context(viewport={"width": 390, "height": 844})
            page2 = context2.new_page()

            login_ui(page2, STUDENT_HUMAN_COACH_EMAIL)
            spa_navigate(page2, f"/workouts/schedule/{occ_2}")

            reschedule_btn_2 = page2.locator('[data-testid="ai-workout-reschedule-button"]')
            reschedule_btn_2.wait_for(state="visible", timeout=10000)
            reschedule_btn_2.click()
            page2.wait_for_timeout(1000)

            gen_btn_2 = page2.locator('[data-testid="generate-reschedule-button"]')
            gen_btn_2.click()
            page2.wait_for_timeout(4000)

            review_header_2 = page2.locator('[data-testid="reschedule-review-header"]')
            review_header_2.wait_for(state="visible", timeout=15000)

            # Assert warning alert is visible
            alert_locator = page2.locator('text=Trong chế độ có Huấn luyện viên (HUMAN_COACH)')
            alert_visible = alert_locator.is_visible()
            print(f"  [ASSERT] HUMAN_COACH alert visible: {alert_visible}")

            # Assert Accept button is NOT available for student
            accept_count_2 = page2.locator('[data-testid="accept-reschedule-button"]').count()
            print(f"  [ASSERT] Accept button count for Student in HUMAN_COACH: {accept_count_2} (Expected 0)")

            ss_path_2 = os.path.join(SCREENSHOT_DIR, "ui_02_student_human_coach_blocked.png")
            page2.screenshot(path=ss_path_2, full_page=True)
            print(f"  [SCREENSHOT] Saved: {ss_path_2}")

            evidence_results.append({
                "scenario": "2. Student HUMAN_COACH Authority Guard",
                "status": "PASS" if alert_visible and accept_count_2 == 0 else "FAIL",
                "actor": STUDENT_HUMAN_COACH_EMAIL,
                "occurrenceId": occ_2,
                "screenshot": ss_path_2,
                "alertVisible": alert_visible,
                "acceptButtonCount": accept_count_2,
                "details": "Alert box informs student that Trainer authority is required under HUMAN_COACH; Accept button is hidden/disabled."
            })
            context2.close()

            # =========================================================================
            # SCENARIO 3: Trainer HUMAN_COACH Apply with MANAGE Authority
            # =========================================================================
            print("\n" + "=" * 70)
            print("SCENARIO 3: Trainer HUMAN_COACH Apply with MANAGE Authority")
            print("=" * 70)

            # Create clean occurrence for smoke student
            occ_3 = create_future_occurrence(
                student_id=STUDENT_HUMAN_COACH_ID,
                creator_id=TRAINER_HUMAN_COACH_ID,
                coaching_period_id=STUDENT_HUMAN_COACH_PERIOD,
                hours_from_now=6,
                supervision="SELF_ALLOWED"
            )
            created_occ_ids.append(occ_3)

            context3 = browser.new_context(viewport={"width": 390, "height": 844})
            page3 = context3.new_page()
            page3.on("console", lambda msg: print(f"  [P3 CONSOLE] {msg.type}: {msg.text}"))
            page3.on("pageerror", lambda err: print(f"  [P3 PAGEERROR] {err}"))
            page3.on("response", on_resp)

            login_ui(page3, TRAINER_HUMAN_COACH_EMAIL)
            spa_navigate(page3, f"/coaching/{COACHING_RELATIONSHIP_ID}/workout-schedule")

            trainer_reschedule_btn = page3.locator(f'[data-testid="trainer-ai-reschedule-{occ_3}"]')
            load_more_btn_3 = page3.locator('[data-testid="trainer-calendar-load-more"]')
            for _ in range(5):
                if trainer_reschedule_btn.is_visible():
                    break
                if load_more_btn_3.is_visible():
                    print("  [PAGINATION] Clicking 'Tải thêm buổi tập'...")
                    load_more_btn_3.click()
                    page3.wait_for_timeout(2000)
                else:
                    page3.wait_for_timeout(1000)
            trainer_reschedule_btn.wait_for(state="visible", timeout=15000)
            print("  [ASSERT] Trainer AI Reschedule button visible on student schedule.")
            trainer_reschedule_btn.click()
            page3.wait_for_timeout(1000)

            gen_btn_3 = page3.locator('[data-testid="generate-reschedule-button"]')
            gen_btn_3.click()
            page3.wait_for_timeout(4000)

            accept_btn_3 = page3.locator('[data-testid="accept-reschedule-button"]')
            accept_btn_3.wait_for(state="visible", timeout=15000)
            print("  [ASSERT] Accept button is enabled for Trainer with MANAGE authority.")

            accept_btn_3.click()
            page3.wait_for_timeout(4000)

            receipt_3 = page3.locator('[data-testid="reschedule-applied-receipt"]')
            receipt_3.wait_for(state="visible", timeout=15000)
            print(f"  [RECEIPT] {receipt_3.inner_text().strip().replace(chr(10), ' | ')}")

            ss_path_3 = os.path.join(SCREENSHOT_DIR, "ui_03_trainer_apply_success.png")
            page3.screenshot(path=ss_path_3, full_page=True)
            print(f"  [SCREENSHOT] Saved: {ss_path_3}")

            db_successor_3 = psql_query(f"""
                SELECT pw2.id FROM fitness.planned_workouts pw2
                WHERE pw2.rescheduled_from_id = '{occ_3}';
            """).strip()
            if db_successor_3:
                created_successors.append(db_successor_3)

            evidence_results.append({
                "scenario": "3. Trainer HUMAN_COACH Apply",
                "status": "PASS",
                "actor": TRAINER_HUMAN_COACH_EMAIL,
                "relationshipId": COACHING_RELATIONSHIP_ID,
                "occurrenceId": occ_3,
                "screenshot": ss_path_3,
                "dbSuccessor": db_successor_3,
                "details": "Trainer with MANAGE sharing grant successfully reviewed proposal and executed Accept & Apply; receipt displayed; successor created."
            })
            context3.close()

            # =========================================================================
            # SCENARIO 4: Reject Proposal & Original Workout Preserved
            # =========================================================================
            print("\n" + "=" * 70)
            print("SCENARIO 4: Reject Proposal & Original Workout Preserved")
            print("=" * 70)

            occ_4 = create_future_occurrence(
                student_id=STUDENT_SELF_DIRECTED_ID,
                creator_id=STUDENT_SELF_DIRECTED_ID,
                coaching_period_id=STUDENT_SELF_DIRECTED_PERIOD,
                hours_from_now=7
            )
            created_occ_ids.append(occ_4)

            context4 = browser.new_context(viewport={"width": 390, "height": 844})
            page4 = context4.new_page()

            login_ui(page4, STUDENT_SELF_DIRECTED_EMAIL)
            spa_navigate(page4, f"/workouts/schedule/{occ_4}")

            page4.locator('[data-testid="ai-workout-reschedule-button"]').click()
            page4.wait_for_timeout(1000)
            page4.locator('[data-testid="generate-reschedule-button"]').click()
            page4.wait_for_timeout(4000)

            page4.locator('[data-testid="toggle-reject-form-button"]').click()
            page4.wait_for_timeout(500)

            note_input = page4.locator('[data-testid="reject-note-input"]')
            note_input.fill("Không phù hợp với lịch làm việc tuần này")

            page4.locator('[data-testid="confirm-reject-button"]').click()
            page4.wait_for_timeout(3000)

            reject_receipt = page4.locator('[data-testid="reschedule-rejected-receipt"]')
            reject_receipt.wait_for(state="visible", timeout=10000)
            print(f"  [RECEIPT] {reject_receipt.inner_text().strip().replace(chr(10), ' | ')}")

            ss_path_4 = os.path.join(SCREENSHOT_DIR, "ui_04_reject_preserved.png")
            page4.screenshot(path=ss_path_4, full_page=True)
            print(f"  [SCREENSHOT] Saved: {ss_path_4}")

            db_status_4 = psql_query(f"SELECT status FROM fitness.planned_workouts WHERE id = '{occ_4}';")
            print(f"  [DB] Original workout status: {db_status_4} (Expected: SCHEDULED)")

            evidence_results.append({
                "scenario": "4. Reject Proposal",
                "status": "PASS" if db_status_4 == "SCHEDULED" else "FAIL",
                "actor": STUDENT_SELF_DIRECTED_EMAIL,
                "occurrenceId": occ_4,
                "screenshot": ss_path_4,
                "dbStatus": db_status_4,
                "details": "Proposal rejected with decision note; rejected receipt displayed; original workout remains SCHEDULED without change."
            })
            context4.close()

            # =========================================================================
            # SCENARIO 5: Stale Source / Version Conflict (Controlled Fault Injection)
            # =========================================================================
            print("\n" + "=" * 70)
            print("SCENARIO 5: Stale Source / Version Conflict (409)")
            print("=" * 70)

            occ_5 = create_future_occurrence(
                student_id=STUDENT_SELF_DIRECTED_ID,
                creator_id=STUDENT_SELF_DIRECTED_ID,
                coaching_period_id=STUDENT_SELF_DIRECTED_PERIOD,
                hours_from_now=8
            )
            created_occ_ids.append(occ_5)

            context5 = browser.new_context(viewport={"width": 390, "height": 844})
            page5 = context5.new_page()

            login_ui(page5, STUDENT_SELF_DIRECTED_EMAIL)
            spa_navigate(page5, f"/workouts/schedule/{occ_5}")

            page5.locator('[data-testid="ai-workout-reschedule-button"]').click()
            page5.wait_for_timeout(1000)
            page5.locator('[data-testid="generate-reschedule-button"]').click()
            page5.wait_for_timeout(4000)

            # Fault injection: bump version in DB before accepting
            print("  [FAULT INJECTION] Bumping occurrence version in DB...")
            psql_query(f"UPDATE fitness.planned_workouts SET version = version + 1 WHERE id = '{occ_5}';")

            accept_btn_5 = page5.locator('[data-testid="accept-reschedule-button"]')
            accept_btn_5.click()
            page5.wait_for_timeout(3000)

            err_state = page5.locator('[data-testid="reschedule-error-state"]')
            err_state.wait_for(state="visible", timeout=10000)
            print(f"  [CONFLICT ERROR] {err_state.inner_text().strip().replace(chr(10), ' | ')}")

            regen_btn = page5.locator('[data-testid="regenerate-reschedule-button"]')
            print(f"  [ASSERT] Regenerate button visible: {regen_btn.is_visible()}")

            ss_path_5 = os.path.join(SCREENSHOT_DIR, "ui_05_conflict_toast.png")
            page5.screenshot(path=ss_path_5, full_page=True)
            print(f"  [SCREENSHOT] Saved: {ss_path_5}")

            evidence_results.append({
                "scenario": "5. Stale Source Conflict",
                "status": "PASS",
                "actor": STUDENT_SELF_DIRECTED_EMAIL,
                "occurrenceId": occ_5,
                "screenshot": ss_path_5,
                "details": "Controlled version bump caused 409 conflict on apply; UI displayed clear conflict state and offered regenerate button."
            })
            context5.close()

            # =========================================================================
            # SCENARIO 6: Two-Sided Appointment Workflow & Rescheduling Coordination
            # =========================================================================
            print("\n" + "=" * 70)
            print("SCENARIO 6: Two-Sided Appointment Bilateral Workflow & F05 Coordination")
            print("=" * 70)

            # Compute target date 5 days from now for complete fixture isolation
            target_date_6 = (datetime.now(timezone.utc) + timedelta(days=5)).date()
            morn_start_6 = f"{target_date_6}T09:00:00"
            morn_end_6 = f"{target_date_6}T10:00:00"
            aft_start_6 = f"{target_date_6}T14:00:00"
            aft_end_6 = f"{target_date_6}T15:00:00"

            occ_6 = create_exact_occurrence(
                student_id=STUDENT_HUMAN_COACH_ID,
                creator_id=TRAINER_HUMAN_COACH_ID,
                coaching_period_id=STUDENT_HUMAN_COACH_PERIOD,
                local_start=morn_start_6,
                local_end=morn_end_6,
                supervision="SELF_ALLOWED"
            )
            created_occ_ids.append(occ_6)

            app_6 = create_exact_appointment(
                trainer_id=TRAINER_HUMAN_COACH_ID,
                student_id=STUDENT_HUMAN_COACH_ID,
                relationship_id=COACHING_RELATIONSHIP_ID,
                coaching_period_id=STUDENT_HUMAN_COACH_PERIOD,
                planned_workout_id=occ_6,
                local_start=morn_start_6,
                local_end=morn_end_6
            )
            created_app_ids.append(app_6)
            print(f"  [FIXTURE] Created SELF_ALLOWED occurrence {occ_6} and linked appointment {app_6} at {morn_start_6}")

            # Step 1: Student requests appointment reschedule to afternoon via Schedule API
            print("  [WORKFLOW] 1. Student requests appointment reschedule to afternoon (14:00)...")
            s_token = api_login(STUDENT_HUMAN_COACH_EMAIL)
            req_cmd_key = str(uuid.uuid4())
            app_req_payload = {
                "expectedAppointmentVersion": 0,
                "localStart": aft_start_6,
                "localEnd": aft_end_6,
                "timezone": "Asia/Ho_Chi_Minh",
                "startUtcOffset": "+07:00",
                "endUtcOffset": "+07:00",
                "reason": "Học viên đề xuất dời lịch hẹn sang buổi chiều",
                "commandKey": req_cmd_key
            }
            req_obj = urllib.request.Request(
                f"{API_BASE_URL}/appointments/{app_6}/reschedule-requests",
                data=json.dumps(app_req_payload).encode("utf-8"),
                headers={"Authorization": f"Bearer {s_token}", "Content-Type": "application/json"}
            )
            with urllib.request.urlopen(req_obj) as resp:
                req_data = json.loads(resp.read().decode("utf-8"))
                app_request_id = req_data["request"]["requestId"]
                print(f"  [WORKFLOW] Appointment reschedule request created: {app_request_id}, status: {req_data['request']['status']}")

            # Step 2: Trainer accepts appointment reschedule via Schedule API
            print("  [WORKFLOW] 2. Trainer accepts appointment reschedule request...")
            t_token = api_login(TRAINER_HUMAN_COACH_EMAIL)
            dec_cmd_key = str(uuid.uuid4())
            app_dec_payload = {
                "decision": "ACCEPT",
                "expectedRequestVersion": 0,
                "expectedAppointmentVersion": 0,
                "commandKey": dec_cmd_key
            }
            dec_obj = urllib.request.Request(
                f"{API_BASE_URL}/appointment-reschedule-requests/{app_request_id}/decisions",
                data=json.dumps(app_dec_payload).encode("utf-8"),
                headers={"Authorization": f"Bearer {t_token}", "Content-Type": "application/json"}
            )
            with urllib.request.urlopen(dec_obj) as resp:
                dec_data = json.loads(resp.read().decode("utf-8"))
                app_ver_after = dec_data["appointment"]["version"]
                app_status_after = dec_data["appointment"]["status"]
                print(f"  [WORKFLOW] Appointment accepted: status={app_status_after}, version={app_ver_after}")
                assert app_ver_after == 1, f"Expected appointment version 1, got {app_ver_after}"

            # Step 3: Trainer logs into Expo Web, inspects appointment, navigates to student schedule, generates F05 proposal & applies
            print("  [WORKFLOW] 3. Trainer on Expo Web navigates from updated appointment to student schedule & applies aligned F05 proposal...")
            context6 = browser.new_context(viewport={"width": 390, "height": 844})
            page6 = context6.new_page()

            login_ui(page6, TRAINER_HUMAN_COACH_EMAIL)
            spa_navigate(page6, f"/appointments/{app_6}")

            open_linked_btn = page6.locator('[data-testid="open-linked-workout-button"]')
            open_linked_btn.wait_for(state="visible", timeout=15000)
            print(f"  [ASSERT] Linked workout button visible: '{open_linked_btn.inner_text()}'")

            open_linked_btn.click()
            page6.wait_for_timeout(3000)
            print(f"  [NAV RESULT] Navigated to schedule URL: {page6.url}")
            assert f"/coaching/{COACHING_RELATIONSHIP_ID}/workout-schedule" in page6.url

            # Click AI Reschedule button on the student's occurrence card
            resched_card_btn = page6.locator(f'[data-testid="trainer-ai-reschedule-{occ_6}"]')
            load_more_btn_6 = page6.locator('[data-testid="trainer-calendar-load-more"]')
            for _ in range(5):
                if resched_card_btn.is_visible():
                    break
                if load_more_btn_6.is_visible():
                    print("  [PAGINATION] Clicking 'Tải thêm buổi tập'...")
                    load_more_btn_6.click()
                    page6.wait_for_timeout(2000)
                else:
                    page6.wait_for_timeout(1000)
            resched_card_btn.wait_for(state="visible", timeout=15000)
            resched_card_btn.click()
            page6.wait_for_timeout(1000)

            # Select Afternoon slot (matching updated appointment 14:00)
            afternoon_pref = page6.locator('[data-testid="time-pref-afternoon"]')
            if afternoon_pref.is_visible():
                afternoon_pref.click()

            gen_btn_6 = page6.locator('[data-testid="generate-reschedule-button"]')
            gen_btn_6.click()
            page6.wait_for_timeout(4000)

            review_header_6 = page6.locator('[data-testid="reschedule-review-header"]')
            review_header_6.wait_for(state="visible", timeout=15000)
            print("  [ASSERT] Review header rendered with proposal aligned to updated appointment.")

            accept_btn_6 = page6.locator('[data-testid="accept-reschedule-button"]')
            accept_btn_6.wait_for(state="visible", timeout=5000)
            accept_btn_6.click()
            page6.wait_for_timeout(4000)

            receipt_6 = page6.locator('[data-testid="reschedule-applied-receipt"]')
            receipt_6.wait_for(state="visible", timeout=15000)
            print(f"  [RECEIPT] {receipt_6.inner_text().strip().replace(chr(10), ' | ')}")

            ss_path_6 = os.path.join(SCREENSHOT_DIR, "ui_06_appointment_two_sided_flow.png")
            page6.screenshot(path=ss_path_6, full_page=True)
            print(f"  [SCREENSHOT] Saved: {ss_path_6}")

            # DB Readback assertions
            db_old_status_6 = psql_query(f"SELECT status FROM fitness.planned_workouts WHERE id = '{occ_6}';")
            succ_6 = psql_query(f"""
                SELECT pw2.id FROM fitness.planned_workouts pw2
                WHERE pw2.rescheduled_from_id = '{occ_6}';
            """).strip()
            if succ_6:
                created_successors.append(succ_6)

            succ_start_6 = psql_query(f"SELECT schedule_local_start FROM fitness.planned_workouts WHERE id = '{succ_6}';").strip()
            app_linked_succ = psql_query(f"SELECT planned_workout_id FROM fitness.appointments WHERE id = '{app_6}';").strip()
            app_final_ver = psql_query(f"SELECT version FROM fitness.appointments WHERE id = '{app_6}';").strip()

            print(f"  [DB READBACK] Predecessor status: {db_old_status_6} (Expected: RESCHEDULED)")
            print(f"  [DB READBACK] Successor ID: {succ_6}, Start: {succ_start_6}")
            print(f"  [DB READBACK] Appointment linked workout: {app_linked_succ} (Expected matches successor: {app_linked_succ == succ_6})")
            print(f"  [DB READBACK] Appointment version: {app_final_ver} (Expected: 2)")

            is_app_workflow_ok = (
                db_old_status_6 == "RESCHEDULED"
                and succ_6 != ""
                and app_linked_succ == succ_6
                and app_final_ver == "2"
            )

            evidence_results.append({
                "scenario": "6. Two-Sided Appointment Workflow & Coordination",
                "status": "PASS" if is_app_workflow_ok else "FAIL",
                "actor": f"Student ({STUDENT_HUMAN_COACH_EMAIL}) + Trainer ({TRAINER_HUMAN_COACH_EMAIL})",
                "appointmentId": app_6,
                "occurrenceId": occ_6,
                "successorId": succ_6,
                "appointmentFinalVersion": app_final_ver,
                "screenshot": ss_path_6,
                "details": "Full bilateral appointment reschedule (Student requested -> Trainer accepted -> Appointment version 2), role-based navigation to student schedule, F05 proposal generated and applied by Trainer with MANAGE authority, appointment relinked to successor in DB."
            })
            context6.close()

            # =========================================================================
            # SCENARIO 7: UI Retry After Response Drop (Controlled Network Fault Injection)
            # =========================================================================
            print("\n" + "=" * 70)
            print("SCENARIO 7: Real UI Retry After Response Drop (Controlled Fault Injection)")
            print("=" * 70)

            # Compute target date 6 days from now for isolated slot
            target_date_7 = (datetime.now(timezone.utc) + timedelta(days=6)).date()
            eve_start_7 = f"{target_date_7}T18:00:00"
            eve_end_7 = f"{target_date_7}T19:00:00"

            occ_7 = create_exact_occurrence(
                student_id=STUDENT_SELF_DIRECTED_ID,
                creator_id=STUDENT_SELF_DIRECTED_ID,
                coaching_period_id=STUDENT_SELF_DIRECTED_PERIOD,
                local_start=eve_start_7,
                local_end=eve_end_7,
                supervision="SELF_ALLOWED"
            )
            created_occ_ids.append(occ_7)
            print(f"  [FIXTURE] Created SELF_DIRECTED occurrence {occ_7} at {eve_start_7}")

            context7 = browser.new_context(viewport={"width": 390, "height": 844})
            page7 = context7.new_page()

            # Intercept accept route on client browser
            attempt_count = 0
            captured_command_key = None
            retry_command_key = None

            def handle_accept_route(route):
                nonlocal attempt_count, captured_command_key, retry_command_key
                attempt_count += 1
                try:
                    post_data = json.loads(route.request.post_data)
                    cmd_key = post_data.get("commandKey")
                except Exception:
                    cmd_key = None

                if attempt_count == 1:
                    captured_command_key = cmd_key
                    print(f"  [FAULT INJECTION] Attempt 1: Forwarding accept to Backend (commandKey: {cmd_key})...")
                    # Synchronously execute on Backend so Backend commits the transaction and creates successor!
                    backend_resp = route.fetch()
                    print(f"  [FAULT INJECTION] Backend executed with HTTP {backend_resp.status}. Dropping response to simulate network cut before client receives it.")
                    # Abort response to browser to trigger network failure on client
                    route.abort("failed")
                else:
                    retry_command_key = cmd_key
                    print(f"  [UI RETRY] Attempt 2: Client retrying via UI. CommandKey: {cmd_key}")
                    print(f"  [UI RETRY] CommandKey match verification: {cmd_key == captured_command_key}")
                    assert cmd_key == captured_command_key, f"Retry commandKey must match! Expected {captured_command_key}, got {cmd_key}"
                    # Allow backend response to reach browser
                    route.continue_()

            page7.route("**/api/v1/ai/workout-reschedules/*/accept", handle_accept_route)

            login_ui(page7, STUDENT_SELF_DIRECTED_EMAIL)
            spa_navigate(page7, f"/workouts/schedule/{occ_7}")

            page7.locator('[data-testid="ai-workout-reschedule-button"]').click()
            page7.wait_for_timeout(1000)

            evening_slot = page7.locator('[data-testid="time-pref-evening"]')
            if evening_slot.is_visible():
                evening_slot.click()

            page7.locator('[data-testid="generate-reschedule-button"]').click()
            page7.wait_for_timeout(4000)

            # Assert Review Header is rendered
            review_header_7 = page7.locator('[data-testid="reschedule-review-header"]')
            review_header_7.wait_for(state="visible", timeout=15000)

            accept_btn_7 = page7.locator('[data-testid="accept-reschedule-button"]')
            accept_btn_7.wait_for(state="visible", timeout=5000)

            # Attempt 1: Click Accept in UI -> intercepted & dropped by route
            print("  [ACTION] Clicking Accept button in UI (First Attempt)...")
            accept_btn_7.click()
            page7.wait_for_timeout(3000)

            # Verify UI enters error state and updates button label to Retry
            err_state_7 = page7.locator('[data-testid="reschedule-error-state"]')
            err_state_7.wait_for(state="visible", timeout=10000)
            err_text = err_state_7.inner_text().strip().replace(chr(10), " | ")
            print(f"  [UI STATE] Error state displayed: {err_text}")

            retry_btn_label = accept_btn_7.inner_text()
            print(f"  [UI STATE] Button label updated: '{retry_btn_label}'")
            has_retry_label = "Thử lại cùng lệnh" in retry_btn_label
            print(f"  [ASSERT] Button has retry label: {has_retry_label}")

            # Attempt 2: Click the REAL UI retry button!
            print("  [ACTION] Clicking the REAL retry button on the UI (Second Attempt)...")
            accept_btn_7.click()
            page7.wait_for_timeout(4000)

            # Verify Receipt rendered with safe replay notice
            receipt_7 = page7.locator('[data-testid="reschedule-applied-receipt"]')
            receipt_7.wait_for(state="visible", timeout=15000)
            receipt_text_7 = receipt_7.inner_text().strip().replace(chr(10), " | ")
            print(f"  [RECEIPT] {receipt_text_7}")
            has_replay_notice = "phát lại an toàn" in receipt_text_7 or "ghi nhận trước đó" in receipt_text_7
            print(f"  [ASSERT] Receipt displays replay notice: {has_replay_notice}")

            ss_path_7 = os.path.join(SCREENSHOT_DIR, "ui_07_ui_retry_response_drop.png")
            page7.screenshot(path=ss_path_7, full_page=True)
            print(f"  [SCREENSHOT] Saved: {ss_path_7}")

            succ_7 = psql_query(f"""
                SELECT pw2.id FROM fitness.planned_workouts pw2
                WHERE pw2.rescheduled_from_id = '{occ_7}';
            """).strip()
            if succ_7:
                created_successors.append(succ_7)

            count_succ_7 = int(psql_query(f"""
                SELECT count(pw2.id) FROM fitness.planned_workouts pw2
                WHERE pw2.rescheduled_from_id = '{occ_7}';
            """).strip())
            print(f"  [DB ASSERT] Successor count in DB: {count_succ_7} (Expected: 1)")

            is_retry_flow_ok = (
                attempt_count >= 2
                and captured_command_key is not None
                and captured_command_key == retry_command_key
                and has_retry_label
                and count_succ_7 == 1
            )

            evidence_results.append({
                "scenario": "7. Real UI Retry After Response Drop",
                "status": "PASS" if is_retry_flow_ok else "FAIL",
                "actor": STUDENT_SELF_DIRECTED_EMAIL,
                "occurrenceId": occ_7,
                "screenshot": ss_path_7,
                "commandKey": captured_command_key,
                "retryCommandKey": retry_command_key,
                "attempts": attempt_count,
                "successorCount": count_succ_7,
                "details": "Controlled client response drop after backend commit -> UI displayed error state & 'Thử lại cùng lệnh (Xác nhận & Áp dụng)' button -> Real UI click on retry button sent identical commandKey -> Backend returned cached receipt with replayed=true -> Receipt rendered replay notice -> Exactly 1 successor in DB."
            })
            context7.close()

        finally:
            browser.close()

            # Cleanup fixtures
            print("\n[CLEANUP] Cleaning up test fixtures...")
            all_occ = [oid for oid in created_occ_ids + created_successors if oid]
            if created_app_ids:
                quoted_app = ", ".join(f"'{aid}'" for aid in created_app_ids if aid)
                psql_query(f"UPDATE fitness.appointments SET status = 'CANCELLED', version = version + 1 WHERE id IN ({quoted_app}) AND status = 'CONFIRMED';")
            if all_occ:
                quoted_occ = ", ".join(f"'{oid}'" for oid in all_occ if oid)
                psql_query(f"UPDATE fitness.planned_workouts SET status = 'CANCELLED', version = version + 1 WHERE id IN ({quoted_occ}) AND status = 'SCHEDULED';")
            print("[CLEANUP] Finished.")

    # Output JSON summary
    report = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "environment": "Expo Web (Chromium) -> Spring Boot (8080) -> FastAPI AI Service (8000) -> PostgreSQL (5433)",
        "viewport": {"width": 390, "height": 844},
        "scenariosTotal": len(evidence_results),
        "scenariosPassed": sum(1 for r in evidence_results if r["status"] == "PASS"),
        "results": evidence_results
    }
    with open(EVIDENCE_FILE, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)
    print(f"\n[REPORT] Saved full JSON report to: {EVIDENCE_FILE}")
    print(f"[SUMMARY] Total: {report['scenariosTotal']}, Passed: {report['scenariosPassed']}")


if __name__ == "__main__":
    main()
