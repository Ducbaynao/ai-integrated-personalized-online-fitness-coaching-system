-- Forward Migration V64: Seed authoritative fitness.goal_types catalog (idempotent).
-- Preserves FAT_LOSS and WEIGHT_LOSS as distinct goal types without merging or conversion.
SET search_path TO fitness, public;

INSERT INTO goal_types (code, name, description, is_active) VALUES
('MUSCLE_GAIN', 'Muscle Gain', 'Increase lean muscle mass and hypertrophy', true),
('FAT_LOSS', 'Fat Loss', 'Reduce body fat while preserving lean mass (requires body-composition evidence)', true),
('WEIGHT_GAIN', 'Weight Gain', 'Increase overall body weight', true),
('WEIGHT_LOSS', 'Weight Loss', 'Reduce overall body weight', true),
('STRENGTH', 'Strength', 'Increase maximal and functional muscular strength', true),
('IMPROVE_FITNESS', 'Improve Fitness', 'Improve overall cardiovascular fitness, conditioning, and stamina', true),
('MAINTAIN', 'Maintain', 'Maintain current body composition, weight, and fitness level', true)
ON CONFLICT (code) DO NOTHING;