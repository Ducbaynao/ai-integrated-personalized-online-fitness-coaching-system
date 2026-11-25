-- ==============================================================================
-- F05 Development Seed Fixtures (Safe & Idempotent)
-- File: tools/seed_f05_dev_fixtures.sql
-- ==============================================================================

-- 1. Ensure test users have known test password ('Pass12345!') and ACTIVE status
UPDATE fitness.users 
SET password_hash = '$2a$10$cld7dVAIRmrFqoEZBeN5m.JGCNaUYaMQ.Dax3WYr.9zto.f1uGdBC',
    status = 'ACTIVE',
    email_verified_at = COALESCE(email_verified_at, NOW())
WHERE email IN (
    'student.f02.runtime@example.com',
    'smoke.student@example.com',
    'smoke.trainer@example.com'
);

-- 2. Clean up previous test AI runs/recommendations for test occurrences
DELETE FROM fitness.ai_recommendation_applications 
WHERE ai_recommendation_id IN (
    SELECT id FROM fitness.ai_recommendations 
    WHERE target_entity_id IN (
        'f3000000-0000-4000-8000-000000000011',
        'f3000000-0000-4000-8000-000000000012',
        'f3000000-0000-4000-8000-000000000003',
        'f3000000-0000-4000-8000-000000000004',
        'f3000000-0000-4000-8000-000000000005'
    )
);

DELETE FROM fitness.ai_recommendations 
WHERE target_entity_id IN (
    'f3000000-0000-4000-8000-000000000011',
    'f3000000-0000-4000-8000-000000000012',
    'f3000000-0000-4000-8000-000000000003',
    'f3000000-0000-4000-8000-000000000004',
    'f3000000-0000-4000-8000-000000000005'
);

-- 3. Delete any previous test appointments
DELETE FROM fitness.appointments WHERE planned_workout_id IN (
    'f3000000-0000-4000-8000-000000000011',
    'f3000000-0000-4000-8000-000000000012',
    'f3000000-0000-4000-8000-000000000003',
    'f3000000-0000-4000-8000-000000000004',
    'f3000000-0000-4000-8000-000000000005'
) OR id = 'a5000000-0000-4000-8000-000000000005';

