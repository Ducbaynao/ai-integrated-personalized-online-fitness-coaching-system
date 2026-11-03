-- ==============================================================================
-- F03 Development Seed Fixtures (Safe & Idempotent)
-- File: tools/seed_f03_dev_fixtures.sql
--
-- Purpose:
--   Seeds active exercise variations for SimulatedF03WorkoutProvider UUIDs
--   (b0000000-0000-4000-8000-000000000001 and b0000000-0000-4000-8000-000000000002)
--   under base exercise '8fd50c74-03f7-45a7-8c4b-fce16a96bdb4' (Barbell Bench Press)
--   to satisfy OD-3 base exercise equivalence in local development and mobile GUI tests.
--
-- Prerequisites:
--   - PostgreSQL database 'digital_fitness' running (port 5433 or Docker container)
--   - Base migrations V1..V55 applied (schema 'fitness' exists, exercise '8fd50c74-03f7-45a7-8c4b-fce16a96bdb4' exists)
--
-- Execution:
--   docker exec -i digital-fitness-postgres psql -U fitness_app -d digital_fitness < tools/seed_f03_dev_fixtures.sql
--
-- IMPORTANT ARCHITECTURAL NOTE:
--   Active status and shared base exercise in this script are development fixtures ONLY.
--   They enable local development and automated GUI testing without modifying Flyway migrations.
--   They do NOT constitute official OD-3 clinical or specialist approval for production exercise substitution.
-- ==============================================================================

INSERT INTO fitness.exercise_variations (
    id,
    exercise_id,
    code,
    name,
    description,
    instructions,
    difficulty,
    is_default,
    is_active,
    created_at,
    updated_at
) VALUES 
(
    'b0000000-0000-4000-8000-000000000001',
    '8fd50c74-03f7-45a7-8c4b-fce16a96bdb4',
    'VAR_BENCH_PRESS_ALT2',
    'Incline Barbell Bench Press',
    'Development fixture for F03 substitution provider alternate 1',
    'Standard bench press variation performed on an incline bench.',
    'INTERMEDIATE',
    false,
    true,
    NOW(),
    NOW()
),
(
    'b0000000-0000-4000-8000-000000000002',
    '8fd50c74-03f7-45a7-8c4b-fce16a96bdb4',
    'VAR_BENCH_PRESS_ALT',
    'Close-Grip Barbell Bench Press',
    'Development fixture for F03 substitution provider alternate 2',
    'Standard bench press variation performed with a closer hand grip.',
    'INTERMEDIATE',
    false,
    true,
    NOW(),
    NOW()
)
ON CONFLICT (id) DO UPDATE SET
    exercise_id = EXCLUDED.exercise_id,
    code = EXCLUDED.code,
    name = EXCLUDED.name,
    is_active = true,
    updated_at = NOW();
