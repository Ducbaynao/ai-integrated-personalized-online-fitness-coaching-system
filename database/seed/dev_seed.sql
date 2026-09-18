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
