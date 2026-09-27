-- Development/reference seed. Run after all Flyway migrations.
SET search_path TO fitness, public;

INSERT INTO roles(code, name, description) VALUES
('ADMIN', 'Administrator', 'System administration and governance'),
('TRAINER', 'Personal Trainer', 'Human coach role'),
('STUDENT', 'Student', 'Fitness learner and data owner')
ON CONFLICT (code) DO NOTHING;

INSERT INTO permissions(code, description) VALUES
('USER_SELF_READ', 'Read own account'),
('USER_SELF_UPDATE', 'Update own account'),
('STUDENT_DATA_READ', 'Read authorized student data'),
('STUDENT_DATA_WRITE', 'Write authorized student data'),
('WORKOUT_PLAN_MANAGE', 'Create and update workout plans'),
('GOAL_PROPOSAL_CREATE', 'Create goal proposals'),
('TRAINER_VERIFY', 'Review and verify trainers'),
('CATALOG_MANAGE', 'Manage exercise and knowledge catalogs'),
('AUDIT_READ', 'Read system audit data')
ON CONFLICT (code) DO NOTHING;

INSERT INTO roles(code, name, description) VALUES
('SUPER_ADMIN', 'Super Administrator', 'Emergency and platform-wide administration'),
('USER_ADMIN', 'User Administrator', 'Account and session operations'),
('CONTENT_ADMIN', 'Content Administrator', 'Exercise and knowledge governance'),
('SUPPORT_ADMIN', 'Support Administrator', 'Support and governed corrections'),
('MODERATION_ADMIN', 'Moderation Administrator', 'Trust and safety operations')
ON CONFLICT (code) DO NOTHING;

INSERT INTO permissions(code, description) VALUES
('USER_SUSPEND', 'Suspend user accounts'),
('USER_DISABLE', 'Disable user accounts'),
('USER_ROLE_MANAGE', 'Manage user roles'),
('TRAINER_ACTIVITY_MANAGE', 'Manage trainer activity'),
('KNOWLEDGE_PUBLISH', 'Publish governed knowledge'),
('DATA_CORRECTION_EXECUTE', 'Execute governed data corrections'),
('SYSTEM_CONFIG_MANAGE', 'Manage system configuration'),
('SESSION_REVOKE', 'Revoke user sessions')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN
('USER_SELF_READ', 'USER_SELF_UPDATE', 'STUDENT_DATA_READ', 'STUDENT_DATA_WRITE', 'WORKOUT_PLAN_MANAGE', 'GOAL_PROPOSAL_CREATE')
WHERE r.code = 'TRAINER'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN
('USER_SELF_READ', 'USER_SELF_UPDATE', 'STUDENT_DATA_WRITE')
WHERE r.code = 'STUDENT'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN
('USER_SELF_READ','USER_SUSPEND','USER_DISABLE','USER_ROLE_MANAGE','SESSION_REVOKE')
WHERE r.code = 'USER_ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO measurement_units(code, symbol, dimension, base_unit_code, multiplier_to_base, offset_to_base) VALUES
('KG', 'kg', 'MASS', 'KG', 1, 0),
('G', 'g', 'MASS', 'KG', 0.001, 0),
('LB', 'lb', 'MASS', 'KG', 0.45359237, 0),
('CM', 'cm', 'LENGTH', 'M', 0.01, 0),
('M', 'm', 'LENGTH', 'M', 1, 0),
('KM', 'km', 'LENGTH', 'M', 1000, 0),
('PERCENT', '%', 'RATIO', 'PERCENT', 1, 0),
('COUNT', 'count', 'COUNT', 'COUNT', 1, 0),
('SECOND', 's', 'TIME', 'SECOND', 1, 0),
('MINUTE', 'min', 'TIME', 'SECOND', 60, 0),
('KCAL', 'kcal', 'ENERGY', 'KCAL', 1, 0),
('ML', 'ml', 'VOLUME', 'ML', 1, 0),
('SERVING', 'serving', 'SERVING', 'SERVING', 1, 0),
('SCORE', 'score', 'SCORE', 'SCORE', 1, 0)
ON CONFLICT (code) DO NOTHING;