-- 4. Insert or ensure test occurrences exist with full schedule & session metadata
INSERT INTO fitness.planned_workouts (
    id, student_id, workout_plan_session_id, coaching_period_id, planned_start_at, planned_end_at,
    original_planned_start_at, status, created_by, supervision_requirement,
    version, schedule_local_start, schedule_local_end, schedule_timezone,
    schedule_start_utc_offset, schedule_end_utc_offset
) VALUES 
(
    'f3000000-0000-4000-8000-000000000011',
    '9c0bad94-120a-437f-a6a7-d1d439f382cf',
    '8910caf5-25ba-45d7-a174-53f32a048a35',
    'cd77ff7f-807f-4223-a2c8-6e74caf762df',
    NOW() - INTERVAL '3 hours',
    NOW() - INTERVAL '2 hours',
    NOW() - INTERVAL '3 hours',
    'SCHEDULED',
    '9c0bad94-120a-437f-a6a7-d1d439f382cf',
    'SELF_ALLOWED',
    1,
    TO_CHAR((NOW() - INTERVAL '3 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS'),
    TO_CHAR((NOW() - INTERVAL '2 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS'),
    'Asia/Ho_Chi_Minh',
    '+07:00',
    '+07:00'
),
(
    'f3000000-0000-4000-8000-000000000012',
    '5c6f49c6-f4f6-4941-b217-2574b3830395',
    '8839ebc2-7db7-45d7-a2fc-67705bc1631c',
    'bdeccc87-beaf-43df-b475-807be044ed0b',
    NOW() - INTERVAL '4 hours',
    NOW() - INTERVAL '3 hours',
    NOW() - INTERVAL '4 hours',
    'SCHEDULED',
    '1cb982ef-b7e6-493c-86f9-fe4994f8ad93',
    'SELF_ALLOWED',
    1,
    TO_CHAR((NOW() - INTERVAL '4 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS'),
    TO_CHAR((NOW() - INTERVAL '3 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS'),
    'Asia/Ho_Chi_Minh',
    '+07:00',
    '+07:00'
),
(
    'f3000000-0000-4000-8000-000000000003',
    '9c0bad94-120a-437f-a6a7-d1d439f382cf',
    '8910caf5-25ba-45d7-a174-53f32a048a35',
    'cd77ff7f-807f-4223-a2c8-6e74caf762df',
    NOW() - INTERVAL '5 hours',
    NOW() - INTERVAL '4 hours',
    NOW() - INTERVAL '5 hours',
    'SCHEDULED',
    '9c0bad94-120a-437f-a6a7-d1d439f382cf',
    'SELF_ALLOWED',
    1,
    TO_CHAR((NOW() - INTERVAL '5 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS'),
    TO_CHAR((NOW() - INTERVAL '4 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS'),
    'Asia/Ho_Chi_Minh',
    '+07:00',
    '+07:00'
),
(
    'f3000000-0000-4000-8000-000000000004',
    '9c0bad94-120a-437f-a6a7-d1d439f382cf',
    '8910caf5-25ba-45d7-a174-53f32a048a35',
    'cd77ff7f-807f-4223-a2c8-6e74caf762df',
    NOW() - INTERVAL '6 hours',
    NOW() - INTERVAL '5 hours',
    NOW() - INTERVAL '6 hours',
    'SCHEDULED',
    '9c0bad94-120a-437f-a6a7-d1d439f382cf',
    'SELF_ALLOWED',
    1,
    TO_CHAR((NOW() - INTERVAL '6 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS'),
    TO_CHAR((NOW() - INTERVAL '5 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS'),
    'Asia/Ho_Chi_Minh',
    '+07:00',
    '+07:00'
),
(
    'f3000000-0000-4000-8000-000000000005',
    '5c6f49c6-f4f6-4941-b217-2574b3830395',
    '8839ebc2-7db7-45d7-a2fc-67705bc1631c',
    'bdeccc87-beaf-43df-b475-807be044ed0b',
    NOW() - INTERVAL '7 hours',
    NOW() - INTERVAL '6 hours',
    NOW() - INTERVAL '7 hours',
    'SCHEDULED',
    '1cb982ef-b7e6-493c-86f9-fe4994f8ad93',
    'COACH_REQUIRED',
    1,
    TO_CHAR((NOW() - INTERVAL '7 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS'),
    TO_CHAR((NOW() - INTERVAL '6 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS'),
    'Asia/Ho_Chi_Minh',
    '+07:00',
    '+07:00'
)
ON CONFLICT (id) DO UPDATE SET
    status = EXCLUDED.status,
    version = fitness.planned_workouts.version + 1,
    planned_start_at = EXCLUDED.planned_start_at,
    planned_end_at = EXCLUDED.planned_end_at,
    original_planned_start_at = EXCLUDED.original_planned_start_at,
    schedule_local_start = EXCLUDED.schedule_local_start,
    schedule_local_end = EXCLUDED.schedule_local_end,
    schedule_timezone = EXCLUDED.schedule_timezone,
    schedule_start_utc_offset = EXCLUDED.schedule_start_utc_offset,
    schedule_end_utc_offset = EXCLUDED.schedule_end_utc_offset,
    rescheduled_from_id = NULL,
    schedule_change_id = NULL;

-- 5. Insert linked appointment in fitness.appointments
INSERT INTO fitness.appointments (
    id, trainer_id, student_id, relationship_id, coaching_period_id,
    planned_workout_id, start_at, end_at, display_timezone, status,
    version, guarded_v2, local_start, local_end, start_utc_offset,
    end_utc_offset, created_by
) VALUES (
    'a5000000-0000-4000-8000-000000000005',
    '1cb982ef-b7e6-493c-86f9-fe4994f8ad93',
    '5c6f49c6-f4f6-4941-b217-2574b3830395',
    '95501599-414d-4d0d-b663-296978c402a5',
    'bdeccc87-beaf-43df-b475-807be044ed0b',
    'f3000000-0000-4000-8000-000000000005',
    NOW() - INTERVAL '7 hours',
    NOW() - INTERVAL '6 hours',
    'Asia/Ho_Chi_Minh',
    'CONFIRMED',
    1,
    true,
    (NOW() - INTERVAL '7 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh',
    (NOW() - INTERVAL '6 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh',
    '+07:00',
    '+07:00',
    '1cb982ef-b7e6-493c-86f9-fe4994f8ad93'
)
ON CONFLICT (id) DO UPDATE SET
    status = 'CONFIRMED',
    version = fitness.appointments.version + 1,
    start_at = EXCLUDED.start_at,
    end_at = EXCLUDED.end_at;
