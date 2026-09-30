SET search_path TO fitness, public;

CREATE TABLE exercise_movement_patterns (
    code varchar(60) PRIMARY KEY,
    display_name varchar(120) NOT NULL,
    is_active boolean NOT NULL DEFAULT true
);

INSERT INTO exercise_movement_patterns(code, display_name) VALUES
('SQUAT', 'Squat'),
('HINGE', 'Hip Hinge'),
('LUNGE', 'Lunge'),
('PUSH', 'Push'),
('PULL', 'Pull'),
('CARRY', 'Loaded Carry'),
('ROTATION', 'Rotation'),
('CORE_STABILITY', 'Core Stability'),
('LOCOMOTION', 'Locomotion'),
('ISOLATION', 'Isolation'),
('MOBILITY', 'Mobility'),
('BALANCE', 'Balance');

-- Match the application input convention before assessing legacy values.
UPDATE exercises
   SET movement_pattern = upper(btrim(movement_pattern))
 WHERE movement_pattern IS NOT NULL
   AND movement_pattern IS DISTINCT FROM upper(btrim(movement_pattern));

DO $$
DECLARE
    unknown_codes text;
BEGIN
    SELECT string_agg(DISTINCT movement_pattern, ', ' ORDER BY movement_pattern)
      INTO unknown_codes
      FROM exercises
     WHERE movement_pattern IS NOT NULL
       AND NOT EXISTS (
           SELECT 1
             FROM exercise_movement_patterns pattern
            WHERE pattern.code = exercises.movement_pattern
       );

    IF unknown_codes IS NOT NULL THEN
        RAISE EXCEPTION 'V23 cannot migrate unknown Exercise movement patterns: %', unknown_codes;
    END IF;
END;
$$;

ALTER TABLE exercises
    ADD CONSTRAINT fk_exercises_movement_pattern
        FOREIGN KEY (movement_pattern)
        REFERENCES exercise_movement_patterns(code)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT;