INSERT INTO metric_definitions(code, display_name, value_type, collection_type, default_unit_id, valid_min, valid_max, stale_after_days)
SELECT x.code, x.name, 'NUMERIC'::measurement_value_type, x.collection::metric_collection_type, u.id, x.min_value, x.max_value, x.stale_days
FROM (VALUES
  ('WEIGHT', 'Weight', 'DIRECT', 'KG', 20::numeric, 500::numeric, 14),
  ('HEIGHT', 'Height', 'DIRECT', 'CM', 50, 260, 365),
  ('BMI', 'Body Mass Index', 'CALCULATED', 'SCORE', 5, 100, 14),
  ('BODY_FAT_PERCENTAGE', 'Body Fat Percentage', 'DEVICE_DEPENDENT', 'PERCENT', 1, 75, 30),
  ('MUSCLE_MASS', 'Muscle Mass', 'DEVICE_DEPENDENT', 'KG', 5, 250, 30),
  ('BODY_WATER_PERCENTAGE', 'Body Water Percentage', 'DEVICE_DEPENDENT', 'PERCENT', 10, 85, 30),
  ('PROTEIN_PERCENTAGE', 'Protein Percentage', 'DEVICE_DEPENDENT', 'PERCENT', 1, 40, 30),
  ('VISCERAL_FAT', 'Visceral Fat', 'DEVICE_DEPENDENT', 'SCORE', 0, 100, 30),
  ('BONE_MASS', 'Bone Mass', 'DEVICE_DEPENDENT', 'KG', 0, 20, 60),
  ('WAIST_CIRCUMFERENCE', 'Waist Circumference', 'DIRECT', 'CM', 20, 300, 30),
  ('CHEST_CIRCUMFERENCE', 'Chest Circumference', 'DIRECT', 'CM', 20, 300, 30),
  ('HIP_CIRCUMFERENCE', 'Hip Circumference', 'DIRECT', 'CM', 20, 300, 30),
  ('ARM_CIRCUMFERENCE', 'Arm Circumference', 'DIRECT', 'CM', 5, 150, 30),
  ('THIGH_CIRCUMFERENCE', 'Thigh Circumference', 'DIRECT', 'CM', 10, 200, 30),
  ('STRENGTH_LOAD', 'Strength Load', 'DIRECT', 'KG', 0, 1000, 30),
  ('RESTING_HEART_RATE', 'Resting Heart Rate', 'DEVICE_DEPENDENT', 'COUNT', 20, 250, 7)
) AS x(code, name, collection, unit_code, min_value, max_value, stale_days)
JOIN measurement_units u ON u.code = x.unit_code
ON CONFLICT (code) DO NOTHING;

INSERT INTO measurement_methods(code, name, description, typical_confidence) VALUES
('USER_SCALE', 'User scale', 'Manual weight measurement from a household scale', 80),
('TAPE_MEASURE', 'Tape measure', 'Manual body circumference measurement', 75),
('BIA_SMART_SCALE', 'BIA smart scale', 'Consumer bioelectrical impedance analysis', 65),
('INBODY_BIA', 'InBody BIA', 'Multi-frequency bioelectrical impedance analysis', 80),
('DEXA', 'DEXA', 'Dual-energy X-ray absorptiometry', 95),
('WEARABLE_SENSOR', 'Wearable sensor', 'Measurement from a wearable device', 75),
('SYSTEM_CALCULATION', 'System calculation', 'Derived by the Progress Engine', 90)
ON CONFLICT (code) DO NOTHING;

INSERT INTO measurement_sources(code, name, source_kind) VALUES
('MANUAL_STUDENT', 'Student manual entry', 'MANUAL'),
('MANUAL_TRAINER', 'Trainer measurement', 'TRAINER'),
('SMART_SCALE', 'Smart scale', 'DEVICE'),
('INBODY', 'InBody', 'DEVICE'),
('APPLE_HEALTH', 'Apple Health', 'HEALTH_PLATFORM'),
('HEALTH_CONNECT', 'Health Connect', 'HEALTH_PLATFORM'),
('WEARABLE', 'Wearable device', 'DEVICE'),
('PROGRESS_ENGINE', 'Progress Engine', 'SYSTEM_DERIVED'),
('FILE_IMPORT', 'Imported file', 'IMPORT')
ON CONFLICT (code) DO NOTHING;

INSERT INTO goal_types(code, name) VALUES
('MUSCLE_GAIN', 'Muscle Gain'),
('FAT_LOSS', 'Fat Loss'),
('WEIGHT_GAIN', 'Weight Gain'),
('WEIGHT_LOSS', 'Weight Loss'),
('STRENGTH', 'Strength'),
('IMPROVE_FITNESS', 'Improve Fitness'),
('MAINTAIN', 'Maintain')
ON CONFLICT (code) DO NOTHING;

