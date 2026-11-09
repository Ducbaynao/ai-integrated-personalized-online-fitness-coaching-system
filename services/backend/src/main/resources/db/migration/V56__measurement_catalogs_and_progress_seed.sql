SET search_path TO fitness, public;

-- Authoritative measurement units
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

-- Authoritative metric definitions
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

-- Authoritative measurement methods
INSERT INTO measurement_methods(code, name, description, typical_confidence) VALUES
('USER_SCALE', 'User scale', 'Manual weight measurement from a household scale', 80),
('TAPE_MEASURE', 'Tape measure', 'Manual body circumference measurement', 75),
('BIA_SMART_SCALE', 'BIA smart scale', 'Consumer bioelectrical impedance analysis', 65),
('INBODY_BIA', 'InBody BIA', 'Multi-frequency bioelectrical impedance analysis', 80),
('DEXA', 'DEXA', 'Dual-energy X-ray absorptiometry', 95),
('WEARABLE_SENSOR', 'Wearable sensor', 'Measurement from a wearable device', 75),
('SYSTEM_CALCULATION', 'System calculation', 'Derived by the Progress Engine', 90)
ON CONFLICT (code) DO NOTHING;

-- Authoritative measurement sources
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