INSERT INTO nutrition_goal_types(code, name) VALUES
('SUPPORT_MUSCLE_GAIN', 'Support Muscle Gain'),
('CALORIC_DEFICIT', 'Caloric Deficit'),
('CALORIC_SURPLUS', 'Caloric Surplus'),
('MAINTENANCE', 'Maintenance'),
('PERFORMANCE_SUPPORT', 'Performance Support'),
('GENERAL_HEALTH', 'General Health')
ON CONFLICT (code) DO NOTHING;

INSERT INTO goal_type_metric_requirements(goal_type_id, metric_definition_id, requirement_level, baseline_required, suggested_interval_days, rationale)
SELECT gt.id, md.id, x.level::metric_requirement_level, x.baseline, x.interval_days, x.rationale
FROM (VALUES
  ('WEIGHT_GAIN','WEIGHT','REQUIRED',true,7,'Primary body-mass outcome'),
  ('WEIGHT_LOSS','WEIGHT','REQUIRED',true,7,'Primary body-mass outcome'),
  ('FAT_LOSS','WEIGHT','REQUIRED',true,7,'Accessible progress signal'),
  ('FAT_LOSS','BODY_FAT_PERCENTAGE','RECOMMENDED',false,30,'Useful when a reliable method is available'),
  ('MUSCLE_GAIN','WEIGHT','REQUIRED',true,7,'Accessible progress signal'),
  ('MUSCLE_GAIN','MUSCLE_MASS','RECOMMENDED',false,30,'Device-dependent optional refinement'),
  ('STRENGTH','STRENGTH_LOAD','REQUIRED',true,14,'Exercise performance outcome'),
  ('MAINTAIN','WEIGHT','RECOMMENDED',false,14,'Maintenance guardrail')
) x(goal_code,metric_code,level,baseline,interval_days,rationale)
JOIN goal_types gt ON gt.code=x.goal_code
JOIN metric_definitions md ON md.code=x.metric_code
ON CONFLICT DO NOTHING;

INSERT INTO exercise_tags(code, name) VALUES
('BEGINNER_FRIENDLY','Beginner Friendly'),('BODYWEIGHT','Bodyweight'),('LOW_IMPACT','Low Impact'),
('COMPOUND','Compound'),('ISOLATION','Isolation'),('CARDIO','Cardio'),('MOBILITY','Mobility')
ON CONFLICT (code) DO NOTHING;

INSERT INTO measurement_source_priorities(source_id, priority_rank, reason)
SELECT s.id, x.priority_rank, 'Default source-quality ordering'
FROM (VALUES ('DEXA',1),('INBODY',2),('MANUAL_TRAINER',3),('SMART_SCALE',4),('MANUAL_STUDENT',5),('WEARABLE',6),('FILE_IMPORT',7)) x(source_code,priority_rank)
JOIN measurement_sources s ON s.code=x.source_code
ON CONFLICT DO NOTHING;

INSERT INTO trainer_specialties(code, name) VALUES
('FAT_LOSS', 'Fat Loss'),
('MUSCLE_GAIN', 'Muscle Gain'),
('STRENGTH_TRAINING', 'Strength Training'),
('BODYBUILDING', 'Bodybuilding'),
('GENERAL_FITNESS', 'General Fitness'),
('ONLINE_COACHING', 'Online Coaching')
ON CONFLICT (code) DO NOTHING;

INSERT INTO exercise_categories(code, name) VALUES
('STRENGTH', 'Strength'),
('CARDIO', 'Cardio'),
('MOBILITY', 'Mobility'),
('FLEXIBILITY', 'Flexibility'),
('BALANCE', 'Balance')
ON CONFLICT (code) DO NOTHING;

INSERT INTO muscle_groups(code, name) VALUES
('CHEST', 'Chest'), ('BACK', 'Back'), ('SHOULDERS', 'Shoulders'),
('BICEPS', 'Biceps'), ('TRICEPS', 'Triceps'), ('FOREARMS', 'Forearms'),
('QUADRICEPS', 'Quadriceps'), ('HAMSTRINGS', 'Hamstrings'), ('GLUTES', 'Glutes'),
('CALVES', 'Calves'), ('CORE', 'Core'), ('FULL_BODY', 'Full Body')
ON CONFLICT (code) DO NOTHING;

INSERT INTO equipment(code, name) VALUES
('BODYWEIGHT', 'Bodyweight'), ('DUMBBELL', 'Dumbbell'), ('BARBELL', 'Barbell'),
('BENCH', 'Bench'), ('CABLE_MACHINE', 'Cable Machine'), ('RESISTANCE_BAND', 'Resistance Band'),
('TREADMILL', 'Treadmill'), ('STATIONARY_BIKE', 'Stationary Bike'), ('KETTLEBELL', 'Kettlebell'),
('PULLUP_BAR', 'Pull-up Bar')
ON CONFLICT (code) DO NOTHING;

-- Synthetic B02 catalog fixtures. These records are deterministic development data and contain no
-- copied commercial exercise descriptions or media URLs.
INSERT INTO exercises(
    id, code, name, category_id, description, instructions, difficulty,
    movement_pattern, unilateral, admin_status
)
SELECT x.id::uuid, x.code, x.name, c.id, x.description, x.instructions, x.difficulty,
       x.movement_pattern, x.unilateral, x.admin_status
FROM (VALUES
  ('10000000-0000-0000-0000-000000000001','SYNTH_BODYWEIGHT_SQUAT','Synthetic Bodyweight Squat','STRENGTH','Synthetic lower-body exercise for development testing.','Keep a stable stance and move through a comfortable range.','BEGINNER','SQUAT',false,'ACTIVE'),
  ('10000000-0000-0000-0000-000000000002','SYNTH_ASSISTED_ROW','Synthetic Assisted Row','STRENGTH','Synthetic pulling exercise for development testing.','Pull with control while keeping the torso stable.','BEGINNER','PULL',false,'ACTIVE'),
  ('10000000-0000-0000-0000-000000000003','SYNTH_CABLE_PRESS','Synthetic Cable Press','STRENGTH','Synthetic pushing exercise for development testing.','Press forward with a controlled return.','INTERMEDIATE','PUSH',false,'ACTIVE'),
  ('10000000-0000-0000-0000-000000000004','SYNTH_MOBILITY_REACH','Synthetic Mobility Reach','MOBILITY','Synthetic mobility exercise for development testing.','Move slowly within a comfortable range.','BEGINNER','MOBILITY',false,'ACTIVE'),
  ('10000000-0000-0000-0000-000000000005','SYNTH_STATIONARY_CYCLE','Synthetic Stationary Cycle','CARDIO','Synthetic cardio exercise for development testing.','Use a sustainable cadence and stop if discomfort occurs.','BEGINNER','CARDIO',false,'ACTIVE'),
  ('10000000-0000-0000-0000-000000000006','SYNTH_UNILATERAL_LUNGE','Synthetic Unilateral Lunge','STRENGTH','Synthetic unilateral lower-body exercise for development testing.','Maintain balance and use a controlled step.','INTERMEDIATE','LUNGE',true,'ACTIVE'),
  ('10000000-0000-0000-0000-000000000007','SYNTH_DRAFT_HINGE','Synthetic Draft Hinge','STRENGTH','Synthetic draft record.','Draft instructions.','BEGINNER','HINGE',false,'DRAFT'),
  ('10000000-0000-0000-0000-000000000008','SYNTH_ARCHIVED_CARRY','Synthetic Archived Carry','STRENGTH','Synthetic archived record.','Archived instructions.','INTERMEDIATE','CARRY',false,'ARCHIVED'),
  ('10000000-0000-0000-0000-000000000009','SYNTH_INACTIVE_STEP','Synthetic Inactive Step','STRENGTH','Synthetic inactive legacy record.','Inactive instructions.','BEGINNER','LUNGE',true,'INACTIVE')
) x(id,code,name,category_code,description,instructions,difficulty,movement_pattern,unilateral,admin_status)
JOIN exercise_categories c ON c.code=x.category_code
ON CONFLICT (code) DO NOTHING;

INSERT INTO exercise_variations(
    id, exercise_id, code, name, description, instructions, difficulty, is_default, is_active
)
SELECT x.id::uuid, e.id, x.code, x.name, x.description, x.instructions, x.difficulty, x.is_default, true
FROM (VALUES
  ('20000000-0000-0000-0000-000000000001','SYNTH_BODYWEIGHT_SQUAT','SYNTH_BODYWEIGHT_SQUAT_STANDARD','Synthetic Bodyweight Squat - Standard','Standard synthetic variation.','Keep a stable stance and controlled tempo.','BEGINNER',true),
  ('20000000-0000-0000-0000-000000000002','SYNTH_BODYWEIGHT_SQUAT','SYNTH_BODYWEIGHT_SQUAT_PAUSE','Synthetic Bodyweight Squat - Pause','Synthetic paused variation.','Pause briefly only within a comfortable range.','INTERMEDIATE',false),
  ('20000000-0000-0000-0000-000000000003','SYNTH_ASSISTED_ROW','SYNTH_ASSISTED_ROW_BAND','Synthetic Assisted Row - Band','Synthetic band variation.','Keep tension controlled through the full motion.','BEGINNER',true),
  ('20000000-0000-0000-0000-000000000004','SYNTH_CABLE_PRESS','SYNTH_CABLE_PRESS_STANDARD','Synthetic Cable Press - Standard','Standard synthetic press variation.','Press and return without abrupt movement.','INTERMEDIATE',true),
  ('20000000-0000-0000-0000-000000000005','SYNTH_MOBILITY_REACH','SYNTH_MOBILITY_REACH_STANDARD','Synthetic Mobility Reach - Standard','Standard synthetic mobility variation.','Use a slow and comfortable motion.','BEGINNER',true),
  ('20000000-0000-0000-0000-000000000006','SYNTH_STATIONARY_CYCLE','SYNTH_STATIONARY_CYCLE_STEADY','Synthetic Stationary Cycle - Steady','Synthetic steady-state variation.','Maintain a sustainable effort.','BEGINNER',true),
  ('20000000-0000-0000-0000-000000000007','SYNTH_UNILATERAL_LUNGE','SYNTH_UNILATERAL_LUNGE_DUMBBELL','Synthetic Unilateral Lunge - Dumbbell','Synthetic loaded variation.','Use a stable load and controlled step.','INTERMEDIATE',true),
  ('20000000-0000-0000-0000-000000000008','SYNTH_DRAFT_HINGE','SYNTH_DRAFT_HINGE_STANDARD','Synthetic Draft Hinge - Standard','Synthetic draft variation.','Draft instructions.','BEGINNER',true),
  ('20000000-0000-0000-0000-000000000009','SYNTH_ARCHIVED_CARRY','SYNTH_ARCHIVED_CARRY_STANDARD','Synthetic Archived Carry - Standard','Synthetic archived variation.','Archived instructions.','INTERMEDIATE',true),
  ('20000000-0000-0000-0000-000000000010','SYNTH_INACTIVE_STEP','SYNTH_INACTIVE_STEP_STANDARD','Synthetic Inactive Step - Standard','Synthetic inactive variation.','Inactive instructions.','BEGINNER',true)
) x(id,exercise_code,code,name,description,instructions,difficulty,is_default)
JOIN exercises e ON e.code=x.exercise_code
ON CONFLICT (code) DO NOTHING;

INSERT INTO exercise_muscles(exercise_variation_id, muscle_group_id, involvement)
SELECT v.id, mg.id, x.involvement
FROM (VALUES
  ('SYNTH_BODYWEIGHT_SQUAT_STANDARD','QUADRICEPS','PRIMARY'),
  ('SYNTH_BODYWEIGHT_SQUAT_STANDARD','GLUTES','PRIMARY'),
  ('SYNTH_BODYWEIGHT_SQUAT_STANDARD','CORE','STABILIZER'),
  ('SYNTH_BODYWEIGHT_SQUAT_PAUSE','QUADRICEPS','PRIMARY'),
  ('SYNTH_ASSISTED_ROW_BAND','BACK','PRIMARY'),
  ('SYNTH_ASSISTED_ROW_BAND','BICEPS','SECONDARY'),
  ('SYNTH_CABLE_PRESS_STANDARD','CHEST','PRIMARY'),
  ('SYNTH_CABLE_PRESS_STANDARD','TRICEPS','SECONDARY'),
  ('SYNTH_MOBILITY_REACH_STANDARD','SHOULDERS','PRIMARY'),
  ('SYNTH_STATIONARY_CYCLE_STEADY','QUADRICEPS','PRIMARY'),
  ('SYNTH_UNILATERAL_LUNGE_DUMBBELL','QUADRICEPS','PRIMARY'),
  ('SYNTH_UNILATERAL_LUNGE_DUMBBELL','GLUTES','PRIMARY')
) x(variation_code,muscle_code,involvement)
JOIN exercise_variations v ON v.code=x.variation_code
JOIN muscle_groups mg ON mg.code=x.muscle_code
ON CONFLICT DO NOTHING;

INSERT INTO exercise_equipment(exercise_variation_id, equipment_id, requirement)
SELECT v.id, eq.id, x.requirement
FROM (VALUES
  ('SYNTH_BODYWEIGHT_SQUAT_STANDARD','BODYWEIGHT','REQUIRED'),
  ('SYNTH_BODYWEIGHT_SQUAT_PAUSE','BODYWEIGHT','REQUIRED'),
  ('SYNTH_ASSISTED_ROW_BAND','RESISTANCE_BAND','REQUIRED'),
  ('SYNTH_ASSISTED_ROW_BAND','PULLUP_BAR','ALTERNATIVE'),
  ('SYNTH_CABLE_PRESS_STANDARD','CABLE_MACHINE','REQUIRED'),
  ('SYNTH_CABLE_PRESS_STANDARD','BENCH','OPTIONAL'),
  ('SYNTH_MOBILITY_REACH_STANDARD','BODYWEIGHT','REQUIRED'),
  ('SYNTH_STATIONARY_CYCLE_STEADY','STATIONARY_BIKE','REQUIRED'),
  ('SYNTH_UNILATERAL_LUNGE_DUMBBELL','DUMBBELL','REQUIRED'),
  ('SYNTH_UNILATERAL_LUNGE_DUMBBELL','BENCH','OPTIONAL')
) x(variation_code,equipment_code,requirement)
JOIN exercise_variations v ON v.code=x.variation_code
JOIN equipment eq ON eq.code=x.equipment_code
ON CONFLICT DO NOTHING;

INSERT INTO exercise_tag_assignments(exercise_id, exercise_tag_id)
SELECT e.id, t.id
FROM (VALUES
  ('SYNTH_BODYWEIGHT_SQUAT','BEGINNER_FRIENDLY'),
  ('SYNTH_BODYWEIGHT_SQUAT','BODYWEIGHT'),
  ('SYNTH_BODYWEIGHT_SQUAT','COMPOUND'),
  ('SYNTH_ASSISTED_ROW','BEGINNER_FRIENDLY'),
  ('SYNTH_ASSISTED_ROW','COMPOUND'),
  ('SYNTH_CABLE_PRESS','COMPOUND'),
  ('SYNTH_MOBILITY_REACH','MOBILITY'),
  ('SYNTH_MOBILITY_REACH','LOW_IMPACT'),
  ('SYNTH_STATIONARY_CYCLE','CARDIO'),
  ('SYNTH_UNILATERAL_LUNGE','COMPOUND')
) x(exercise_code,tag_code)
JOIN exercises e ON e.code=x.exercise_code
JOIN exercise_tags t ON t.code=x.tag_code
ON CONFLICT DO NOTHING;

INSERT INTO nutrients(code, name, default_unit_id, is_macro)
SELECT x.code, x.name, u.id, x.is_macro
FROM (VALUES
  ('ENERGY_KCAL', 'Energy', 'KCAL', true),
  ('PROTEIN', 'Protein', 'G', true),
  ('CARBOHYDRATE', 'Carbohydrate', 'G', true),
  ('FAT', 'Fat', 'G', true),
  ('FIBER', 'Fiber', 'G', false),
  ('SODIUM', 'Sodium', 'G', false),
  ('SUGAR', 'Sugar', 'G', false)
) AS x(code, name, unit_code, is_macro)
JOIN measurement_units u ON u.code = x.unit_code
ON CONFLICT (code) DO NOTHING;

INSERT INTO integration_providers(code, name, provider_type) VALUES
('APPLE_HEALTH', 'Apple Health', 'HEALTH_PLATFORM'),
('HEALTH_CONNECT', 'Google Health Connect', 'HEALTH_PLATFORM'),
('INBODY', 'InBody', 'BODY_COMPOSITION'),
('GENERIC_SMART_SCALE', 'Generic Smart Scale', 'SMART_SCALE'),
('GENERIC_WEARABLE', 'Generic Wearable', 'WEARABLE')
ON CONFLICT (code) DO NOTHING;
